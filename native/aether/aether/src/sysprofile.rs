use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tier {
    Low,
    Medium,
    High,
}

#[derive(Debug, Clone, Copy)]
pub struct Tuning {
    pub tier: Tier,
    pub cpus: usize,
    pub mem_mb: Option<u64>,
    pub scan_concurrency_cap: usize,
    /// `SO_RCVBUF` for every datagram socket in the data plane.
    ///
    /// Large on purpose, and it must stay large: this is the buffer the 1.2.8
    /// media-stall fix was actually about. When the reader is a few hundred
    /// microseconds late the kernel starts discarding inbound datagrams, and
    /// among them are the WireGuard handshake replies the session needs to stay
    /// alive. Receive buffers cost latency to nobody.
    pub udp_socket_rcv_buf: usize,
    /// `SO_SNDBUF` for every datagram socket in the data plane.
    ///
    /// ## 1.2.8-r6 ROOT CAUSE. This was the same 7 MB number as the receive
    /// buffer, and it is the reason five rounds of queue bounding did nothing.
    ///
    /// `tune_udp_buffers` was introduced in 1.2.8 to fix inbound datagram loss
    /// and it set BOTH directions from one figure. So every WireGuard socket -
    /// the entire WARP and gool data plane - got a **7 MB kernel send buffer**,
    /// sitting immediately downstream of every throttle the last four rounds
    /// added:
    ///
    /// ```text
    ///   smoltcp socket tx buffer (512 KB)   <- r4/r5 sized this
    ///     -> StackDevice.tx        (64 pkts) <- r5 made transmit() refuse here
    ///       -> outbound mpsc      (256 pkts) <- r2 bounded this
    ///         -> UDP socket        (7 MB!)   <- nobody looked here
    ///           -> qdisc -> radio
    /// ```
    ///
    /// `send()` on a datagram socket only blocks once `SO_SNDBUF` is full. At
    /// 7 MB it never is, so the WireGuard writer never blocked, so the mpsc never
    /// filled, so `StackDevice.tx` never reached 64, so `transmit()` never
    /// refused, so CUBIC was still being shown a link with infinite capacity and
    /// zero loss - exactly the condition r5 was written to remove, one layer
    /// further down than r5 looked.
    ///
    /// The r5 field log is the proof and it is unambiguous: `backpressure 0` and
    /// `outbound tail-drops 0` on **every one of the 96 telemetry lines** of a
    /// 13-minute session in which a fresh dial through that same path cost
    /// 5694 ms. The queue was real; it was in the kernel, where nothing in this
    /// process was measuring it.
    ///
    /// Sized to a latency target now, not to RAM: ~128 KB is ~100 tunnel MTUs,
    /// roughly 250 ms of a 4 Mbit/s mobile uplink. Small enough that the socket
    /// pushes back, large enough to swallow a whole [MAX_ENCAP_BATCH] burst
    /// without a single wake-up. Note Linux DOUBLES what you ask for and reports
    /// the doubled value, so the log showing ~256 KB here is correct.
    pub udp_socket_snd_buf: usize,
    /// Per-flow TCP SEND buffer: app->network data waiting for CUBIC to clock it
    /// out. Feeds the congestion controller, so it may be generous.
    pub netstack_tcp_tx_buf: usize,
    /// Per-flow TCP RECEIVE buffer, i.e. **the advertised receive window**.
    ///
    /// 1.2.8-r4: this used to be the same number as the send buffer (512 KB on
    /// this tier), which let one remote server keep half a megabyte in flight
    /// towards a 475 ms mobile path - about two seconds of standing queue, read
    /// off the screen as "ping 2000". Sized to a realistic mobile
    /// bandwidth-delay product now, not to available RAM.
    pub netstack_tcp_rx_buf: usize,
    pub netstack_udp_buf: usize,
    pub channel_capacity: usize,
    /// Initial HTTP/2 STREAM window advertised to a MASQUE-over-H2 edge.
    ///
    /// Core 1.9.0. HTTP/2 flow control decides how much data the edge may have
    /// in flight towards us before it has to stop and wait, so it puts a hard
    /// ceiling of window / round-trip-time on a download. The h2 crate defaults
    /// to the RFC minimum of 64 KiB, which caps a 130 ms path at ~500 KB/s
    /// however fast the line underneath really is. QUIC and WireGuard never meet
    /// this limit because their windows are megabytes wide; this is what puts the
    /// HTTP/2 carrier on the same footing.
    ///
    /// NOTE (1.2.9): this is a CARRIER window, not a per-flow one. What a single
    /// device flow may keep in flight end to end is still bounded by
    /// [Tuning::netstack_tcp_rx_buf] above, which is the advertised window smoltcp
    /// derives from that flow's receive buffer and which r4 deliberately sized to
    /// a mobile bandwidth-delay product. Lifting the carrier ceiling therefore
    /// cannot re-create the standing queue r4 removed: the inner window stays the
    /// binding limit.
    pub h2_stream_window: u32,
    /// Initial HTTP/2 CONNECTION window for the same carrier (core 1.9.0).
    pub h2_connection_window: u32,
}

static TUNING: OnceLock<Tuning> = OnceLock::new();

fn detected_cpus() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1)
}

#[cfg(target_os = "linux")]
fn total_mem_mb() -> Option<u64> {
    let data = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in data.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            let kb: u64 = rest.trim().trim_end_matches("kB").trim().parse().ok()?;
            return Some(kb / 1024);
        }
    }
    None
}

#[cfg(target_os = "android")]
fn total_mem_mb() -> Option<u64> {
    let data = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in data.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            let kb: u64 = rest.trim().trim_end_matches("kB").trim().parse().ok()?;
            return Some(kb / 1024);
        }
    }
    None
}

#[cfg(target_os = "macos")]
fn total_mem_mb() -> Option<u64> {
    let mut size: u64 = 0;
    let mut len = std::mem::size_of::<u64>();
    let name = b"hw.memsize\0";
    let ret = unsafe {
        libc::sysctlbyname(
            name.as_ptr() as *const libc::c_char,
            &mut size as *mut u64 as *mut libc::c_void,
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if ret == 0 {
        Some(size / 1024 / 1024)
    } else {
        None
    }
}

#[cfg(target_os = "windows")]
fn total_mem_mb() -> Option<u64> {
    #[repr(C)]
    struct MemoryStatusEx {
        length: u32,
        memory_load: u32,
        total_phys: u64,
        avail_phys: u64,
        total_page_file: u64,
        avail_page_file: u64,
        total_virtual: u64,
        avail_virtual: u64,
        avail_extended_virtual: u64,
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn GlobalMemoryStatusEx(buf: *mut MemoryStatusEx) -> i32;
    }

    let mut status = MemoryStatusEx {
        length: std::mem::size_of::<MemoryStatusEx>() as u32,
        memory_load: 0,
        total_phys: 0,
        avail_phys: 0,
        total_page_file: 0,
        avail_page_file: 0,
        total_virtual: 0,
        avail_virtual: 0,
        avail_extended_virtual: 0,
    };

    let ok = unsafe { GlobalMemoryStatusEx(&mut status) };
    if ok != 0 {
        Some(status.total_phys / 1024 / 1024)
    } else {
        None
    }
}

#[cfg(not(any(
    target_os = "linux",
    target_os = "android",
    target_os = "macos",
    target_os = "windows"
)))]
fn total_mem_mb() -> Option<u64> {
    None
}

fn detect_tier(cpus: usize, mem_mb: Option<u64>) -> Tier {
    if let Ok(v) = std::env::var("AETHER_PERF_PROFILE") {
        match v.trim().to_lowercase().as_str() {
            "low" => return Tier::Low,
            "medium" | "mid" => return Tier::Medium,
            "high" => return Tier::High,
            _ => {}
        }
    }

    let mem_low = mem_mb.map(|m| m <= 384).unwrap_or(false);
    let mem_medium = mem_mb.map(|m| m <= 1536).unwrap_or(false);

    if cpus <= 2 || mem_low {
        Tier::Low
    } else if cpus <= 4 || mem_medium {
        Tier::Medium
    } else {
        Tier::High
    }
}

/// Reads a buffer size in bytes from the environment, ignoring anything
/// outside what a TCP socket can sensibly be given.
///
/// Core 1.9.0. Kept as upstream wrote it: it only ever overrides the two
/// netstack TCP figures, and the defaults it falls back to are this app's
/// (see the r4/r6 sizing below), so an unset variable changes nothing.
fn buffer_override(key: &str, fallback: usize) -> usize {
    std::env::var(key)
        .ok()
        .and_then(|value| value.trim().parse::<usize>().ok())
        .filter(|bytes| (16 * 1024..=64 * 1024 * 1024).contains(bytes))
        .unwrap_or(fallback)
}

fn build_tuning() -> Tuning {
    let cpus = detected_cpus();
    let mem_mb = total_mem_mb();
    let tier = detect_tier(cpus, mem_mb);

    // 1.2.8-r4: the TCP figure is split into tx/rx. The send side keeps the old
    // sizing (it queues for the congestion controller); the receive side is the
    // window we advertise and is now bounded by a plausible mobile BDP rather
    // than by how much RAM the phone happens to have. 192 KB over a 400 ms path
    // is ~3.8 Mbit/s of headroom per flow and caps the queue a single download
    // can build at a few hundred milliseconds instead of a few seconds.
    // >>> AETHER-APP-PATCH netstack-buffer-sizing
    // 1.2.8-r6: the UDP figure is split the same way the TCP one was in r4, and
    // for the same reason. The receive side keeps its generous sizing (it only
    // ever prevents loss); the SEND side is now a latency budget. See
    // [Tuning::udp_socket_snd_buf] for the log that made this the root cause.
    //
    // The netstack TCP send buffer comes down with it: 512 KB in front of a
    // single flow is ~10 s of a live dubbing uplink (~48 KB/s of raw PCM), and
    // in chained mode there IS only one flow - the whole device rides one
    // Psiphon SSH connection, which the r5 log confirms (`tcp flows=1`).
    let (
        scan_concurrency_cap,
        udp_socket_rcv_buf,
        udp_socket_snd_buf,
        netstack_tcp_tx_buf,
        netstack_tcp_rx_buf,
        netstack_udp_buf,
        channel_capacity,
    ) = match tier {
        Tier::Low => (4usize, 256 * 1024, 48 * 1024, 64 * 1024, 64 * 1024, 32 * 1024, 128usize),
        Tier::Medium => (10usize, 2 * 1024 * 1024, 96 * 1024, 96 * 1024, 128 * 1024, 64 * 1024, 512usize),
        Tier::High => (usize::MAX, 7 * 1024 * 1024, 128 * 1024, 128 * 1024, 192 * 1024, 128 * 1024, 1024usize),
    };
    //
    // CORE 1.9.0 REBASE NOTE (1.2.9). Upstream 1.9.0 rewrote this same table on
    // its own: it folded the UDP figure back into ONE 7 MB number for both
    // directions and raised the netstack TCP buffers to 2 MB rx / 512 KB tx on
    // this tier. Taking those numbers would revert the r6 root-cause fix (a 7 MB
    // kernel send buffer is what silently disabled every throttle above it) and
    // the r4 window bound (which is what stopped a single download building two
    // seconds of standing queue). The engine upgrade was required to leave
    // download speed and connect behaviour exactly as 1.2.8 tuned them, so the
    // app figures stay and only upstream's new MECHANISMS are adopted: the
    // environment overrides just below and the HTTP/2 windows further down.
    // <<< AETHER-APP-PATCH netstack-buffer-sizing

    // Core 1.9.0: the two netstack TCP figures can be overridden per device
    // without a rebuild. The fallbacks are the app values above.
    let netstack_tcp_rx_buf = buffer_override("AETHER_NETSTACK_TCP_RX", netstack_tcp_rx_buf);
    let netstack_tcp_tx_buf = buffer_override("AETHER_NETSTACK_TCP_TX", netstack_tcp_tx_buf);

    // How much unacknowledged data an HTTP/2 MASQUE edge may have on its way to
    // us. It is a promise rather than a reservation, but it does bound how much
    // arrives before we have drained it, so it follows the tier like the rest.
    // Upstream 1.9.0's figures, taken unchanged: 1.2.8 had no such knob at all
    // (the h2 crate's 64 KiB default was the ceiling) and the per-flow window
    // above still governs what any one connection can keep in flight.
    let (h2_stream_window, h2_connection_window) = match tier {
        Tier::Low => (2 * 1024 * 1024, 4 * 1024 * 1024),
        Tier::Medium => (8 * 1024 * 1024, 16 * 1024 * 1024),
        Tier::High => (16 * 1024 * 1024, 32 * 1024 * 1024),
    };

    Tuning {
        tier,
        cpus,
        mem_mb,
        scan_concurrency_cap,
        udp_socket_rcv_buf,
        udp_socket_snd_buf,
        netstack_tcp_tx_buf,
        netstack_tcp_rx_buf,
        netstack_udp_buf,
        channel_capacity,
        h2_stream_window,
        h2_connection_window,
    }
}

pub fn tuning() -> &'static Tuning {
    TUNING.get_or_init(build_tuning)
}

pub fn log_summary() {
    let t = tuning();
    let mem = t
        .mem_mb
        .map(|m| format!("{m}MB"))
        .unwrap_or_else(|| "unknown".to_string());
    let cap = if t.scan_concurrency_cap == usize::MAX {
        "unlimited".to_string()
    } else {
        t.scan_concurrency_cap.to_string()
    };
    // >>> AETHER-APP-PATCH netstack-buffer-sizing
    // This exact string is a BUILD FINGERPRINT. `udp socket rcv/snd=` is the
    // r6 wording. If a field log shows `udp socket buffer=NNNNKB` (one figure)
    // the engine predates r6, and if it shows `netstack buffers=` it predates
    // r4 - in either case nothing diagnosed since is being tested. That is
    // precisely how the r4 round was lost. Do not reword it casually.
    log::info!(
        "[*] performance profile: {:?} (cpus={} mem={}); scan concurrency cap={}, udp socket rcv/snd={}KB/{}KB (snd = uplink queue budget), netstack tcp tx/rx={}KB/{}KB (rx = advertised window), netstack udp={}KB, channel capacity={}, h2 windows stream/conn={}KB/{}KB",
        t.tier,
        t.cpus,
        mem,
        cap,
        t.udp_socket_rcv_buf / 1024,
        t.udp_socket_snd_buf / 1024,
        t.netstack_tcp_tx_buf / 1024,
        t.netstack_tcp_rx_buf / 1024,
        t.netstack_udp_buf / 1024,
        t.channel_capacity,
        t.h2_stream_window / 1024,
        t.h2_connection_window / 1024,
    );
    // <<< AETHER-APP-PATCH netstack-buffer-sizing
}

#[cfg(unix)]
pub fn raise_fd_limit() {
    let mut limit = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    if unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut limit) } != 0 {
        return;
    }

    #[cfg_attr(not(target_os = "macos"), allow(unused_mut))]
    let mut wanted = limit.rlim_max;
    #[cfg(target_os = "macos")]
    {
        wanted = wanted.min(macos_max_files_per_proc());
    }

    if wanted <= limit.rlim_cur {
        return;
    }

    let raised = libc::rlimit {
        rlim_cur: wanted,
        rlim_max: limit.rlim_max,
    };
    if unsafe { libc::setrlimit(libc::RLIMIT_NOFILE, &raised) } == 0 {
        log::debug!(
            "[*] open file limit raised from {} to {}",
            limit.rlim_cur,
            wanted
        );
    }
}

#[cfg(not(unix))]
pub fn raise_fd_limit() {}

#[cfg(target_os = "macos")]
fn macos_max_files_per_proc() -> libc::rlim_t {
    const OPEN_MAX: libc::rlim_t = 10240;

    let mut value: libc::c_int = 0;
    let mut len = std::mem::size_of::<libc::c_int>();
    let name = b"kern.maxfilesperproc\0";
    let ret = unsafe {
        libc::sysctlbyname(
            name.as_ptr() as *const libc::c_char,
            &mut value as *mut libc::c_int as *mut libc::c_void,
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if ret == 0 && value > 0 {
        value as libc::rlim_t
    } else {
        OPEN_MAX
    }
}

#[cfg(unix)]
pub fn open_file_limit() -> Option<usize> {
    let mut limit = libc::rlimit {
        rlim_cur: 0,
        rlim_max: 0,
    };
    if unsafe { libc::getrlimit(libc::RLIMIT_NOFILE, &mut limit) } != 0
        || limit.rlim_cur == libc::RLIM_INFINITY
    {
        return None;
    }
    usize::try_from(limit.rlim_cur).ok()
}

#[cfg(not(unix))]
pub fn open_file_limit() -> Option<usize> {
    None
}

pub fn cap_concurrency(requested: usize) -> usize {
    requested.min(tuning().scan_concurrency_cap)
}

// >>> AETHER-APP-PATCH udp-socket-buffer-asymmetry
/// `SO_RCVBUF` to request on data-plane datagram sockets.
pub fn udp_socket_rcv_buf_bytes() -> usize {
    tuning().udp_socket_rcv_buf
}

/// `SO_SNDBUF` to request on data-plane datagram sockets.
///
/// Deliberately much smaller than the receive side. This is the last queue
/// between the congestion controller and the radio, and until r6 it was 7 MB,
/// which silently disabled every throttle above it. See
/// [Tuning::udp_socket_snd_buf].
pub fn udp_socket_snd_buf_bytes() -> usize {
    tuning().udp_socket_snd_buf
}
// <<< AETHER-APP-PATCH udp-socket-buffer-asymmetry

pub fn netstack_tcp_tx_buf_bytes() -> usize {
    tuning().netstack_tcp_tx_buf
}

pub fn netstack_tcp_rx_buf_bytes() -> usize {
    tuning().netstack_tcp_rx_buf
}

pub fn netstack_udp_buf_bytes() -> usize {
    tuning().netstack_udp_buf
}

pub fn channel_capacity() -> usize {
    tuning().channel_capacity
}

/// Core 1.9.0: the stream window `masque_h2::h2_builder()` advertises.
pub fn h2_stream_window_bytes() -> u32 {
    tuning().h2_stream_window
}

/// Core 1.9.0: the connection window `masque_h2::h2_builder()` advertises.
pub fn h2_connection_window_bytes() -> u32 {
    tuning().h2_connection_window
}
