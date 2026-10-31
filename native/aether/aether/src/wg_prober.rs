use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::stream::StreamExt;
use rand::RngExt;

use crate::aethernoize::AetherNoizeConfig;
use crate::error::{AetherError, Result};
use crate::prober::IpScan;
use crate::wireguard;

#[derive(Debug, Clone, Copy)]
pub struct WgProbeResult {
    pub ip: IpAddr,
    pub port: u16,
    pub rtt: Duration,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WgScanMode {
    Turbo,
    Balanced,
    Thorough,
    Verified,
    Ironclad,
}

impl WgScanMode {
    pub fn parse(s: &str) -> WgScanMode {
        match s.trim().to_lowercase().as_str() {
            "turbo" | "fast" => WgScanMode::Turbo,
            "thorough" | "deep" | "pro" => WgScanMode::Thorough,
            "verified" | "proven" | "stealth" | "quiet" => WgScanMode::Verified,
            "ironclad" | "real" | "verify" | "guaranteed" => WgScanMode::Ironclad,
            _ => WgScanMode::Balanced,
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            WgScanMode::Turbo => "turbo",
            WgScanMode::Balanced => "balanced",
            WgScanMode::Thorough => "thorough",
            WgScanMode::Verified => "verified",
            WgScanMode::Ironclad => "ironclad",
        }
    }

    fn strategy(&self) -> WgStrategy {
        match self {
            WgScanMode::Turbo => WgStrategy {
                concurrency: 12,
                per_probe_timeout: Duration::from_millis(5000),
                overall_deadline: Duration::from_secs(30),
                quiet_after_first: Duration::from_secs(0),
                target_successes: 1,
                early_exit_first: true,
                full_subnet: false,
                sample_per_cidr: 40,
                pool_port_waves: 1,
            },
            WgScanMode::Balanced => WgStrategy {
                concurrency: 8,
                per_probe_timeout: Duration::from_millis(7000),
                overall_deadline: Duration::from_secs(80),
                quiet_after_first: Duration::from_secs(12),
                target_successes: 5,
                early_exit_first: false,
                full_subnet: false,
                sample_per_cidr: 120,
                pool_port_waves: 3,
            },
            WgScanMode::Thorough => WgStrategy {
                concurrency: 10,
                per_probe_timeout: Duration::from_millis(9000),
                overall_deadline: Duration::from_secs(250),
                quiet_after_first: Duration::from_secs(25),
                target_successes: 0,
                early_exit_first: false,
                full_subnet: true,
                sample_per_cidr: 0,
                pool_port_waves: 4,
            },
            WgScanMode::Verified => WgStrategy {
                concurrency: 10,
                per_probe_timeout: Duration::from_millis(5000),
                overall_deadline: Duration::from_secs(60),
                quiet_after_first: Duration::from_secs(8),
                target_successes: 6,
                early_exit_first: false,
                full_subnet: false,
                sample_per_cidr: 48,
                pool_port_waves: 4,
            },
            WgScanMode::Ironclad => WgStrategy {
                concurrency: 4,
                per_probe_timeout: Duration::from_millis(15000),
                overall_deadline: Duration::from_secs(180),
                quiet_after_first: Duration::from_secs(15),
                target_successes: 3,
                early_exit_first: false,
                full_subnet: false,
                sample_per_cidr: 120,
                pool_port_waves: 3,
            },
        }
    }
}

const WG_IRONCLAD_TCPING_TIMEOUT: Duration = Duration::from_secs(10);

struct WgStrategy {
    concurrency: usize,
    per_probe_timeout: Duration,
    overall_deadline: Duration,
    quiet_after_first: Duration,
    target_successes: usize,
    early_exit_first: bool,
    full_subnet: bool,
    sample_per_cidr: usize,
    pool_port_waves: usize,
}

#[derive(Clone)]
pub struct WgProbe {
    pub private_key: Arc<[u8; 32]>,
    pub peer_public_key: Arc<[u8; 32]>,
    pub client_id: [u8; 3],
    pub local_ipv4: Ipv4Addr,
    pub aethernoize: AetherNoizeConfig,
    pub ports: Vec<u16>,
    pub ip: IpScan,
    pub excluded: HashSet<SocketAddr>,
}

// >>> AETHER-APP-PATCH scan-rtt-quality-gate (1.2.8-r4)
/// RTT a scanned endpoint has to beat before the turbo scan commits to it.
///
/// ## ROOT CAUSE this fixes
///
/// `WgScanMode::Turbo` sets `early_exit_first`, and the loop below took that
/// literally: **the first candidate that answered was returned, whatever its
/// RTT**, 0.48 s into a 30 s budget with 79 of 80 candidates still unprobed.
/// There was no quality check anywhere on the scan's own result - only on the
/// CACHED endpoint, which produced a build that openly contradicted itself.
/// From the field log, 2 ms apart:
///
/// ```text
/// [-] cached endpoint 162.159.192.115:859 answered but is slow
///     (rtt 396.851077ms over the 180ms budget); ignoring the cache and
///     scanning for a faster endpoint
/// [+] wg candidate ok 162.159.195.92:1701 rtt=475.011923ms
/// [+] selected WireGuard endpoint 162.159.195.92:1701
/// ```
///
/// It threw away a 397 ms endpoint for being too slow and then committed the
/// whole session to a 475 ms one - **79 ms worse than what it had just
/// rejected** - while the DPI fingerprint in the same second had measured
/// 104-115 ms edges on the very ranges being scanned. Every number the user has
/// screenshotted is built on top of that: the endpoint in a1 and a2 is this one,
/// and in the chained mode its RTT is paid twice.
///
/// So the gate that guards the cache now guards the scan too, and by
/// construction with the SAME number: `AETHER_SCAN_GOOD_RTT_MS` if the app sends
/// it, otherwise `AETHER_QUICK_RECONNECT_MAX_RTT_MS` (the cache budget), other-
/// wise 180 ms. It is impossible for this build to reject an endpoint as too
/// slow and then choose a slower one.
fn good_rtt_budget() -> Option<Duration> {
    for name in ["AETHER_SCAN_GOOD_RTT_MS", "AETHER_QUICK_RECONNECT_MAX_RTT_MS"] {
        if let Some(ms) = std::env::var(name)
            .ok()
            .and_then(|v| v.trim().parse::<u64>().ok())
            .filter(|ms| *ms > 0)
        {
            return Some(Duration::from_millis(ms));
        }
    }
    Some(Duration::from_millis(180))
}

/// How long the turbo scan keeps looking after an over-budget first answer.
///
/// The point of turbo is that connecting is fast, so this is deliberately short:
/// a second of extra scanning, once, against a session that would otherwise run
/// for an hour on a needlessly slow endpoint. The first answer is KEPT as the
/// fallback throughout, so this can never turn a working connect into a failure -
/// worst case it costs this much time and picks the same endpoint anyway.
fn slow_first_grace() -> Duration {
    let ms = std::env::var("AETHER_SCAN_SLOW_FIRST_GRACE_MS")
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .filter(|ms| *ms > 0)
        .unwrap_or(1_200);
    Duration::from_millis(ms)
}
// <<< AETHER-APP-PATCH scan-rtt-quality-gate

// >>> AETHER-APP-PATCH scan-rtt-floor (1.2.8-r5)
/// The RTT a scan is not allowed to come back WORSE than, plus the endpoint that
/// set it.
///
/// ## Why r4's gate was not enough
///
/// r4 added a quality target and its own doc comment claimed "it is impossible
/// for this build to reject an endpoint as too slow and then choose a slower
/// one." That claim was wrong, and the field log in this round shows the exact
/// hole: the target only decides whether turbo commits EARLY. When the grace
/// window closes and nothing under the target was found, the slow first answer
/// is still returned as the fallback. On the logged run that fallback is
/// 475.01 ms, and the cache that had just been discarded for being slow was
/// 396.85 ms - so the contradiction survives r4 in full, and the session still
/// ends up 78 ms worse off than doing nothing at all.
///
/// A target is an aspiration. What was missing is a FLOOR: a hard statement that
/// whatever we replace the cache with must actually be better than the cache.
///
/// So the moment quick-reconnect discards a cached endpoint for being slow, it
/// registers that endpoint and its measured RTT here. If the scan then finishes
/// without beating it, [apply_rtt_floor] returns the cached endpoint instead of
/// the slower winner. That endpoint was verified alive milliseconds earlier by
/// the very probe that measured it, so reusing it is safe by construction, and it
/// is strictly the better of the two choices available.
///
/// Net effect: rejecting the cache can now only ever IMPROVE the endpoint, never
/// degrade it. The pathological case in the log becomes "no faster edge found,
/// keeping the 396 ms cache".
static RTT_FLOOR_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static RTT_FLOOR_PEER: std::sync::Mutex<Option<SocketAddr>> = std::sync::Mutex::new(None);

/// Registers the endpoint a scan has to beat. Called by quick-reconnect when it
/// throws a cached endpoint away for being slow.
pub fn set_rtt_floor(peer: SocketAddr, rtt: Duration) {
    RTT_FLOOR_MS.store(rtt.as_millis() as u64, std::sync::atomic::Ordering::Relaxed);
    if let Ok(mut guard) = RTT_FLOOR_PEER.lock() {
        *guard = Some(peer);
    }
}

/// Forgets the floor. Called once a session is actually up, so a later reconnect
/// is never judged against a stale measurement.
pub fn clear_rtt_floor() {
    RTT_FLOOR_MS.store(0, std::sync::atomic::Ordering::Relaxed);
    if let Ok(mut guard) = RTT_FLOOR_PEER.lock() {
        *guard = None;
    }
}

fn rtt_floor() -> Option<(SocketAddr, Duration)> {
    let ms = RTT_FLOOR_MS.load(std::sync::atomic::Ordering::Relaxed);
    if ms == 0 {
        return None;
    }
    let peer = (*RTT_FLOOR_PEER.lock().ok()?)?;
    Some((peer, Duration::from_millis(ms)))
}

/// Substitutes the discarded endpoint back in when the scan failed to beat it.
///
/// Applied to the FIRST (fastest) element only: `distinct_by_ip` has already
/// sorted by RTT, so if the head does not clear the floor, nothing does.
fn apply_rtt_floor(mut picked: Vec<WgProbeResult>) -> Vec<WgProbeResult> {
    let Some((peer, floor)) = rtt_floor() else {
        return picked;
    };
    // Copy the head out before touching `picked` again: the insert below needs a
    // mutable borrow and the comparison only needs three Copy fields.
    let (best_ip, best_port, best_rtt) = match picked.first() {
        Some(b) => (b.ip, b.port, b.rtt),
        None => return picked,
    };
    if best_rtt <= floor {
        return picked;
    }
    log::warn!(
        "[-] the scan's best edge {}:{} (rtt {:?}) is SLOWER than the cached endpoint \
         {peer} (rtt {:?}) that was discarded for being slow; keeping the cache, because \
         replacing an endpoint with a worse one is not an upgrade",
        best_ip,
        best_port,
        best_rtt,
        floor,
    );
    let restored = WgProbeResult {
        ip: peer.ip(),
        port: peer.port(),
        rtt: floor,
    };
    // Keep the scanned results behind it as alternates for the retry ladder.
    picked.insert(0, restored);
    picked
}
// <<< AETHER-APP-PATCH scan-rtt-floor

pub async fn hunt_best_wg_endpoint(probe: &WgProbe, mode: WgScanMode) -> Result<WgProbeResult> {
    hunt_wg_endpoints(probe, mode, 1)
        .await?
        .into_iter()
        .next()
        .ok_or(AetherError::NoCleanEndpoint)
}

pub async fn hunt_wg_endpoints(
    probe: &WgProbe,
    mode: WgScanMode,
    want: usize,
) -> Result<Vec<WgProbeResult>> {
    let want = want.max(1);
    let mut st = mode.strategy();
    st.concurrency = crate::sysprofile::cap_concurrency(st.concurrency);
    let timeout = st.per_probe_timeout;
    let mut effective_ip = probe.ip;
    if probe.ip.want_v6() && !crate::prober::host_has_ipv6().await {
        if probe.ip.want_v4() {
            log::warn!("[-] host has no IPv6 route; falling back to IPv4-only scan");
            effective_ip = IpScan::V4;
        } else {
            log::warn!("[-] host has no IPv6 route; IPv6 scan needs native IPv6 connectivity");
            return Err(AetherError::NoCleanEndpoint);
        }
    }
    let candidates = build_wg_candidates(&st, &probe.ports, effective_ip, &probe.excluded);

    log::info!(
        "[*] wireguard scan mode={} ip={} candidates={} ports={:?} concurrency={} per_probe={:?} budget={:?}",
        mode.label(),
        effective_ip.label(),
        candidates.len(),
        probe.ports,
        st.concurrency,
        st.per_probe_timeout,
        st.overall_deadline,
    );

    let ironclad = mode == WgScanMode::Ironclad;

    let stream = futures::stream::iter(
        candidates
            .into_iter()
            .map(|(ip, port)| verify_one_wg(probe, ip, port, timeout, ironclad)),
    )
    .buffer_unordered(st.concurrency);
    tokio::pin!(stream);

    if want > 1 {
        st.early_exit_first = false;
        st.target_successes = st.target_successes.max(want * 3);
    }

    let deadline = Instant::now() + st.overall_deadline;
    let mut verified: Vec<WgProbeResult> = Vec::new();
    let mut found = 0usize;
    let mut quiet_until: Option<Instant> = None;
    // 1.2.8-r4: set when turbo's first answer came back over budget, so the scan
    // keeps looking for a fast one instead of committing to a slow one. Only
    // reachable from the `early_exit_first` path, so multi-endpoint hunts
    // (`want > 1`, which clears that flag above) behave exactly as before.
    let rtt_budget = good_rtt_budget();
    let mut hunting_for_fast = false;

    loop {
        let effective = match quiet_until {
            Some(q) => q.min(deadline),
            None => deadline,
        };
        let remaining = effective.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            if !verified.is_empty() {
                if quiet_until.is_some() {
                    log::info!("[+] no new endpoints recently, finalizing selection");
                } else {
                    log::warn!("[-] scan deadline reached");
                }
            } else {
                log::warn!("[-] scan deadline reached with no endpoint");
            }
            break;
        }

        tokio::select! {
            item = stream.next() => {
                match item {
                    None => break,
                    Some(None) => continue,
                    Some(Some(pr)) => {
                        log::info!("[+] wg candidate ok {}:{} rtt={:?}", pr.ip, pr.port, pr.rtt);

                        // >>> AETHER-APP-PATCH scan-rtt-quality-gate (1.2.8-r4)
                        // See [good_rtt_budget]. "First to answer" is not the
                        // same thing as "good", and turbo used to treat it as if
                        // it were.
                        let fast_enough = rtt_budget.map_or(true, |limit| pr.rtt <= limit)
                            // >>> AETHER-APP-PATCH scan-rtt-floor (1.2.8-r5)
                            // Committing early is only allowed if this answer is
                            // also genuinely better than whatever cache we threw
                            // away to get here. Without this the turbo fast path
                            // could still exit on an endpoint the floor would have
                            // rejected two lines later.
                            && rtt_floor().map_or(true, |(_, floor)| pr.rtt <= floor);
                            // <<< AETHER-APP-PATCH scan-rtt-floor

                        if (st.early_exit_first || hunting_for_fast) && fast_enough {
                            if hunting_for_fast {
                                log::info!(
                                    "[+] found a fast endpoint {}:{} (rtt {:?}) within the {:?} target; taking it over the slow first answer",
                                    pr.ip, pr.port, pr.rtt, rtt_budget.unwrap_or_default(),
                                );
                            }
                            return Ok(vec![pr]);
                        }

                        if st.early_exit_first {
                            // Over budget. Keep it as the fallback, but spend a
                            // short grace window looking for something better
                            // instead of committing the whole session to it.
                            // When the window closes, `distinct_by_ip` sorts by
                            // RTT, so the FASTEST endpoint found wins - never
                            // merely the first one to answer.
                            log::warn!(
                                "[-] first wg answer {}:{} is slow (rtt {:?} over the {:?} target); \
                                 keeping it as a fallback and scanning {:?} more for a faster edge",
                                pr.ip, pr.port, pr.rtt,
                                rtt_budget.unwrap_or_default(), slow_first_grace(),
                            );
                            st.early_exit_first = false;
                            hunting_for_fast = true;
                            quiet_until = Some(Instant::now() + slow_first_grace());
                            verified.push(pr);
                            found += 1;
                            continue;
                        }
                        // <<< AETHER-APP-PATCH scan-rtt-quality-gate

                        verified.push(pr);
                        found += 1;

                        if distinct_by_ip(&verified).len() >= want && want > 1 {
                            log::info!("[+] found {want} endpoints on separate addresses");
                            break;
                        }


                        if st.target_successes > 0 && found >= st.target_successes && quiet_until.is_none() {
                            log::info!("[+] reached target of {} endpoints, selecting best", st.target_successes);
                            if !st.quiet_after_first.is_zero() {
                                quiet_until = Some(Instant::now() + st.quiet_after_first);
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            _ = tokio::time::sleep(remaining) => {
                if !verified.is_empty() {
                    if quiet_until.is_some() {
                        log::info!("[+] no new endpoints recently, finalizing selection");
                    } else {
                        log::warn!("[-] scan deadline reached");
                    }
                } else {
                    log::warn!("[-] scan deadline reached with no endpoint");
                }
                break;
            }
        }
    }

    // >>> AETHER-APP-PATCH scan-rtt-floor (1.2.8-r5)
    // distinct_by_ip sorts by RTT, so the head is the fastest thing the scan
    // found. apply_rtt_floor puts the discarded cache back in front of it when
    // the scan failed to beat it. See [apply_rtt_floor].
    let picked = apply_rtt_floor(distinct_by_ip(&verified));
    // <<< AETHER-APP-PATCH scan-rtt-floor
    if picked.is_empty() {
        return Err(AetherError::NoCleanEndpoint);
    }

    for pr in picked.iter().take(want) {
        log::info!("[+] wg endpoint {}:{} rtt={:?}", pr.ip, pr.port, pr.rtt);
    }

    Ok(picked.into_iter().take(want).collect())
}

fn distinct_by_ip(found: &[WgProbeResult]) -> Vec<WgProbeResult> {
    let mut sorted = found.to_vec();
    sorted.sort_by_key(|pr| pr.rtt);

    let mut seen = std::collections::HashSet::new();
    sorted.into_iter().filter(|pr| seen.insert(pr.ip)).collect()
}

async fn verify_one_wg(
    probe: &WgProbe,
    ip: IpAddr,
    port: u16,
    timeout: Duration,
    ironclad: bool,
) -> Option<WgProbeResult> {
    let peer = SocketAddr::new(ip, port);

    let (rtt, session) = match wireguard::verify_endpoint_keep_session(
        peer,
        *probe.private_key,
        *probe.peer_public_key,
        probe.client_id,
        probe.local_ipv4,
        &probe.aethernoize,
        timeout,
        None,
    )
    .await
    {
        Ok(v) => v,
        Err(e) => {
            log::trace!("wg probe {ip}:{port} -> {e}");
            return None;
        }
    };

    if !ironclad {
        return Some(WgProbeResult { ip, port, rtt });
    }

    let params = crate::tunnelping::WgPingParams {
        local_ipv4: probe.local_ipv4,
        local_ipv6: "::1".parse().unwrap(),
        aethernoize: probe.aethernoize.clone(),
    };
    match crate::tunnelping::wg_http_ping_established(session, &params, WG_IRONCLAD_TCPING_TIMEOUT)
        .await
    {
        Ok(http_rtt) => {
            log::info!(
                "[+] ironclad verified wg {ip}:{port} real http round trip rtt={:?}",
                http_rtt
            );
            Some(WgProbeResult {
                ip,
                port,
                rtt: http_rtt,
            })
        }
        Err(e) => {
            log::trace!("[-] ironclad wg {ip}:{port} failed real http check: {e}");
            None
        }
    }
}

fn build_wg_candidates(
    st: &WgStrategy,
    ports: &[u16],
    ip: IpScan,
    excluded: &HashSet<SocketAddr>,
) -> Vec<(IpAddr, u16)> {
    let ports: Vec<u16> = {
        let mut seen_port: HashSet<u16> = HashSet::new();
        let deduped: Vec<u16> = ports
            .iter()
            .copied()
            .filter(|p| seen_port.insert(*p))
            .collect();
        if deduped.is_empty() {
            vec![2408]
        } else {
            deduped
        }
    };

    // >>> AETHER-APP-PATCH manual-wg-range
    // Aether Mobile: when the user pins their own IPv4 range(s) (Settings ->
    // endpoint mode = manual range), scan ONLY those ranges: no built-in seeds
    // and no built-in WARP prefixes. Read from AETHER_WG_CIDRS, else from the
    // shared AETHER_SCAN_CIDRS. Deliberately additive (one early return plus
    // self-contained helpers, all inside AETHER-APP-PATCH markers) so an
    // upstream refactor of the default path below can never conflict with it.
    if ip.want_v4() {
        if let Some(cidrs) = custom_wg_cidrs_v4() {
            if ip.want_v6() {
                log::warn!("[!] manual ranges are IPv4 only; ignoring IPv6 for this scan");
            }
            log::info!("[i] manual wg range mode: {}", cidrs.join(", "));
            return manual_wg_candidates(st, &ports, &cidrs, excluded);
        }
    }
    // <<< AETHER-APP-PATCH manual-wg-range

    let mut anchors: Vec<IpAddr> = Vec::new();
    let mut pool: Vec<IpAddr> = Vec::new();

    if ip.want_v4() {
        for s in wireguard::wg_seeds_v4() {
            if let Ok(a) = s.parse::<Ipv4Addr>() {
                anchors.push(IpAddr::V4(a));
            }
        }
        let cidr_hosts: Vec<Vec<Ipv4Addr>> = wireguard::wg_prefixes_v4()
            .iter()
            .map(|c| {
                if st.full_subnet {
                    enumerate_cidr_v4(c)
                } else {
                    sample_cidr_v4(c, st.sample_per_cidr)
                }
            })
            .collect();
        let max_len = cidr_hosts.iter().map(|v| v.len()).max().unwrap_or(0);
        for i in 0..max_len {
            for hosts in &cidr_hosts {
                if let Some(a) = hosts.get(i) {
                    pool.push(IpAddr::V4(*a));
                }
            }
        }
    }

    if ip.want_v6() {
        for s in wireguard::WG_SEEDS_V6 {
            if let Ok(a) = s.parse::<Ipv6Addr>() {
                anchors.push(IpAddr::V6(a));
            }
        }
        let per = if st.sample_per_cidr == 0 {
            80
        } else {
            st.sample_per_cidr
        };
        let cidr6: Vec<Vec<Ipv6Addr>> = wireguard::wg_prefixes_v6()
            .iter()
            .map(|c| sample_cidr_v6(c, per, wireguard::WG_PREFIXES_V4))
            .collect();
        let max6 = cidr6.iter().map(|v| v.len()).max().unwrap_or(0);
        for i in 0..max6 {
            for hosts in &cidr6 {
                if let Some(a) = hosts.get(i) {
                    pool.push(IpAddr::V6(*a));
                }
            }
        }
    }

    let mut out: Vec<(IpAddr, u16)> = Vec::new();
    let mut seen: HashSet<(IpAddr, u16)> = HashSet::new();
    let port_count = ports.len();

    let mut push = |ip: IpAddr, port: u16| {
        if !excluded.contains(&SocketAddr::new(ip, port)) && seen.insert((ip, port)) {
            out.push((ip, port));
        }
    };

    let mut seen_ip: HashSet<IpAddr> = HashSet::new();
    let ips: Vec<IpAddr> = anchors
        .iter()
        .chain(pool.iter())
        .copied()
        .filter(|ip| seen_ip.insert(*ip))
        .collect();

    for wave in 0..st.pool_port_waves.max(1) {
        for (idx, candidate_ip) in ips.iter().enumerate() {
            push(*candidate_ip, ports[(idx + wave) % port_count]);
        }
    }

    out
}

fn parse_cidr_v4(cidr: &str) -> Option<(u32, u8)> {
    let (ip, prefix) = cidr.split_once('/')?;
    Some((
        u32::from(ip.parse::<Ipv4Addr>().ok()?),
        prefix.parse().ok()?,
    ))
}

fn enumerate_cidr_v4(cidr: &str) -> Vec<Ipv4Addr> {
    let (base, prefix) = match parse_cidr_v4(cidr) {
        Some(v) => v,
        None => return Vec::new(),
    };
    let host_bits = 32u32.saturating_sub(prefix as u32);
    if host_bits == 0 {
        return vec![Ipv4Addr::from(base)];
    }
    if host_bits > 12 {
        return Vec::new();
    }
    let size = 1u32 << host_bits;
    (1..size.saturating_sub(1))
        .map(|off| Ipv4Addr::from(base + off))
        .collect()
}

fn sample_cidr_v4(cidr: &str, n: usize) -> Vec<Ipv4Addr> {
    let (base, prefix) = match parse_cidr_v4(cidr) {
        Some(v) => v,
        None => return Vec::new(),
    };
    let host_bits = 32u32.saturating_sub(prefix as u32);
    let size = if host_bits >= 32 {
        u32::MAX
    } else {
        1u32 << host_bits
    };
    if size <= 2 {
        return vec![Ipv4Addr::from(base)];
    }

    let usable = size - 2;
    let want = (n as u32).min(usable);
    let mut rng = rand::rng();
    let mut chosen: HashSet<u32> = HashSet::with_capacity(want as usize);
    let mut out = Vec::with_capacity(want as usize);

    while (out.len() as u32) < want {
        let off = 1 + rng.random_range(0..usable);
        if chosen.insert(off) {
            out.push(Ipv4Addr::from(base + off));
        }
    }

    out
}

fn parse_cidr_v6(cidr: &str) -> Option<(u128, u8)> {
    let (ip, prefix) = cidr.split_once('/')?;
    Some((
        u128::from(ip.parse::<Ipv6Addr>().ok()?),
        prefix.parse().ok()?,
    ))
}

fn sample_cidr_v6(cidr: &str, n: usize, v4_cidrs: &[&str]) -> Vec<Ipv6Addr> {
    let (base, prefix) = match parse_cidr_v6(cidr) {
        Some(v) => v,
        None => return Vec::new(),
    };
    if 128u32.saturating_sub(prefix as u32) == 0 {
        return vec![Ipv6Addr::from(base)];
    }

    let v4: Vec<(u32, u8)> = v4_cidrs.iter().filter_map(|c| parse_cidr_v4(c)).collect();
    let mut rng = rand::rng();
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let embedded = if v4.is_empty() {
            rng.random::<u32>() as u128
        } else {
            let (b, p) = v4[rng.random_range(0..v4.len())];
            let host_bits = 32u32.saturating_sub(p as u32);
            let host = if host_bits == 0 {
                0
            } else {
                rng.random::<u32>() & ((1u32 << host_bits) - 1)
            };
            (b | host) as u128
        };
        out.push(Ipv6Addr::from(base | embedded));
    }
    out
}

// >>> AETHER-APP-PATCH manual-wg-range
/// App-owned manual IPv4 range parser: `AETHER_WG_CIDRS`, else the shared
/// `AETHER_SCAN_CIDRS`. Upstream stopped exposing a helper for this in 1.6.0.
fn custom_wg_cidrs_v4() -> Option<Vec<String>> {
    let raw = std::env::var("AETHER_WG_CIDRS")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| {
            std::env::var("AETHER_SCAN_CIDRS")
                .ok()
                .filter(|s| !s.trim().is_empty())
        })?;
    let list: Vec<String> = raw
        .split([',', ';', ' ', '\n'])
        .filter_map(app_normalize_cidr_v4)
        .collect();
    if list.is_empty() {
        None
    } else {
        Some(list)
    }
}

/// Normalises one user-typed IPv4 range. Accepts `188.114.96.0/24`,
/// `188.114.96.x` / `188.114.96.*` (read as /24) and a bare `188.114.96.7`
/// (read as /32). Anything malformed is dropped.
fn app_normalize_cidr_v4(raw: &str) -> Option<String> {
    let v = raw.trim();
    if v.is_empty() {
        return None;
    }
    if let Some((ip, prefix)) = v.split_once('/') {
        let addr = ip.trim().parse::<Ipv4Addr>().ok()?;
        let bits: u8 = prefix.trim().parse().ok()?;
        if bits > 32 {
            return None;
        }
        return Some(format!("{addr}/{bits}"));
    }
    if v.ends_with(".x") || v.ends_with(".X") || v.ends_with(".*") {
        let addr = format!("{}0", &v[..v.len() - 1]).parse::<Ipv4Addr>().ok()?;
        return Some(format!("{addr}/24"));
    }
    let addr = v.parse::<Ipv4Addr>().ok()?;
    Some(format!("{addr}/32"))
}

/// Candidate builder for manual-range mode. Mirrors the default path: sample
/// (or fully enumerate) every range, interleave the ranges so no single one
/// eats the whole scan budget, then rotate the port waves exactly like
/// [`build_wg_candidates`] does.
fn manual_wg_candidates(
    st: &WgStrategy,
    ports: &[u16],
    cidrs: &[String],
    excluded: &HashSet<SocketAddr>,
) -> Vec<(IpAddr, u16)> {
    let cidr_hosts: Vec<Vec<Ipv4Addr>> = cidrs
        .iter()
        .map(|c| {
            if st.full_subnet {
                enumerate_cidr_v4(c)
            } else {
                sample_cidr_v4(c, st.sample_per_cidr)
            }
        })
        .collect();

    let mut ips: Vec<IpAddr> = Vec::new();
    let max_len = cidr_hosts.iter().map(|v| v.len()).max().unwrap_or(0);
    for i in 0..max_len {
        for hosts in &cidr_hosts {
            if let Some(a) = hosts.get(i) {
                ips.push(IpAddr::V4(*a));
            }
        }
    }

    let ports: Vec<u16> = if ports.is_empty() {
        vec![2408]
    } else {
        ports.to_vec()
    };
    let port_count = ports.len();

    let mut out: Vec<(IpAddr, u16)> = Vec::new();
    let mut seen: HashSet<(IpAddr, u16)> = HashSet::new();
    for wave in 0..st.pool_port_waves.max(1) {
        for (idx, candidate_ip) in ips.iter().enumerate() {
            let port = ports[(idx + wave) % port_count];
            if !excluded.contains(&SocketAddr::new(*candidate_ip, port))
                && seen.insert((*candidate_ip, port))
            {
                out.push((*candidate_ip, port));
            }
        }
    }
    out
}
// <<< AETHER-APP-PATCH manual-wg-range

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn anchors_come_first_but_each_on_its_own_port() {
        let strategy = WgScanMode::Turbo.strategy();
        let ports = [2408, 500, 1701, 4500, 854];
        let candidates = build_wg_candidates(&strategy, &ports, IpScan::V4, &HashSet::new());

        for (idx, seed) in wireguard::wg_seeds_v4().iter().enumerate() {
            let ip = IpAddr::V4(seed.parse().expect("wireguard seed"));
            assert_eq!(
                candidates[idx],
                (ip, ports[idx % ports.len()]),
                "anchor {ip} should be tried once, on a port of its own"
            );
        }
    }

    #[test]
    fn the_front_of_the_scan_never_hammers_one_port() {
        let strategy = WgScanMode::Turbo.strategy();
        let ports = [2408, 500, 1701, 4500, 854];
        let candidates = build_wg_candidates(&strategy, &ports, IpScan::V4, &HashSet::new());

        let head: HashSet<u16> = candidates[..ports.len()].iter().map(|(_, p)| *p).collect();
        assert_eq!(
            head.len(),
            ports.len(),
            "the first candidates must spread across ports, not stack on 2408"
        );

        let on_2408 = candidates
            .iter()
            .take(20)
            .filter(|(_, p)| *p == 2408)
            .count();
        assert!(
            on_2408 <= 4,
            "port 2408 took {on_2408} of the first twenty slots"
        );
    }

    #[test]
    fn turbo_tries_every_address_once_before_repeating_any() {
        let strategy = WgScanMode::Turbo.strategy();
        let ports = [2408, 500, 1701, 4500, 854];
        let candidates = build_wg_candidates(&strategy, &ports, IpScan::V4, &HashSet::new());

        let mut per_ip: std::collections::HashMap<IpAddr, usize> = std::collections::HashMap::new();
        for (ip, _) in &candidates {
            *per_ip.entry(*ip).or_default() += 1;
        }

        let repeated = per_ip.values().filter(|count| **count > 1).count();
        assert!(
            repeated <= wireguard::wg_seeds_v4().len(),
            "only an anchor that the pool also sampled may appear twice, saw {repeated}"
        );
        assert!(
            per_ip.values().all(|count| *count <= 2),
            "no address should be tried more than twice in turbo"
        );
    }

    #[test]
    fn sampled_ips_receive_multiple_rotated_port_attempts() {
        let mut strategy = WgScanMode::Balanced.strategy();
        strategy.sample_per_cidr = 1;
        let candidates = build_wg_candidates(
            &strategy,
            &[2408, 500, 1701, 4500],
            IpScan::V4,
            &HashSet::new(),
        );
        let anchors: HashSet<IpAddr> = wireguard::wg_seeds_v4()
            .into_iter()
            .map(|seed| IpAddr::V4(seed.parse().expect("wireguard seed")))
            .collect();
        let mut ports_per_ip: HashMap<IpAddr, HashSet<u16>> = HashMap::new();

        for (ip, port) in candidates {
            if !anchors.contains(&ip) {
                ports_per_ip.entry(ip).or_default().insert(port);
            }
        }

        assert!(
            ports_per_ip.values().any(|ports| ports.len() >= 3),
            "sampled IPs should be tried across multiple port waves"
        );
    }

    fn result(ip: &str, port: u16, rtt_ms: u64) -> WgProbeResult {
        WgProbeResult {
            ip: ip.parse().unwrap(),
            port,
            rtt: Duration::from_millis(rtt_ms),
        }
    }

    #[test]
    fn two_ports_on_one_edge_count_as_a_single_choice() {
        let found = vec![
            result("162.159.192.1", 2408, 40),
            result("162.159.192.1", 500, 30),
            result("162.159.192.1", 1701, 50),
        ];
        let picked = distinct_by_ip(&found);
        assert_eq!(picked.len(), 1, "one address must not fill both gool hops");
        assert_eq!(picked[0].port, 500, "the quickest port on it is kept");
    }

    #[test]
    fn separate_edges_are_offered_quickest_first() {
        let found = vec![
            result("162.159.193.7", 2408, 90),
            result("162.159.192.1", 2408, 20),
            result("162.159.192.1", 500, 25),
            result("162.159.195.4", 4500, 55),
        ];
        let picked = distinct_by_ip(&found);
        assert_eq!(picked.len(), 3);
        assert_eq!(picked[0].ip.to_string(), "162.159.192.1");
        assert_eq!(picked[1].ip.to_string(), "162.159.195.4");
        assert_eq!(picked[2].ip.to_string(), "162.159.193.7");
        assert_ne!(
            picked[0].ip, picked[1].ip,
            "gool must get two different addresses"
        );
    }

    #[test]
    fn nothing_verified_means_nothing_offered() {
        assert!(distinct_by_ip(&[]).is_empty());
    }

    #[test]
    fn cooled_down_endpoint_is_excluded_from_the_scan() {
        let strategy = WgScanMode::Turbo.strategy();
        let peer: SocketAddr = "162.159.192.1:2408".parse().unwrap();
        let excluded = HashSet::from([peer]);
        let candidates =
            build_wg_candidates(&strategy, &[2408, 500, 1701, 4500], IpScan::V4, &excluded);

        assert!(!candidates.contains(&(peer.ip(), peer.port())));
    }
}
