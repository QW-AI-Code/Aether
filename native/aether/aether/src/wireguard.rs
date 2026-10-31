use parking_lot::Mutex as StdMutex;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, Mutex};

use crate::aethernoize::{self, AetherNoizeConfig};
use crate::error::{AetherError, Result};
use rand::RngExt;

const TIMER_TICK: Duration = Duration::from_millis(250);
const MAX_PACKET: usize = 65536;

/// How many outbound IP packets are encapsulated under ONE acquisition of the
/// boringtun session lock. See the send task in [WgTunnel::run] for why this
/// exists at all.
const MAX_ENCAP_BATCH: usize = 64;

// >>> AETHER-APP-PATCH wg-uplink-backpressure
/// How often the writer reports what the uplink is actually doing.
///
/// 1.2.8-r6. Every counter this project added in r2..r5 measured a queue inside
/// this process, and every one of them read zero through a session whose path
/// had seconds of standing queue - because the queue was in the kernel's
/// `SO_SNDBUF`, which nothing measured. This is that measurement. Matched to the
/// netstack telemetry interval so the two lines interleave and can be read
/// together.
const UPLINK_REPORT_INTERVAL: Duration = Duration::from_secs(15);
// <<< AETHER-APP-PATCH wg-uplink-backpressure
const VERIFY_RETRY_DELAYS: [Duration; 2] =
    [Duration::from_millis(750), Duration::from_millis(2_000)];

const WG_MSG_TYPE_MIN: u8 = 1;
const WG_MSG_TYPE_MAX: u8 = 4;

const MAX_TRANSIENT_RECV_ERRORS: u32 = 64;
const TRANSIENT_RECV_BACKOFF: Duration = Duration::from_millis(50);

/// 1.2.8 MEDIA-STALL FIX - how long the socket reader will wait for room in the
/// netstack's inbound queue before it drops a datagram.
///
/// ROOT CAUSE this bounds: the reader used to `inbound_tx.send(..).await`, an
/// unbounded wait. A video player fills that queue in milliseconds, so the only
/// task draining the WireGuard UDP socket parked - and while it was parked the
/// kernel receive buffer overflowed and discarded whatever arrived next.
/// Handshake responses and keepalives are part of "whatever": losing them is
/// how a healthy tunnel talks itself into an expired session. A datagram is
/// worth waiting a few milliseconds for; it is never worth going deaf for.
const INBOUND_HANDOFF_BUDGET: Duration = Duration::from_millis(20);

/// Consecutive `encapsulate` refusals that mean "this session is unusable, ask
/// for a new one" rather than "that packet was unlucky".
const ENCAP_ERRORS_BEFORE_REKEY: u32 = 24;

/// Shortest gap between two in-place re-handshakes of the same tunnel.
const REKEY_MIN_GAP: Duration = Duration::from_secs(3);

/// Consecutive stale observations before the tunnel is declared dead. One is
/// not enough: a congested mobile path can legitimately swallow every probe in
/// a single window, and tearing a gool session down costs the user the SOCKS5
/// listener for seconds. See [wg_stale_timeout].
const STALE_STRIKES_BEFORE_DEATH: u32 = 2;

pub fn is_transient_socket_error(error: &std::io::Error) -> bool {
    use std::io::ErrorKind;

    matches!(
        error.kind(),
        ErrorKind::ConnectionRefused
            | ErrorKind::ConnectionReset
            | ErrorKind::ConnectionAborted
            | ErrorKind::HostUnreachable
            | ErrorKind::NetworkUnreachable
            | ErrorKind::Interrupted
            | ErrorKind::WouldBlock
            | ErrorKind::TimedOut
    )
}

struct TaskGuard(Vec<tokio::task::AbortHandle>);

impl Drop for TaskGuard {
    fn drop(&mut self) {
        for handle in self.0.drain(..) {
            handle.abort();
        }
    }
}

/// Hands a decapsulated IP packet to the netstack without ever blocking the
/// socket reader indefinitely (see [INBOUND_HANDOFF_BUDGET]).
///
/// Returns `false` only when the netstack is gone for good, which is the one
/// case where the reader should stop.
async fn deliver_inbound(tx: &mpsc::Sender<Vec<u8>>, pkt: Vec<u8>) -> bool {
    match tx.try_send(pkt) {
        Ok(()) => true,
        Err(mpsc::error::TrySendError::Full(pkt)) => {
            match tokio::time::timeout(INBOUND_HANDOFF_BUDGET, tx.send(pkt)).await {
                Ok(Ok(())) => true,
                // The netstack closed: nothing left to read for.
                Ok(Err(_)) => false,
                // Congested. Dropping here is deliberate and is exactly the
                // loss signal TCP and QUIC congestion control are built to
                // read; going deaf on the socket is not.
                Err(_) => true,
            }
        }
        Err(mpsc::error::TrySendError::Closed(_)) => false,
    }
}

fn inject_client_id(pkt: &mut [u8], client_id: &[u8; 3]) {
    if pkt.len() < 4 {
        return;
    }
    if pkt[0] < WG_MSG_TYPE_MIN || pkt[0] > WG_MSG_TYPE_MAX {
        return;
    }
    pkt[1..4].copy_from_slice(client_id);
}

fn strip_client_id(pkt: &mut [u8]) {
    if pkt.len() < 4 {
        return;
    }
    if pkt[0] < WG_MSG_TYPE_MIN || pkt[0] > WG_MSG_TYPE_MAX {
        return;
    }
    pkt[1..4].copy_from_slice(&[0u8; 3]);
}

#[derive(Clone)]
pub struct WgConfig {
    pub local_private_key: [u8; 32],
    pub peer_public_key: [u8; 32],
    pub peer_endpoint: SocketAddr,
    pub local_ipv4: Ipv4Addr,
    pub local_ipv6: Ipv6Addr,
    pub client_id: [u8; 3],
    pub preshared_key: Option<[u8; 32]>,
    pub persistent_keepalive: Option<u16>,
    pub aethernoize: Arc<AetherNoizeConfig>,
}

pub struct WgTunnel {
    tunn: Arc<Mutex<Box<Tunn>>>,
    sock: Arc<UdpSocket>,
    detour: crate::upstream::DetourGuard,
    peer: SocketAddr,
    inbound_tx: mpsc::Sender<Vec<u8>>,
    pub obf_sent: Arc<Mutex<bool>>,
    pub aethernoize: Arc<AetherNoizeConfig>,
    pub client_id: [u8; 3],
    pub local_ipv4: Ipv4Addr,
    keys: SessionKeys,
}

/// Everything needed to mint a REPLACEMENT noise session for a peer we are
/// already talking to.
///
/// 1.2.8: this is the whole point of the media-stall fix. When a session dies
/// (boringtun gives up re-handshaking under packet loss) the socket, the peer,
/// the netstack and the SOCKS5 listener are all still perfectly good - only the
/// crypto session is gone. Carrying the keys here lets us swap in a fresh
/// session on the SAME socket in well under a second, instead of tearing the
/// whole tunnel (both hops, in gool mode) down and rebuilding it.
#[derive(Clone)]
pub struct SessionKeys {
    private_key: [u8; 32],
    peer_public: [u8; 32],
    preshared: Option<[u8; 32]>,
    keepalive: u16,
}

impl SessionKeys {
    fn fresh_session(&self) -> Box<Tunn> {
        Box::new(Tunn::new(
            StaticSecret::from(self.private_key),
            PublicKey::from(self.peer_public),
            self.preshared,
            Some(self.keepalive),
            0,
            None,
        ))
    }
}

pub struct EstablishedSession {
    tunn: Arc<Mutex<Box<Tunn>>>,
    sock: Arc<UdpSocket>,
    detour: crate::upstream::DetourGuard,
    peer: SocketAddr,
    client_id: [u8; 3],
    keys: SessionKeys,
}

impl WgTunnel {
    pub async fn new(cfg: WgConfig, inbound_tx: mpsc::Sender<Vec<u8>>) -> Result<Self> {
        let (sock, _, detour) = crate::upstream::bind_via_upstream(cfg.peer_endpoint).await?;

        let local_secret = StaticSecret::from(cfg.local_private_key);
        let peer_public = PublicKey::from(cfg.peer_public_key);
        let preshared = cfg.preshared_key;

        let tunn = Tunn::new(
            local_secret,
            peer_public,
            preshared,
            cfg.persistent_keepalive,
            0,
            None,
        );

        Ok(Self {
            tunn: Arc::new(Mutex::new(Box::new(tunn))),
            sock: Arc::new(sock),
            detour,
            peer: cfg.peer_endpoint,
            inbound_tx,
            obf_sent: Arc::new(Mutex::new(false)),
            aethernoize: cfg.aethernoize.clone(),
            client_id: cfg.client_id,
            local_ipv4: cfg.local_ipv4,
            keys: SessionKeys {
                private_key: cfg.local_private_key,
                peer_public: cfg.peer_public_key,
                preshared: cfg.preshared_key,
                keepalive: cfg.persistent_keepalive.unwrap_or(25),
            },
        })
    }

    pub fn from_established(
        session: EstablishedSession,
        aethernoize: Arc<AetherNoizeConfig>,
        inbound_tx: mpsc::Sender<Vec<u8>>,
        local_ipv4: Ipv4Addr,
    ) -> Self {
        Self {
            tunn: session.tunn,
            sock: session.sock,
            detour: session.detour,
            peer: session.peer,
            inbound_tx,
            obf_sent: Arc::new(Mutex::new(true)),
            aethernoize,
            client_id: session.client_id,
            local_ipv4,
            keys: session.keys,
        }
    }

    pub async fn run(self, mut outbound_rx: mpsc::Receiver<Vec<u8>>) -> Result<()> {
        let sock_r = self.sock.clone();
        let sock_w = self.sock.clone();
        let sock_t = self.sock.clone();
        let sock_h = self.sock.clone();
        let tunn_r = self.tunn.clone();
        let tunn_w = self.tunn.clone();
        let tunn_t = self.tunn.clone();
        let tunn_h = self.tunn.clone();
        let inbound_tx = self.inbound_tx.clone();
        let obf_sent = self.obf_sent.clone();
        let aethernoize = self.aethernoize.clone();
        let aethernoize_t = self.aethernoize.clone();
        let client_id = self.client_id;
        let client_id_h = self.client_id;
        let peer = self.peer;
        let local_ipv4 = self.local_ipv4;
        let keys_t = self.keys.clone();

        let last_valid_rx: Arc<StdMutex<Instant>> = Arc::new(StdMutex::new(Instant::now()));
        let last_valid_rx_r = last_valid_rx.clone();
        let last_valid_rx_h = last_valid_rx.clone();

        // 1.2.8: the three flags that let the tasks cooperate instead of each
        // failing silently on its own.
        //   rekey_now   - "this session looks stuck, re-handshake it in place"
        //   session_dead - "it is past saving, hand me a new tunnel"
        let rekey_now = Arc::new(AtomicBool::new(false));
        let rekey_now_w = rekey_now.clone();
        let rekey_now_t = rekey_now.clone();
        let rekey_now_h = rekey_now.clone();
        let session_dead = Arc::new(AtomicBool::new(false));
        let session_dead_t = session_dead.clone();
        let session_dead_h = session_dead.clone();

        let recv_task = tokio::spawn(async move {
            let mut buf = vec![0u8; MAX_PACKET];
            let mut tmp = vec![0u8; MAX_PACKET];
            let mut transient_errors = 0u32;
            // Reused across iterations so a busy tunnel does not allocate two
            // vectors per datagram.
            let mut to_network: Vec<Vec<u8>> = Vec::new();
            let mut to_tunnel: Vec<Vec<u8>> = Vec::new();
            loop {
                match sock_r.recv(&mut buf).await {
                    Ok(0) => {}
                    Ok(n) => {
                        transient_errors = 0;
                        strip_client_id(&mut buf[..n]);
                        to_network.clear();
                        to_tunnel.clear();
                        let mut progressed = false;

                        {
                            let mut tunn = tunn_r.lock().await;
                            // 1.2.8: boringtun QUEUES packets internally (the
                            // ones that arrived while a handshake was in
                            // flight, and the handshake replies it owes the
                            // peer). One decapsulate call returns ONE of them.
                            // The old code took the first and threw the rest
                            // away, which is why a rekey under load could
                            // silently half-complete. Drain until it says Done.
                            let mut first = true;
                            loop {
                                let outcome = if first {
                                    tunn.decapsulate(None, &buf[..n], &mut tmp)
                                } else {
                                    tunn.decapsulate(None, &[], &mut tmp)
                                };
                                first = false;

                                match outcome {
                                    TunnResult::Done => {
                                        progressed = true;
                                        break;
                                    }
                                    TunnResult::Err(e) => {
                                        log::trace!("decapsulate error: {e:?}");
                                        break;
                                    }
                                    TunnResult::WriteToNetwork(pkt) => {
                                        progressed = true;
                                        let mut pkt_vec = pkt.to_vec();
                                        inject_client_id(&mut pkt_vec, &client_id);
                                        to_network.push(pkt_vec);
                                    }
                                    TunnResult::WriteToTunnelV4(pkt, _)
                                    | TunnResult::WriteToTunnelV6(pkt, _) => {
                                        progressed = true;
                                        to_tunnel.push(pkt.to_vec());
                                        break;
                                    }
                                }
                            }
                        }

                        if progressed {
                            *last_valid_rx_r.lock() = Instant::now();
                        }

                        for pkt in to_network.drain(..) {
                            let _ = sock_r.send(&pkt).await;
                        }
                        let mut netstack_gone = false;
                        for pkt in to_tunnel.drain(..) {
                            if !deliver_inbound(&inbound_tx, pkt).await {
                                netstack_gone = true;
                                break;
                            }
                        }
                        if netstack_gone {
                            log::warn!("[wg] the netstack is gone; stopping the socket reader");
                            break;
                        }
                    }
                    Err(e) => {
                        if is_transient_socket_error(&e) {
                            transient_errors += 1;
                            if transient_errors > MAX_TRANSIENT_RECV_ERRORS {
                                log::error!(
                                    "recv error: {e}; giving up after {transient_errors} consecutive transient failures"
                                );
                                break;
                            }
                            log::debug!(
                                "transient recv error: {e}; keeping the tunnel and retrying"
                            );
                            tokio::time::sleep(TRANSIENT_RECV_BACKOFF).await;
                            continue;
                        }
                        log::error!("recv error: {e}");
                        break;
                    }
                }
            }
        });

        // ------------------------------------------------------------------
        // 1.2.8-r3 LIVE-STREAM FIX  (the real "ping 2000 the moment dubbing
        // starts" root cause on the engine side).
        //
        // ## What was wrong
        //
        // The loop was, per packet:
        //
        //     outbound_rx.recv().await   ->   tunn_w.lock().await   ->   send
        //
        // `tunn` is the ONE boringtun session, and the socket reader
        // (`recv_task`) takes the very same lock for every datagram it
        // decapsulates. So one lock acquisition per packet, in both directions,
        // against each other.
        //
        // On an ASYMMETRIC flow that costs almost nothing: a download keeps the
        // reader busy and the writer barely wants the lock. On a SYMMETRIC flow
        // the two tasks hand the lock back and forth on literally every packet,
        // and because it is a fair async mutex each hand-off is a full task
        // park + wake through the tokio scheduler. The uplink then cannot drain
        // faster than the scheduler round-trips, the queue in front of it grows,
        // and the measured RTT walks up into seconds while the tunnel is
        // perfectly healthy and still downloading.
        //
        // That is exactly, and only, the reported shape:
        //   * live dubbing is permanently symmetric (~44 KB/s up while ~64 KB/s
        //     comes down) - it stalls within a second of pressing start;
        //   * a download or a normal page of the same volume is not - it is fine;
        //   * press stop and the contention disappears, so the RTT walks back
        //     DOWN gradually instead of snapping back - nothing had died;
        //   * a different VPN app has one lock per packet too, but not a second
        //     task fighting it for the same session on every packet.
        //
        // The per-packet `obf_sent.lock().await` was a second async mutex on the
        // same hot path, taken forever to re-read a flag that can only ever
        // change once.
        //
        // ## The fix
        //
        // Drain the queue in bursts and encapsulate a whole burst under ONE
        // acquisition, so hand-offs scale with bursts (>= 64x fewer) instead of
        // with packets. The one-shot obfuscation flag is now checked without
        // touching a mutex after the first burst. No packet is delayed to build
        // a batch: the burst is whatever is ALREADY queued when the first packet
        // arrives.
        // ------------------------------------------------------------------
        let send_task = tokio::spawn(async move {
            let mut out_buf = vec![0u8; MAX_PACKET];
            let mut post_hs_junk_sent = false;
            let mut encap_errors = 0u32;
            let mut batch: Vec<Vec<u8>> = Vec::with_capacity(MAX_ENCAP_BATCH);
            let mut wire: Vec<Vec<u8>> = Vec::with_capacity(MAX_ENCAP_BATCH);
            let mut obfuscation_settled = false;
            // >>> AETHER-APP-PATCH wg-uplink-backpressure
            // Per-tunnel, not global: in gool mode there are two of these tasks
            // (outer and inner) and their uplinks are nested, so one shared
            // counter would be unreadable. The peer address in the log line is
            // what tells them apart.
            let mut up_pkts: u64 = 0;
            let mut up_bytes: u64 = 0;
            let mut up_waits: u64 = 0;
            let mut up_wait_micros: u64 = 0;
            let mut up_wait_worst_micros: u64 = 0;
            let mut last_uplink_report = Instant::now();
            let sndbuf_kb = crate::upstream::send_buffer_kb(&sock_w);
            // <<< AETHER-APP-PATCH wg-uplink-backpressure

            loop {
                let first = match outbound_rx.recv().await {
                    Some(pkt) => pkt,
                    None => break,
                };

                batch.clear();
                batch.push(first);
                // Whatever is already waiting, nothing more: this never adds
                // latency to build a bigger burst.
                while batch.len() < MAX_ENCAP_BATCH {
                    match outbound_rx.try_recv() {
                        Ok(pkt) => batch.push(pkt),
                        Err(_) => break,
                    }
                }

                wire.clear();
                {
                    let mut tunn = tunn_w.lock().await;
                    for ip_packet in batch.drain(..) {
                        match tunn.encapsulate(&ip_packet, &mut out_buf) {
                            TunnResult::Done => {
                                encap_errors = 0;
                            }
                            TunnResult::Err(e) => {
                                // 1.2.8: this arm used to be a trace line and
                                // nothing else. Once boringtun expires a session
                                // every single packet lands here, so the tunnel
                                // went silent while the process, the netstack and
                                // the SOCKS5 listener all stayed up - the "still
                                // connected, nothing loads" report. A run of
                                // refusals now asks the timer task for a fresh
                                // handshake instead of being swallowed.
                                encap_errors = encap_errors.saturating_add(1);
                                if encap_errors == 1 || encap_errors % 64 == 0 {
                                    log::warn!(
                                        "[wg] encapsulate refused a packet ({encap_errors} in a row): {e:?}"
                                    );
                                }
                                if encap_errors >= ENCAP_ERRORS_BEFORE_REKEY {
                                    rekey_now_w.store(true, Ordering::Relaxed);
                                }
                            }
                            TunnResult::WriteToNetwork(pkt) => {
                                encap_errors = 0;
                                let mut pkt_vec = pkt.to_vec();
                                inject_client_id(&mut pkt_vec, &client_id);
                                wire.push(pkt_vec);
                            }
                            TunnResult::WriteToTunnelV4(_, _)
                            | TunnResult::WriteToTunnelV6(_, _) => {}
                        }
                    }
                }

                if wire.is_empty() {
                    continue;
                }

                // Once per session, not once per packet.
                if !obfuscation_settled {
                    obfuscation_settled = true;
                    let mut sent = obf_sent.lock().await;
                    if !*sent && aethernoize.is_enabled() {
                        *sent = true;
                        drop(sent);
                        aethernoize::apply_obfuscation(&sock_w, peer, &aethernoize).await;
                    }
                }

                // >>> AETHER-APP-PATCH wg-uplink-backpressure
                //
                // ## 1.2.8-r6: this loop is where the backpressure chain was cut
                //
                // `sock_w.send(&pkt).await` is not wrong in itself - it is the
                // only place backpressure from the radio can enter this process,
                // because it is the only call that can wait. The bug was that it
                // could never wait: `tune_udp_buffers` gave this socket a 7 MB
                // `SO_SNDBUF`, and `send()` on a datagram socket returns
                // immediately unless that buffer is full.
                //
                // So the entire chain above was inert. The mpsc never filled, so
                // `flush_tx` never retained, so `StackDevice::tx` never reached
                // MAX_DEVICE_TX, so `transmit()` never refused, so CUBIC was
                // being told - still, in r5, after two rounds of fixing exactly
                // this - that the link had infinite capacity and no loss. All
                // the packets it "sent" were sitting in a kernel queue up to 7 MB
                // deep, which at the ~48 KB/s a live dubbing session uploads is
                // over two minutes of audio. The RTT that queue adds is the
                // "ping 3092 ms" on the badge, and the ACKs for the download
                // direction were stuck behind it, which is the download
                // collapsing to 1.2 KB/s while the upload kept running at 49.6.
                //
                // With `SO_SNDBUF` now a latency budget (see sysprofile), this
                // call really does wait, and the wait is what CUBIC needs. It is
                // also measured: a writer that waits is the fix working, and a
                // writer that never waits on a saturated uplink means the kernel
                // ignored the buffer request and r6 is not in effect.
                //
                // `try_send` + `writable()` rather than plain `send()` so the
                // wait can be attributed and timed instead of being invisible.
                for pkt in wire.drain(..) {
                    let began = Instant::now();
                    let mut waited = false;
                    loop {
                        match sock_w.try_send(&pkt) {
                            Ok(_) => break,
                            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                                // The uplink is full. Holding here is the whole
                                // point: it propagates up the mpsc, into the
                                // device queue, into `transmit()`, and finally
                                // into the congestion window.
                                waited = true;
                                if sock_w.writable().await.is_err() {
                                    break;
                                }
                            }
                            Err(_) => break,
                        }
                    }
                    up_pkts = up_pkts.saturating_add(1);
                    up_bytes = up_bytes.saturating_add(pkt.len() as u64);
                    if waited {
                        let micros = began.elapsed().as_micros() as u64;
                        up_waits = up_waits.saturating_add(1);
                        up_wait_micros = up_wait_micros.saturating_add(micros);
                        if micros > up_wait_worst_micros {
                            up_wait_worst_micros = micros;
                        }
                    }
                }

                if last_uplink_report.elapsed() >= UPLINK_REPORT_INTERVAL {
                    let window = last_uplink_report.elapsed();
                    last_uplink_report = Instant::now();
                    let kbps = if window.as_millis() > 0 {
                        (up_bytes * 1000) / (window.as_millis() as u64) / 1024
                    } else {
                        0
                    };
                    log::info!(
                        "[uplink {peer}] {:?} window: {} pkts, {} KB ({} KB/s) | kernel sndbuf \
                         {} KB | writer waited {} times, {} ms total, worst {} ms",
                        window,
                        up_pkts,
                        up_bytes / 1024,
                        kbps,
                        sndbuf_kb,
                        up_waits,
                        up_wait_micros / 1000,
                        up_wait_worst_micros / 1000,
                    );
                    up_pkts = 0;
                    up_bytes = 0;
                    up_waits = 0;
                    up_wait_micros = 0;
                    up_wait_worst_micros = 0;
                }
                // <<< AETHER-APP-PATCH wg-uplink-backpressure

                // Post-handshake junk once only - not on every data packet.
                if aethernoize.jc_after_hs > 0 && !post_hs_junk_sent {
                    post_hs_junk_sent = true;
                    aethernoize::send_post_handshake_junk(&sock_w, peer, &aethernoize).await;
                }
            }
        });

        let timer_task = tokio::spawn(async move {
            let mut interval = tokio::time::interval(TIMER_TICK);
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            let mut tmp = vec![0u8; MAX_PACKET];
            // A SECOND buffer: the forced-handshake call below happens while
            // the update_timers result is still borrowing `tmp`.
            let mut hs_buf = vec![0u8; MAX_PACKET];
            let mut outgoing: Vec<Vec<u8>> = Vec::new();
            let mut last_rekey = Instant::now()
                .checked_sub(REKEY_MIN_GAP)
                .unwrap_or_else(Instant::now);
            loop {
                interval.tick().await;

                let mut want_rekey = rekey_now_t.swap(false, Ordering::Relaxed);
                outgoing.clear();
                let mut unrecoverable = false;

                {
                    let mut tunn = tunn_t.lock().await;
                    match tunn.update_timers(&mut tmp) {
                        TunnResult::WriteToNetwork(pkt) => {
                            let mut pkt_vec = pkt.to_vec();
                            inject_client_id(&mut pkt_vec, &client_id);
                            outgoing.push(pkt_vec);
                        }
                        TunnResult::Err(e) => {
                            // 1.2.8 ROOT CAUSE: this result was DISCARDED. When
                            // congestion (a video starting is enough) makes the
                            // rekey handshakes time out, boringtun gives up and
                            // reports the session expired - exactly once, here.
                            // Dropping that report left a live tunnel object
                            // wired to a dead session: encapsulate refused
                            // every packet, nothing was sent, nothing came back,
                            // and only a manual disconnect/reconnect fixed it.
                            log::warn!("[wg] session timer reports {e:?}; re-keying in place");
                            want_rekey = true;
                        }
                        _ => {}
                    }

                    // Requested by the send task (a run of refused packets), by
                    // the health task (the data plane went quiet), or by the
                    // expiry above. Rate-limited so a genuinely unreachable
                    // peer cannot turn this into a handshake flood.
                    if want_rekey && last_rekey.elapsed() >= REKEY_MIN_GAP {
                        last_rekey = Instant::now();
                        *tunn = keys_t.fresh_session();
                        // Same idiom the initial connect uses: an empty payload
                        // makes boringtun emit the handshake initiation.
                        match tunn.encapsulate(&[], &mut hs_buf) {
                            TunnResult::WriteToNetwork(pkt) => {
                                log::info!(
                                    "[wg] re-handshaking the session with {peer} in place (socket, netstack and socks5 stay up)"
                                );
                                let mut pkt_vec = pkt.to_vec();
                                inject_client_id(&mut pkt_vec, &client_id);
                                outgoing.push(pkt_vec);
                            }
                            other => {
                                log::error!(
                                    "[wg] a fresh session refused to start a handshake ({other:?}); asking for a new tunnel"
                                );
                                unrecoverable = true;
                            }
                        }
                    }
                }

                if unrecoverable {
                    session_dead_t.store(true, Ordering::Relaxed);
                }

                if !outgoing.is_empty() && aethernoize_t.is_enabled() {
                    aethernoize::send_keepalive_junk(&sock_t, &aethernoize_t).await;
                }
                for pkt in outgoing.drain(..) {
                    let _ = sock_t.send(&pkt).await;
                }
            }
        });

        let stale_timeout = wg_stale_timeout();
        // Try to heal the session well before giving up on it.
        let rekey_after_idle = stale_timeout / 2;
        let health_task = tokio::spawn(async move {
            let mut interval = tokio::time::interval(WG_HEALTHCHECK_INTERVAL);
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            let probe = build_dataplane_probe(local_ipv4);
            let mut out_buf = vec![0u8; MAX_PACKET];
            let mut strikes = 0u32;
            loop {
                tokio::time::sleep(health_check_pause()).await;

                if session_dead_h.load(Ordering::Relaxed) {
                    log::warn!("[wg] the session for peer {peer} expired beyond repair");
                    return Err::<(), AetherError>(AetherError::Other(
                        "wireguard session expired and could not be re-handshaked".into(),
                    ));
                }

                let idle = last_valid_rx_h.lock().elapsed();

                if idle >= rekey_after_idle {
                    // Nothing valid has come back for a while. Before writing
                    // the session off (which in gool mode takes the SOCKS5
                    // listener down with it), spend one handshake on it.
                    rekey_now_h.store(true, Ordering::Relaxed);
                }

                if idle >= stale_timeout {
                    strikes = strikes.saturating_add(1);
                    log::warn!(
                        "[wg] no valid data from peer {} in {:?} (strike {}/{})",
                        peer, idle, strikes, STALE_STRIKES_BEFORE_DEATH
                    );
                    if strikes >= STALE_STRIKES_BEFORE_DEATH {
                        return Err::<(), AetherError>(AetherError::Other(
                            "wireguard tunnel stale: no valid data from peer".into(),
                        ));
                    }
                } else {
                    strikes = 0;
                }

                let probe = build_dataplane_probe(local_ipv4);
                let mut tunn = tunn_h.lock().await;
                if let Err(e) =
                    send_dataplane_probe(&sock_h, &mut tunn, &client_id_h, &probe, &mut out_buf)
                        .await
                {
                    log::trace!("[wg] health probe send failed: {e}");
                }
            }
        });

        let _guard = TaskGuard(vec![
            recv_task.abort_handle(),
            send_task.abort_handle(),
            timer_task.abort_handle(),
            health_task.abort_handle(),
        ]);

        let result = tokio::select! {
            _ = recv_task => {
                log::info!("wireguard recv task ended");
                Ok(())
            }
            _ = send_task => {
                log::info!("wireguard send task ended");
                Ok(())
            }
            _ = timer_task => {
                log::info!("wireguard timer task ended");
                Ok(())
            }
            r = health_task => {
                match r {
                    Ok(Err(e)) => Err(e),
                    Ok(Ok(())) => Ok(()),
                    Err(e) => Err(AetherError::Other(format!("health task panicked: {e}"))),
                }
            }
        };

        result
    }
}

const WG_HEALTHCHECK_INTERVAL: Duration = Duration::from_secs(3);
const WG_HEALTHCHECK_JITTER: Duration = Duration::from_millis(500);

fn health_check_pause() -> Duration {
    let jitter = WG_HEALTHCHECK_JITTER.as_millis() as u64;
    let offset = rand::rng().random_range(0..=jitter * 2);
    WG_HEALTHCHECK_INTERVAL - WG_HEALTHCHECK_JITTER + Duration::from_millis(offset)
}

/// How long the data plane may stay silent before the tunnel is written off.
///
/// 1.2.8: raised from 10 s. Ten seconds is shorter than a bad Iranian mobile
/// path stalls for on its own, so a video's first congestion spike used to
/// convince a perfectly good tunnel that its peer had vanished - and in gool
/// mode that verdict tears down BOTH hops, the netstack and the SOCKS5
/// listener, then charges the user a rescan for it. With the in-place rekey
/// (fired at half this value) a genuinely wedged session still recovers in a
/// few seconds, and a genuinely dead peer is still noticed inside ~25 s.
fn wg_stale_timeout() -> Duration {
    let secs = std::env::var("AETHER_WG_STALE_SECS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(20);
    Duration::from_secs(secs)
}

fn build_dns_query() -> Vec<u8> {
    let id: u16 = rand::random();
    let mut q = Vec::with_capacity(32);
    q.extend_from_slice(&id.to_be_bytes());
    q.extend_from_slice(&[0x01, 0x00]);
    q.extend_from_slice(&[0x00, 0x01]);
    q.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    for label in ["cloudflare", "com"] {
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0x00);
    q.extend_from_slice(&[0x00, 0x01]);
    q.extend_from_slice(&[0x00, 0x01]);
    q
}

fn ipv4_checksum(header: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut i = 0;
    while i + 1 < header.len() {
        sum += u16::from_be_bytes([header[i], header[i + 1]]) as u32;
        i += 2;
    }
    if i < header.len() {
        sum += (header[i] as u32) << 8;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

fn build_dataplane_probe(src: Ipv4Addr) -> Vec<u8> {
    let dns = build_dns_query();
    let udp_len = 8 + dns.len();
    let total_len = 20 + udp_len;
    let mut pkt = Vec::with_capacity(total_len);
    pkt.push(0x45);
    pkt.push(0x00);
    pkt.extend_from_slice(&(total_len as u16).to_be_bytes());
    let id: u16 = rand::random();
    pkt.extend_from_slice(&id.to_be_bytes());
    pkt.extend_from_slice(&[0x00, 0x00]);
    pkt.push(64);
    pkt.push(17);
    pkt.extend_from_slice(&[0x00, 0x00]);
    pkt.extend_from_slice(&src.octets());
    pkt.extend_from_slice(&Ipv4Addr::new(8, 8, 8, 8).octets());
    let csum = ipv4_checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&csum.to_be_bytes());
    let sport: u16 = rand::rng().random_range(20000..60000);
    pkt.extend_from_slice(&sport.to_be_bytes());
    pkt.extend_from_slice(&53u16.to_be_bytes());
    pkt.extend_from_slice(&(udp_len as u16).to_be_bytes());
    pkt.extend_from_slice(&[0x00, 0x00]);
    pkt.extend_from_slice(&dns);
    pkt
}

async fn send_dataplane_probe(
    sock: &UdpSocket,
    tunn: &mut Tunn,
    client_id: &[u8; 3],
    probe: &[u8],
    out_buf: &mut [u8],
) -> Result<()> {
    match tunn.encapsulate(probe, out_buf) {
        TunnResult::WriteToNetwork(pkt) => {
            let mut v = pkt.to_vec();
            inject_client_id(&mut v, client_id);
            sock.send(&v).await?;
        }
        TunnResult::Err(e) => {
            return Err(AetherError::Other(format!("dataplane encap: {e:?}")));
        }
        _ => {}
    }
    Ok(())
}

const DATAPLANE_REQUIRED_SUCCESSES: u32 = 2;
const DATAPLANE_PROBE_GAP: Duration = Duration::from_millis(600);

async fn verify_dataplane(
    sock: &UdpSocket,
    tunn: &mut Tunn,
    client_id: &[u8; 3],
    local_ipv4: Ipv4Addr,
    start: Instant,
    deadline: Instant,
) -> Result<Duration> {
    let probe = build_dataplane_probe(local_ipv4);
    let mut out_buf = vec![0u8; MAX_PACKET];
    let mut recv_buf = vec![0u8; MAX_PACKET];
    let mut tmp_buf = vec![0u8; MAX_PACKET];

    let mut successes: u32 = 0;
    let mut last_probe_at = Instant::now();
    send_dataplane_probe(sock, tunn, client_id, &probe, &mut out_buf).await?;
    let mut resend_at = last_probe_at + Duration::from_millis(700);

    loop {
        let now = Instant::now();
        if now >= deadline {
            log::debug!(
                "[wg] dataplane verify timed out ({}/{} confirmations)",
                successes,
                DATAPLANE_REQUIRED_SUCCESSES
            );
            return Err(AetherError::Other("dataplane timeout".into()));
        }
        if now >= resend_at {
            let _ = send_dataplane_probe(sock, tunn, client_id, &probe, &mut out_buf).await;
            last_probe_at = now;
            resend_at = now + Duration::from_millis(700);
        }
        let wait = deadline
            .saturating_duration_since(now)
            .min(resend_at.saturating_duration_since(now));

        tokio::select! {
            r = sock.recv(&mut recv_buf) => {
                let n = r?;
                strip_client_id(&mut recv_buf[..n]);
                match tunn.decapsulate(None, &recv_buf[..n], &mut tmp_buf) {
                    TunnResult::WriteToTunnelV4(_, _) | TunnResult::WriteToTunnelV6(_, _) => {
                        successes += 1;
                        log::debug!(
                            "[wg] dataplane round-trip {}/{} confirmed in {:?}",
                            successes, DATAPLANE_REQUIRED_SUCCESSES, start.elapsed()
                        );
                        if successes >= DATAPLANE_REQUIRED_SUCCESSES {
                            let elapsed = start.elapsed();
                            log::debug!("[wg] dataplane ok in {:?}", elapsed);
                            return Ok(elapsed);
                        }
                        let next_at = Instant::now().max(last_probe_at + DATAPLANE_PROBE_GAP);
                        let _ = send_dataplane_probe(sock, tunn, client_id, &probe, &mut out_buf).await;
                        last_probe_at = next_at;
                        resend_at = next_at + Duration::from_millis(700);
                    }
                    TunnResult::WriteToNetwork(pkt) => {
                        let mut v = pkt.to_vec();
                        inject_client_id(&mut v, client_id);
                        let _ = sock.send(&v).await;
                    }
                    _ => {}
                }
            }
            _ = tokio::time::sleep(wait) => {}
        }
    }
}

pub async fn verify_endpoint(
    peer: SocketAddr,
    private_key: [u8; 32],
    peer_public: [u8; 32],
    client_id: [u8; 3],
    local_ipv4: Ipv4Addr,
    aethernoize: &AetherNoizeConfig,
    timeout: Duration,
    keepalive: Option<u16>,
) -> Result<Duration> {
    let (elapsed, _session) = verify_endpoint_keep_session(
        peer,
        private_key,
        peer_public,
        client_id,
        local_ipv4,
        aethernoize,
        timeout,
        keepalive,
    )
    .await?;
    Ok(elapsed)
}

pub async fn verify_endpoint_keep_session(
    peer: SocketAddr,
    private_key: [u8; 32],
    peer_public: [u8; 32],
    client_id: [u8; 3],
    local_ipv4: Ipv4Addr,
    aethernoize: &AetherNoizeConfig,
    timeout: Duration,
    keepalive: Option<u16>,
) -> Result<(Duration, EstablishedSession)> {
    let data_check = std::env::var("AETHER_WG_NO_DATA_CHECK").is_err();
    log::trace!(
        "[wg] verify {} obf={} data_check={}",
        peer,
        aethernoize.is_enabled(),
        data_check
    );

    let (sock, _, detour) = crate::upstream::bind_via_upstream(peer).await?;

    let start = Instant::now();
    let deadline = start + timeout;

    if aethernoize.is_enabled() {
        aethernoize::apply_obfuscation(&sock, peer, aethernoize).await;
    }

    let local_secret = StaticSecret::from(private_key);
    let peer_pk = PublicKey::from(peer_public);

    let session_keys = SessionKeys {
        private_key,
        peer_public,
        preshared: None,
        keepalive: keepalive.unwrap_or(25),
    };

    let mut tunn = Tunn::new(
        local_secret,
        peer_pk,
        None,
        Some(keepalive.unwrap_or(25)),
        0,
        None,
    );

    let mut out_buf = vec![0u8; MAX_PACKET];
    let mut recv_buf = vec![0u8; MAX_PACKET];
    let mut tmp_buf = vec![0u8; MAX_PACKET];

    let init_packet = match tunn.encapsulate(&[], &mut out_buf) {
        TunnResult::WriteToNetwork(pkt) => {
            let mut pkt_vec = pkt.to_vec();
            inject_client_id(&mut pkt_vec, &client_id);
            pkt_vec
        }
        other => {
            log::warn!("[wg] unexpected encap result: {:?}", other);
            return Err(AetherError::Other("handshake init failed".into()));
        }
    };

    log::trace!("[wg] sending init {} bytes to {}", init_packet.len(), peer);
    sock.send(&init_packet).await?;

    let mut retry_index = 0usize;
    let mut timer = tokio::time::interval(TIMER_TICK);
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    timer.tick().await;

    let mut attempts = 0;
    loop {
        if Instant::now() >= deadline {
            log::trace!("[wg] timeout after {} recv attempts", attempts);
            return Err(AetherError::Other("verify timeout".into()));
        }

        let remaining = deadline.saturating_duration_since(Instant::now());

        tokio::select! {
            r = sock.recv(&mut recv_buf) => {
                attempts += 1;
                let n = r?;
                if n == 0 {
                    continue;
                }
                log::trace!("[wg] recv {} bytes (attempt {})", n, attempts);
                strip_client_id(&mut recv_buf[..n]);

                match tunn.decapsulate(None, &recv_buf[..n], &mut tmp_buf) {
                    TunnResult::Done => {
                        let elapsed = start.elapsed();
                        log::trace!("[wg] handshake done in {:?}", elapsed);
                        if data_check {
                            let dp_elapsed = verify_dataplane(&sock, &mut tunn, &client_id, local_ipv4, start, deadline).await?;
                            return Ok((dp_elapsed, EstablishedSession {
                                tunn: Arc::new(Mutex::new(Box::new(tunn))),
                                sock: Arc::new(sock),
                                detour,
                                peer,
                                client_id,
                                keys: session_keys.clone(),
                            }));
                        }
                        return Ok((elapsed, EstablishedSession {
                            tunn: Arc::new(Mutex::new(Box::new(tunn))),
                            sock: Arc::new(sock),
                            detour,
                            peer,
                            client_id,
                            keys: session_keys.clone(),
                        }));
                    }
                    TunnResult::WriteToNetwork(pkt) => {
                        let mut pkt_vec = pkt.to_vec();
                        inject_client_id(&mut pkt_vec, &client_id);
                        log::trace!("[wg] sending response {} bytes", pkt_vec.len());
                        sock.send(&pkt_vec).await?;
                        let elapsed = start.elapsed();
                        log::trace!("[wg] handshake success in {:?}", elapsed);
                        if data_check {
                            let dp_elapsed = verify_dataplane(&sock, &mut tunn, &client_id, local_ipv4, start, deadline).await?;
                            return Ok((dp_elapsed, EstablishedSession {
                                tunn: Arc::new(Mutex::new(Box::new(tunn))),
                                sock: Arc::new(sock),
                                detour,
                                peer,
                                client_id,
                                keys: session_keys.clone(),
                            }));
                        }
                        return Ok((elapsed, EstablishedSession {
                            tunn: Arc::new(Mutex::new(Box::new(tunn))),
                            sock: Arc::new(sock),
                            detour,
                            peer,
                            client_id,
                            keys: session_keys.clone(),
                        }));
                    }
                    TunnResult::Err(e) => {
                        log::trace!("[wg] decap error: {:?}", e);
                    }
                    other => {
                        log::trace!("[wg] unexpected decap: {:?}", other);
                    }
                }
            }
            _ = timer.tick() => {
                if let Some(delay) = VERIFY_RETRY_DELAYS.get(retry_index) {
                    if start.elapsed() >= *delay {
                        retry_index += 1;
                        log::trace!(
                            "[wg] retransmitting init to {} after {:?} ({}/{})",
                            peer,
                            delay,
                            retry_index,
                            VERIFY_RETRY_DELAYS.len()
                        );
                        sock.send(&init_packet).await?;
                    }
                }

                match tunn.update_timers(&mut out_buf) {
                    TunnResult::WriteToNetwork(pkt) => {
                        let mut pkt_vec = pkt.to_vec();
                        inject_client_id(&mut pkt_vec, &client_id);
                        log::trace!("[wg] timer generated {} byte handshake packet", pkt_vec.len());
                        sock.send(&pkt_vec).await?;
                    }
                    TunnResult::Err(e) => {
                        return Err(AetherError::Other(format!("wireguard timer failed: {e:?}")));
                    }
                    _ => {}
                }
            }
            _ = tokio::time::sleep(remaining) => {
                log::trace!("[wg] sleep timeout");
                return Err(AetherError::Other("verify timeout".into()));
            }
        }
    }
}

pub const WG_PREFIXES_V4: &[&str] = &[
    "162.159.192.0/24",
    "162.159.195.0/24",
    "188.114.96.0/24",
    "188.114.97.0/24",
    "188.114.98.0/24",
    "188.114.99.0/24",
    "162.159.193.0/24",
];

pub const WG_PREFIXES_V6: &[&str] = &[
    "2606:4700:d0::/64",
    "2606:4700:d1::/64",
    "2606:4700:100::/48",
];

pub const WG_ZT_PREFIXES_V4: &[&str] = &["162.159.193.0/24"];

pub const WG_ZT_PREFIXES_V6: &[&str] = &["2606:4700:100::/48"];

pub const WG_PORTS: &[u16] = &[
    2408, 500, 1701, 4500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939, 942,
    943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387, 1843, 2371,
    2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 5279, 5956, 7103, 7152, 7156, 7281, 7559, 8319,
    8742, 8854, 8886,
];

pub const WG_SEEDS_V4: &[&str] = &[
    "162.159.192.1",
    "162.159.195.1",
    "188.114.96.1",
    "188.114.97.1",
    "162.159.193.1",
];

pub const WG_SEEDS_V6: &[&str] = &[
    "2606:4700:d0::a29f:c001",
    "2606:4700:d1::a29f:c001",
    "2606:4700:d0::a29f:c301",
    "2606:4700:d0::bc72:6001",
];

pub fn wg_prefixes_v4() -> Vec<&'static str> {
    crate::prober::prioritize(WG_PREFIXES_V4, WG_ZT_PREFIXES_V4)
}

pub fn wg_prefixes_v6() -> Vec<&'static str> {
    crate::prober::prioritize(WG_PREFIXES_V6, WG_ZT_PREFIXES_V6)
}

pub fn wg_seeds_v4() -> Vec<&'static str> {
    crate::prober::prioritize(WG_SEEDS_V4, &["162.159.193.1"])
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Error, ErrorKind};

    #[test]
    fn the_documented_zero_trust_wireguard_ingress_range_is_scanned() {
        assert!(WG_PREFIXES_V4.contains(&"162.159.193.0/24"));
        assert!(WG_PREFIXES_V6.contains(&"2606:4700:100::/48"));
    }

    #[test]
    fn the_documented_wireguard_ports_are_all_covered() {
        for port in [2408u16, 500, 1701, 4500] {
            assert!(WG_PORTS.contains(&port), "port {port} should be scanned");
        }
    }

    #[test]
    fn the_documented_default_wireguard_port_leads_the_sweep() {
        assert_eq!(
            WG_PORTS.first(),
            Some(&2408),
            "the primary sweep port is taken from the head of this list"
        );
    }

    #[test]
    fn the_documented_wireguard_fallback_ports_follow_the_default() {
        assert_eq!(&WG_PORTS[..4], &[2408, 500, 1701, 4500]);
    }

    #[test]
    fn the_consumer_range_leads_when_no_team_is_configured() {
        std::env::remove_var("AETHER_TEAM");
        assert_eq!(wg_prefixes_v4().first(), Some(&"162.159.192.0/24"));
        assert_eq!(wg_prefixes_v6().first(), Some(&"2606:4700:d0::/64"));
    }

    #[test]
    fn no_prefix_is_lost_when_the_zero_trust_range_is_promoted() {
        let promoted = crate::prober::prioritize(WG_PREFIXES_V4, WG_ZT_PREFIXES_V4);
        assert_eq!(promoted.len(), WG_PREFIXES_V4.len());
        for entry in WG_PREFIXES_V4 {
            assert!(promoted.contains(entry), "{entry} went missing");
        }
    }

    #[test]
    fn every_wireguard_prefix_parses() {
        for entry in WG_PREFIXES_V4 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<std::net::Ipv4Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
        for entry in WG_PREFIXES_V6 {
            let (addr, bits) = entry.split_once('/').expect("cidr");
            assert!(addr.parse::<std::net::Ipv6Addr>().is_ok(), "{entry}");
            assert!(bits.parse::<u8>().is_ok(), "{entry}");
        }
    }

    #[test]
    fn an_icmp_port_unreachable_is_treated_as_transient() {
        assert!(is_transient_socket_error(&Error::from(
            ErrorKind::ConnectionRefused
        )));
    }

    #[test]
    fn the_usual_transient_udp_errors_do_not_end_the_tunnel() {
        for kind in [
            ErrorKind::ConnectionReset,
            ErrorKind::ConnectionAborted,
            ErrorKind::HostUnreachable,
            ErrorKind::NetworkUnreachable,
            ErrorKind::Interrupted,
            ErrorKind::WouldBlock,
            ErrorKind::TimedOut,
        ] {
            assert!(
                is_transient_socket_error(&Error::from(kind)),
                "{kind:?} should be transient"
            );
        }
    }

    #[test]
    fn health_probes_are_jittered_around_the_interval() {
        for _ in 0..200 {
            let pause = health_check_pause();
            assert!(pause >= WG_HEALTHCHECK_INTERVAL - WG_HEALTHCHECK_JITTER);
            assert!(pause <= WG_HEALTHCHECK_INTERVAL + WG_HEALTHCHECK_JITTER);
        }
    }

    #[test]
    fn every_health_probe_is_a_fresh_packet() {
        let local = Ipv4Addr::new(172, 16, 0, 2);
        let distinct: std::collections::HashSet<Vec<u8>> =
            (0..16).map(|_| build_dataplane_probe(local)).collect();
        assert!(
            distinct.len() > 1,
            "the probe must not repeat byte for byte"
        );
    }

    #[test]
    fn a_broken_socket_is_still_fatal() {
        for kind in [
            ErrorKind::NotConnected,
            ErrorKind::AddrNotAvailable,
            ErrorKind::PermissionDenied,
            ErrorKind::InvalidInput,
        ] {
            assert!(
                !is_transient_socket_error(&Error::from(kind)),
                "{kind:?} should be fatal"
            );
        }
    }

    #[tokio::test]
    async fn endpoint_verification_retransmits_a_lost_initial_handshake() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let peer = server.local_addr().unwrap();
        let profile = aethernoize::from_profile("off");
        let verifier = tokio::spawn(async move {
            verify_endpoint(
                peer,
                [7u8; 32],
                [9u8; 32],
                [1u8, 2, 3],
                "172.16.0.2".parse().unwrap(),
                &profile,
                Duration::from_secs(4),
                None,
            )
            .await
        });

        let mut received = Vec::new();
        let mut buf = [0u8; 2048];
        for _ in 0..3 {
            let n = tokio::time::timeout(Duration::from_secs(3), server.recv(&mut buf))
                .await
                .expect("handshake packet deadline")
                .expect("handshake packet");
            received.push(buf[..n].to_vec());
        }

        verifier.abort();
        let _ = verifier.await;

        assert_eq!(received.len(), 3);
        assert_eq!(received[0], received[1]);
        assert_eq!(received[1], received[2]);
    }
}
