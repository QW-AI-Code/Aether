use std::collections::HashMap;
use std::collections::HashSet;
use std::collections::VecDeque;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet};
use smoltcp::phy::{Checksum, Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::{tcp, udp};
use smoltcp::time::Instant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpEndpoint, Ipv4Address, Ipv6Address};
use tokio::sync::{mpsc, oneshot};

use crate::error::{AetherError, Result};

/// Send-buffer size for one netstack TCP flow: how much app->network data may
/// wait for the congestion controller to clock it out.
///
/// Generous is correct here now that a controller exists (see
/// [`Cmd::OpenTcp`]): this buffer feeds CUBIC, it does not bypass it.
fn tcp_tx_buf() -> usize {
    crate::sysprofile::netstack_tcp_tx_buf_bytes()
}

/// Receive-buffer size for one netstack TCP flow.
///
/// ## 1.2.8-r4: this is the advertised TCP receive window, and it was 512 KB
///
/// smoltcp derives the window it advertises from the free space in THIS buffer,
/// so its size is a hard cap on how many bytes a remote server is allowed to put
/// in flight towards the device. At 512 KB, on a path measured at 475 ms RTT
/// carrying a few hundred KB/s, a single download is permitted to build roughly
/// **two seconds of standing queue** inside the carrier's buffers before the
/// window even becomes the limit - which is read off the screen as "the ping
/// went to 2000" and, on every other flow sharing that queue, as "the connection
/// dropped and then came back".
///
/// The download direction is paced by the remote's congestion control, so this
/// is the only knob on this side that bounds it. It is now sized to a realistic
/// bandwidth-delay product for a mobile path instead of to available RAM: still
/// enough to saturate a fast link, no longer enough to hide seconds of latency.
///
/// Kept separate from [tcp_tx_buf] on purpose: the two have opposite
/// requirements and sharing one number is what made the wrong one obvious.
fn tcp_rx_buf() -> usize {
    crate::sysprofile::netstack_tcp_rx_buf_bytes()
}

/// Kept for the pending-write bound, which tracks the SEND side.
fn tcp_buf() -> usize {
    tcp_tx_buf()
}

fn udp_buf() -> usize {
    crate::sysprofile::netstack_udp_buf_bytes()
}

fn udp_meta() -> usize {
    match crate::sysprofile::tuning().tier {
        crate::sysprofile::Tier::Low => 32,
        crate::sysprofile::Tier::Medium => 64,
        crate::sysprofile::Tier::High => 128,
    }
}

fn app_queue() -> usize {
    crate::sysprofile::channel_capacity()
}

const MAX_INGEST_PER_TICK: usize = 512;
const MAX_RECV_CHUNKS: usize = 128;

/// Per-pass budget for the app->network direction.
///
/// 1.2.8-r2 STARVATION FIX. See [run]: this direction now gets a budget of its
/// own on every pass of the loop instead of competing for the one `select!`
/// wake-up that a saturated download had already taken.
const MAX_APP_INGEST_PER_TICK: usize = 512;

/// Per-pass budget for control messages (open a flow, close it, resolve a name,
/// set the interface address).
///
/// Small on purpose - it is a handful of messages per new connection - but it
/// MUST be served under load. A starved `cmd` queue is a tunnel in which no new
/// flow can be opened while an existing one is downloading, which is exactly
/// what the user reads as "connected, ping 2000, and nothing else opens".
const MAX_CMD_PER_TICK: usize = 64;

/// How long the app->network direction may make no progress at all, while the
/// network->app direction keeps making it, before it is reported once.
const STARVATION_REPORT_AFTER: std::time::Duration = std::time::Duration::from_secs(2);
const STARVATION_REPORT_GAP: std::time::Duration = std::time::Duration::from_secs(10);

/// How busy the network->app direction must be before a quiet app->network
/// direction is worth reporting.
///
/// 1.2.8-r3. The r2 build shipped this telemetry and then the field log filled
/// up with lines like
///
/// ```text
/// app->network idle for 32.033894156s while 1 packets arrived;
/// backlog 0 bytes over 0 blocked flows
/// ```
///
/// One packet a pass is not a saturated download, and an empty backlog over
/// zero blocked flows means nothing was waiting to go out in the first place.
/// The condition it was built to catch cannot look like that: real starvation
/// needs a queue that is full and a queue that is not being served. Reporting a
/// quiet tunnel as a stalled one sent the whole r2 investigation after a ghost,
/// so the signal now requires the download direction to actually be busy.
const STARVATION_MIN_INBOUND: usize = 32;
const BACKPRESSURE_RETRY: std::time::Duration = std::time::Duration::from_millis(2);
const DROP_REPORT_STEP: usize = 512;
const MAX_IDLE_TICK: std::time::Duration = std::time::Duration::from_millis(250);

/// How often the data plane reports itself. See the telemetry block in [run].
const TELEMETRY_INTERVAL: std::time::Duration = std::time::Duration::from_secs(15);

// >>> AETHER-APP-PATCH netstack-device-backpressure
/// Delay before the FIRST telemetry line.
///
/// 1.2.8-r5: r4 waited a full [TELEMETRY_INTERVAL] before saying anything, so a
/// short reproduction ("press start dubbing, watch the ping, press stop") could
/// finish with the data plane never having reported once - and the whole point of
/// the counter is to be read during exactly that window. The first line lands at
/// two seconds now, which also means the log confirms the stack is instrumented
/// before the user has done anything at all.
const TELEMETRY_FIRST: std::time::Duration = std::time::Duration::from_secs(2);
// <<< AETHER-APP-PATCH netstack-device-backpressure

/// How much app-to-network data may sit in the backlog before the stack stops
/// accepting more.
///
/// 1.2.8 MEDIA-STALL FIX. The old loop had a single global deferred queue and
/// stopped reading `data_in` entirely while it held anything - `recv(), if
/// deferred.is_empty()`. One flow that could not take another byte therefore
/// froze the writes of EVERY other flow through the tunnel, which is precisely
/// the "still connected, ping 2000, then no site opens at all" report: a video
/// player is the easiest way in the world to produce that one flow. The backlog
/// is now per-flow ordered and never gates the others; this cap only bounds
/// memory.
// >>> AETHER-APP-PATCH netstack-device-backpressure
// 1.2.8-r5: 8 MB was sized to "cannot exhaust RAM", which is not what this
// number is for. It is a QUEUE in front of a mobile uplink, and 8 MB of PCM at
// the ~44 KB/s a live dubbing session uploads is over three minutes of audio
// waiting its turn. Any flow that ever reached that depth was already dead in
// every sense the user cares about. 512 KB is ~1 s at that rate, which is the
// most a real-time stream can tolerate and still be real-time; past it the
// SOCKS5 reader stops being served and the backpressure reaches the app, which
// is exactly what should happen.
const MAX_BACKLOG_BYTES: usize = 512 * 1024;
// <<< AETHER-APP-PATCH netstack-device-backpressure

/// How long a single flow may accept nothing at all before it is reset.
///
/// smoltcp will happily retransmit into a black hole forever, and a flow whose
/// peer stopped reading used to pin its buffers (and, before the backlog was
/// made per-flow, the whole tunnel) for the life of the session.
const TCP_WEDGE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);

// >>> AETHER-APP-PATCH netstack-drain-liveness
/// How long a flow may hold queued bytes without ONE of them being acknowledged
/// before it is declared dead.
///
/// ## ROOT CAUSE this fixes (1.2.8-r7) - the bug that survived r2 through r6
///
/// [TCP_WEDGE_TIMEOUT] above, and the `last_progress` field that drives it,
/// measure the WRONG EVENT. `last_progress` is refreshed whenever the socket
/// *accepts* bytes out of `pending` into its send buffer (see `service_tcp`),
/// and `reap_wedged` only ever considers flows sitting in `backlog.blocked`.
/// Accepting a byte into a 128 KB send buffer is not progress: it is the socket
/// agreeing to remember it. A flow whose peer has gone completely silent keeps
/// accepting bytes, and therefore keeps looking healthy, until that buffer is
/// 100% full - and only then does the 30 s clock even start.
///
/// The r6 field log times it exactly. `socket send-queue`, worst flow:
///
///   18:13:15   73120 bytes   <- peer already silent, still "making progress"
///   18:13:45  121200 bytes
///   18:14:00  131072 bytes   <- exactly full, app->net 1 pkt in 15 s
///
/// Forty-five seconds of a dead flow with `backlog 0`, `backpressure 0`,
/// `tail-drops 0` and not one warning, because every gauge in the process was
/// watching queues that were empty BY DEFINITION - the bytes were parked in the
/// socket send buffer, which nothing was allowed to call a queue. The wedge
/// reaper would have fired at 18:14:30. The session ended at 18:14:16.
///
/// In a chained `Aether -> Psiphon` session this is fatal rather than untidy:
/// Psiphon multiplexes the WHOLE DEVICE over ONE SSH connection, so that single
/// wedged flow is every app on the phone. Download collapses while upload keeps
/// trickling out on keepalives (a1: `123 B/s` down, `1.2 KB/s` up) and the
/// latency badge reports the depth of the stuck queue as ping (6442 ms).
///
/// So liveness is now measured on DRAINAGE: has `send_queue()` gone down. That
/// is the only signal in this stack that requires the far end to have actually
/// received something, because bytes only leave a TCP send buffer when they are
/// acknowledged. Nothing else here can tell "sent" from "remembered".
///
/// Sized against the measured path, not guessed: this session RTT was 142-238 ms
/// and endpoint probes 380-520 ms. A flow with zero bytes acknowledged for 12 s
/// has missed roughly 25 round trips and about five RTO backoffs. It is not
/// slow, it is gone.
///
/// 1.2.8-r8 lowers this from 12 s to 8 s. The r7 field log is the argument: the
/// flow went **9.4 s** with nothing acknowledged, recovered 2.6 s inside the old
/// deadline, and the session was then connected-but-dead for three minutes until
/// the user reconnected by hand. 12 s was sized as "about 25 round trips, that is
/// certainly gone"; 8 s is still ~16 round trips on this path and it is inside the
/// window where a reset can still be repaired by one Psiphon redial instead of by
/// the user. See also [STALL_FLAP_LIMIT] for the stall that never reaches any
/// deadline at all.
const TCP_DRAIN_STALL_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(8);

/// When a non-draining flow starts being reported, well before it is reset.
///
/// Deliberately short: the reason six rounds of diagnosis missed this is that a
/// dead flow was invisible while it died. It is visible from 3 s now.
const TCP_DRAIN_STALL_WARN: std::time::Duration = std::time::Duration::from_secs(3);
// <<< AETHER-APP-PATCH netstack-drain-liveness

// >>> AETHER-APP-PATCH netstack-uplink-admission
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;

/// ## 1.2.8-r8 ROOT CAUSE: the upload side had no admission control at all
///
/// r7 finally made a stalled flow visible, and the first field log from an r7
/// build says two things at once that r2-r7 could not both explain:
///
/// ```text
/// 19:59:49 [uplink] 1506 pkts, 1606 KB (107 KB/s) | writer waited 0 times
/// 19:59:51 W/ping   first round trip 4803 ms, confirming 1309 ms, floor 134 ms
/// 19:59:56 W/netstack flow 4 has 58404 bytes queued and has had NOTHING
///                    acknowledged for 3.138011154s
/// 20:00:03 W/ping   first round trip 7173 ms, confirming 456 ms
/// ```
///
/// A 7.1 second round trip on a path whose floor is 134 ms is 7 seconds of
/// QUEUE. And the uplink writer never waited once, the device queue peaked at 2
/// of 64 packets, `backpressure` was 0 and `tail-drops` were 0 - so the queue was
/// not in the kernel (r6's clamp holds), not in the device, and not in the socket
/// send buffer at the instant the window ended.
///
/// It was in the two places nothing has ever bounded, or even measured:
///
/// ```text
///   queue                     bound before r8
///   shared `data_in` channel   1024 MESSAGES x up to 16 KB each
///   TcpState::pending          max_tcp_pending() = 256 KB
///   socket send buffer         tcp_tx_buf() = 128 KB   (r6)
///   Backlog                    MAX_BACKLOG_BYTES = 512 KB (r5)
/// ```
///
/// Every one of those is a BYTE bound, and a byte bound is a latency bound only
/// if you know the rate. At the 107 KB/s this uplink actually sustained, the
/// queues in front of one byte add up to ~8 seconds - which is the 7173 ms the
/// ping monitor measured, to the sample. And because Psiphon multiplexes the
/// whole device over ONE SSH connection in chained mode (`tcp flows=1`), that
/// queue sits in front of *everything*: the latency badge, DNS, every other app.
///
/// The gauges missed it because they are point samples read at the END of a 15 s
/// window, and `pending` plus the channel were never sampled at all. `socket
/// send-queue 3792 bytes` and a 4803 ms probe are the same instant seen from two
/// places.
///
/// ## The fix: bound the queue in TIME, at the source
///
/// Each flow now carries a [FlowCredit]. The app side (the SOCKS5 reader) may
/// only have `outstanding = handed-to-the-stack - acknowledged-by-the-peer` bytes
/// in the pipe, and the ceiling is that flow's own measured drain rate times
/// [UPLINK_QUEUE_BUDGET]. Past it the reader simply does not read - which is
/// backpressure that travels out of this process, through the loopback leg, into
/// hev-socks5-tunnel, into the TUN, and finally into the congestion window of the
/// app doing the uploading. That is where it belongs, and it is what every other
/// VPN gets for free by not terminating TCP.
///
/// Same session, same rate: ~53 KB outstanding instead of ~900 KB, i.e. ~0.5 s of
/// standing queue instead of ~8 s.
///
/// This is deliberately NOT another buffer-size constant. r5 shrank the backlog,
/// r6 shrank the kernel send buffer and the socket send buffer; each was correct
/// and each was defeated by the next queue down the line, because a fixed byte
/// count cannot know the rate. A credit measured against the rate the peer is
/// actually acknowledging cannot be wrong about it.
const UPLINK_QUEUE_BUDGET: std::time::Duration = std::time::Duration::from_millis(500);

/// Floor for the per-flow uplink budget, so a flow that has not measured its rate
/// yet - or is genuinely slow - can still keep a congestion window full.
///
/// 48 KB is above the bandwidth-delay product of the path in the report
/// (107 KB/s x ~0.4 s = ~43 KB), so admission can never be the throughput limit
/// on the link that produced it. It only removes the standing queue.
const MIN_FLOW_QUEUE_BYTES: usize = 48 * 1024;

/// How often a flow's drain rate is re-estimated from acknowledged bytes.
const RATE_SAMPLE_WINDOW: std::time::Duration = std::time::Duration::from_millis(500);

/// Poll step used by a writer that is over budget.
///
/// A sleep rather than a `Notify`: this path is only taken when we have *decided*
/// to slow a writer down, exactly one task per flow waits on it, and a
/// missed-wakeup bug in backpressure code would be indistinguishable from the
/// stall this file exists to fix.
const CREDIT_WAIT_STEP: std::time::Duration = std::time::Duration::from_millis(2);

/// Ceiling on one admission wait, so a flow whose accounting is somehow stuck
/// degrades to "queue it anyway" instead of parking a task for good.
const CREDIT_WAIT_MAX: std::time::Duration = std::time::Duration::from_secs(20);

/// Window over which repeated drain stalls on one flow are counted.
const STALL_FLAP_WINDOW: std::time::Duration = std::time::Duration::from_secs(60);

/// Drain stalls inside [STALL_FLAP_WINDOW] before a flow is reset even though
/// each individual stall recovered on its own.
///
/// The r7 log is exactly this case: the flow went 9.4 s with nothing
/// acknowledged, recovered 2.6 s short of [TCP_DRAIN_STALL_TIMEOUT], and the
/// session was left technically connected and practically dead - uplink 6-18
/// KB/s for the next three minutes while the badge read a healthy 140-260 ms.
/// "It recovered on its own" is not a state worth preserving when it keeps
/// happening: a reset costs one Psiphon redial, and the user gets that in seconds
/// instead of a manual disconnect and reconnect.
const STALL_FLAP_LIMIT: u32 = 2;

/// Per-flow uplink admission: how much unacknowledged app data may be inside this
/// process on behalf of one flow.
///
/// `sent` is written only by the app side and `acked`/`cap`/`closed` only by the
/// netstack task, so relaxed atomics are enough - there is no invariant here that
/// two writers could race on.
pub struct FlowCredit {
    /// Bytes the app has handed to the stack for this flow, ever.
    sent: AtomicU64,
    /// Bytes the PEER has acknowledged, as published by `service_tcp`.
    acked: AtomicU64,
    /// Current budget in bytes: measured drain rate x [UPLINK_QUEUE_BUDGET].
    cap: AtomicUsize,
    /// Set when the flow is gone, so a waiting writer is released at once.
    closed: AtomicBool,
}

impl FlowCredit {
    fn new() -> Self {
        Self {
            sent: AtomicU64::new(0),
            acked: AtomicU64::new(0),
            cap: AtomicUsize::new(MIN_FLOW_QUEUE_BYTES),
            closed: AtomicBool::new(false),
        }
    }

    /// Bytes in flight for this flow: still in the channel, in `pending`, or in
    /// the socket's send buffer unacknowledged.
    fn outstanding(&self) -> u64 {
        self.sent
            .load(Ordering::Relaxed)
            .saturating_sub(self.acked.load(Ordering::Relaxed))
    }

    fn budget(&self) -> usize {
        self.cap.load(Ordering::Relaxed)
    }

    /// Netstack side, once per service pass.
    fn publish(&self, acked: u64, cap: usize) {
        // Monotonic by construction (`accepted_total` only grows, `send_queue`
        // only shrinks on an ACK), but clamped anyway: a credit that could move
        // backwards would hand out free window.
        if acked > self.acked.load(Ordering::Relaxed) {
            self.acked.store(acked, Ordering::Relaxed);
        }
        self.cap
            .store(cap.max(MIN_FLOW_QUEUE_BYTES), Ordering::Relaxed);
    }

    fn close(&self) {
        self.closed.store(true, Ordering::Relaxed);
    }

    /// App side. Returns once this flow may add `len` more bytes.
    ///
    /// Never refuses and never reorders - a TCP stream may not lose or reorder a
    /// byte - it only makes the writer WAIT, which is the one thing that reaches
    /// the uploading application.
    async fn reserve(&self, len: usize) {
        let deadline = std::time::Instant::now() + CREDIT_WAIT_MAX;
        loop {
            if self.closed.load(Ordering::Relaxed) {
                break;
            }
            let out = self.outstanding();
            // `out == 0` stops a single oversized chunk deadlocking against its
            // own budget: an empty pipe always accepts one write.
            if out == 0 || out.saturating_add(len as u64) <= self.budget() as u64 {
                break;
            }
            if std::time::Instant::now() >= deadline {
                break;
            }
            tokio::time::sleep(CREDIT_WAIT_STEP).await;
        }
        self.sent.fetch_add(len as u64, Ordering::Relaxed);
    }
}
// <<< AETHER-APP-PATCH netstack-uplink-admission

/// Outbound packets held back when the WireGuard writer is momentarily behind.
/// Anything past this is dropped, which is what a real link does when its queue
/// is full - but shredding a whole video burst because the writer was 200 us
/// late is not.
/// 1.2.8-r2: 2048 was another 2.6 MB of hidden queue in front of the WireGuard
/// writer (see `packet_queue_capacity` in lib.rs). Holding a burst across a
/// couple of 2 ms retries is the point; holding seconds of it is bufferbloat.
const MAX_TX_RETAINED: usize = 256;

// >>> AETHER-APP-PATCH netstack-device-backpressure
/// Hard cap on the device's outbound queue, in packets.
///
/// ## 1.2.8-r5 ROOT CAUSE #2: the congestion window was fed a lie
///
/// r4 gave this stack a real congestion controller (`socket-tcp-cubic` plus an
/// explicit `set_congestion_control`). Correct, necessary - and on its own still
/// defeated, because of the two functions right below this constant.
///
/// `StackDevice::transmit()` returned `Some(token)` **unconditionally** and `tx`
/// was an unbounded `VecDeque`. To smoltcp that is a link with infinite capacity
/// and zero latency: every segment CUBIC hands the device is accepted instantly
/// and accounted as SENT. Then `flush_tx` discovered the WireGuard writer was
/// full and quietly threw packets away - *after* smoltcp had already recorded
/// them as transmitted.
///
/// So the loss our own bottleneck produced was invisible to the controller that
/// exists to react to loss. CUBIC saw a link that never dropped anything and
/// never delayed anything, so it opened its window to the maximum and kept it
/// there, while the packets it "sent" were being shredded one queue later. The
/// only feedback left was the far-end retransmission timer, seconds away. That
/// is the same self-destruct loop r4 described, one layer lower down: r4 removed
/// the missing controller, and this removed the controller's ability to work.
///
/// It also explains why `outbound tail-drops` mattered so much and why the old
/// `flush_tx` could never surface it honestly: it dropped **exactly one packet
/// per pass and then `break`ed**, so under sustained pressure the queue kept
/// growing past [MAX_TX_RETAINED] without bound while the counter crawled.
///
/// The fix is the textbook one: make the device behave like a real link.
/// `transmit()` refuses when the queue is at capacity, smoltcp keeps the segment
/// in the socket's send buffer, and CUBIC's in-flight accounting stays honest.
/// Nothing is dropped behind the controller's back, so backpressure travels all
/// the way up to the sending application instead of turning into a retransmit
/// storm.
///
/// 64 packets at a 1280-byte tunnel MTU is ~82 KB: roughly 150 ms of queue on a
/// 4 Mbit/s mobile uplink. Enough to absorb a burst, far too little to hold the
/// two seconds of standing queue the field logs were showing.
const MAX_DEVICE_TX: usize = 64;

/// Absolute ceiling before the device sheds load.
///
/// `transmit()` is gated at [MAX_DEVICE_TX], but `receive()` must ALWAYS be able
/// to hand out a tx token or inbound processing stalls and ACKs stop flowing -
/// throttling our own sender is the goal, throttling acknowledgements is a
/// different bug. ACK-driven growth is small and drains every pass; this ceiling
/// is the last resort if it ever is not, and unlike the old code it sheds the
/// whole excess in one pass instead of one packet at a time.
const MAX_DEVICE_TX_HARD: usize = MAX_TX_RETAINED;
// <<< AETHER-APP-PATCH netstack-device-backpressure

/// Dead-flow reaping: idle TCP flows are probed instead of being trusted, and a
/// flow whose peer has vanished is closed instead of living forever.
const TCP_KEEPALIVE: smoltcp::time::Duration = smoltcp::time::Duration::from_secs(15);
const TCP_DEAD_PEER_TIMEOUT: smoltcp::time::Duration = smoltcp::time::Duration::from_secs(90);

// >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
// Core 2.0.0 gave the netstack three lifetimes this file never had: a bound on
// how long a connect may stay unanswered, a keepalive/timeout pair that can be
// tuned without a rebuild, and a linger after which an ORPHANED socket (the app
// side is gone, the far end never closes) is reset instead of kept for the life
// of the session. That last one is half of the file-descriptor exhaustion
// upstream fixed in #101/#106: every orphan held a socket, a port and two
// channels forever.
//
// The app's own defaults are kept as the defaults - 15 s keepalive / 90 s dead
// peer, measured on Iranian mobile paths, not upstream's 60/180 - and only the
// env override and the orphan linger are new. `AETHER_TCP_KEEPALIVE_SECS` and
// `AETHER_TCP_CONNECT_SECS` behave exactly as core 2.0.0 documents them.

/// How long a socket whose app side has gone away may wait for the far end to
/// finish the shutdown before it is reset.
const ORPHAN_LINGER: std::time::Duration = std::time::Duration::from_secs(10);

fn env_secs(name: &str, default: u64) -> std::time::Duration {
    let secs = std::env::var(name)
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .filter(|&v| v > 0)
        .map(|v| v.min(86_400))
        .unwrap_or(default);
    std::time::Duration::from_secs(secs)
}

pub(crate) fn tcp_keepalive() -> std::time::Duration {
    env_secs("AETHER_TCP_KEEPALIVE_SECS", TCP_KEEPALIVE.secs())
}

fn tcp_dead_peer_timeout() -> std::time::Duration {
    // Upstream derives its timeout as 3x the keepalive. Ours is 6x by
    // measurement (15 s / 90 s), so the ratio is preserved when the keepalive
    // is overridden rather than the absolute number.
    tcp_keepalive().saturating_mul(6)
}

fn tcp_connect_timeout() -> std::time::Duration {
    env_secs("AETHER_TCP_CONNECT_SECS", 30)
}

fn smol_duration(duration: std::time::Duration) -> smoltcp::time::Duration {
    smoltcp::time::Duration::from_millis(duration.as_millis().min(u64::MAX as u128) as u64)
}

#[derive(Debug, Clone, Copy)]
struct TcpLimits {
    connect: std::time::Duration,
    keepalive: std::time::Duration,
    dead_peer: std::time::Duration,
    orphan_linger: std::time::Duration,
}

impl TcpLimits {
    fn from_env() -> Self {
        Self {
            connect: tcp_connect_timeout(),
            keepalive: tcp_keepalive(),
            dead_peer: tcp_dead_peer_timeout(),
            orphan_linger: ORPHAN_LINGER,
        }
    }
}
// <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes

fn max_tcp_pending() -> usize {
    tcp_buf().saturating_mul(2).max(64 * 1024)
}

type OpenTcpResp = oneshot::Sender<std::result::Result<TcpConn, String>>;
type OpenUdpResp = oneshot::Sender<std::result::Result<UdpConn, String>>;

pub struct StackDevice {
    rx: VecDeque<Vec<u8>>,
    tx: VecDeque<Vec<u8>>,
    mtu: usize,
    // >>> AETHER-APP-PATCH netstack-device-backpressure
    /// How many times `transmit()` refused because the queue was full. This is
    /// the HEALTHY counter: it means backpressure reached smoltcp and the
    /// segment stayed in the socket buffer where the congestion controller can
    /// still see it. Compare with `tx_shed` below, which is the unhealthy one.
    tx_stalls: usize,
    /// Packets discarded at the hard ceiling. Should stay at zero; anything else
    /// is loss the congestion controller cannot observe.
    tx_shed: usize,
    /// High-water mark of the outbound queue since the last telemetry window.
    tx_peak: usize,
    // <<< AETHER-APP-PATCH netstack-device-backpressure
}

impl StackDevice {
    fn new(mtu: usize) -> Self {
        Self {
            rx: VecDeque::new(),
            tx: VecDeque::new(),
            mtu,
            tx_stalls: 0,
            tx_shed: 0,
            tx_peak: 0,
        }
    }
}

pub struct StackRxToken(Vec<u8>);
pub struct StackTxToken<'a>(&'a mut VecDeque<Vec<u8>>);

impl RxToken for StackRxToken {
    fn consume<R, F: FnOnce(&[u8]) -> R>(self, f: F) -> R {
        f(&self.0)
    }
}

impl<'a> TxToken for StackTxToken<'a> {
    fn consume<R, F: FnOnce(&mut [u8]) -> R>(self, len: usize, f: F) -> R {
        let mut buf = vec![0u8; len];
        let r = f(&mut buf);
        self.0.push_back(buf);
        r
    }
}

impl Device for StackDevice {
    type RxToken<'a> = StackRxToken;
    type TxToken<'a> = StackTxToken<'a>;

    fn receive(&mut self, _t: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let pkt = self.rx.pop_front()?;
        Some((StackRxToken(pkt), StackTxToken(&mut self.tx)))
    }

    fn transmit(&mut self, _t: Instant) -> Option<Self::TxToken<'_>> {
        // >>> AETHER-APP-PATCH netstack-device-backpressure
        // THE r5 FIX. Returning Some() unconditionally advertised an infinite,
        // zero-latency link to smoltcp, so the congestion controller r4 enabled
        // was reacting to a link that never signalled anything. Refusing here is
        // what makes it a real link: smoltcp keeps the segment in the socket's
        // send buffer, retries on the next poll, and CUBIC's in-flight
        // accounting stays truthful.
        //
        // NOTE the asymmetry with `receive()` above, which still always hands
        // out a token: this throttles what WE send, never acknowledgements.
        if self.tx.len() >= MAX_DEVICE_TX {
            self.tx_stalls = self.tx_stalls.saturating_add(1);
            return None;
        }
        // <<< AETHER-APP-PATCH netstack-device-backpressure
        Some(StackTxToken(&mut self.tx))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = self.mtu;
        caps.checksum.ipv4 = Checksum::Tx;
        caps.checksum.tcp = Checksum::Tx;
        caps.checksum.udp = Checksum::Tx;
        caps
    }
}

pub enum Cmd {
    OpenTcp { dst: SocketAddr, resp: OpenTcpResp, carrier: bool },
    OpenUdp { resp: OpenUdpResp },
    SetAddrs {
        v4: Option<(Ipv4Addr, u8)>,
        v6: Option<(Ipv6Addr, u8)>,
    },
}

pub enum DataIn {
    Tcp(usize, Vec<u8>),
    TcpClose(usize),
    Udp(usize, SocketAddr, Vec<u8>),
    UdpClose(usize),
}

pub struct TcpConn {
    pub id: usize,
    pub from_stack: mpsc::Receiver<Vec<u8>>,
    data_in: mpsc::Sender<DataIn>,
    split: bool,
    // >>> AETHER-APP-PATCH netstack-uplink-admission
    credit: Arc<FlowCredit>,
    // <<< AETHER-APP-PATCH netstack-uplink-admission
}

impl TcpConn {
    pub async fn send(&self, data: Vec<u8>) -> Result<()> {
        // >>> AETHER-APP-PATCH netstack-uplink-admission
        // The one line that bounds the upload path. Everything downstream of
        // here is a queue; this decides how much of one there may be, in time,
        // per flow. See [FlowCredit].
        self.credit.reserve(data.len()).await;
        // <<< AETHER-APP-PATCH netstack-uplink-admission
        self.data_in
            .send(DataIn::Tcp(self.id, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::TcpClose(self.id)).await;
    }

    pub fn into_split(mut self) -> (TcpSender, mpsc::Receiver<Vec<u8>>) {
        self.split = true;
        (
            TcpSender {
                id: self.id,
                data_in: self.data_in.clone(),
                // >>> AETHER-APP-PATCH netstack-uplink-admission
                credit: self.credit.clone(),
                // <<< AETHER-APP-PATCH netstack-uplink-admission
            },
            std::mem::replace(
                &mut self.from_stack,
                {
                    let (_tx, rx) = mpsc::channel(1);
                    rx
                },
            ),
        )
    }
}

impl Drop for TcpConn {
    fn drop(&mut self) {
        if !self.split {
            let _ = self.data_in.try_send(DataIn::TcpClose(self.id));
        }
    }
}

pub struct TcpSender {
    id: usize,
    data_in: mpsc::Sender<DataIn>,
    // >>> AETHER-APP-PATCH netstack-uplink-admission
    credit: Arc<FlowCredit>,
    // <<< AETHER-APP-PATCH netstack-uplink-admission
}

impl TcpSender {
    pub async fn send(&self, data: Vec<u8>) -> Result<()> {
        // >>> AETHER-APP-PATCH netstack-uplink-admission
        // The one line that bounds the upload path. Everything downstream of
        // here is a queue; this decides how much of one there may be, in time,
        // per flow. See [FlowCredit].
        self.credit.reserve(data.len()).await;
        // <<< AETHER-APP-PATCH netstack-uplink-admission
        self.data_in
            .send(DataIn::Tcp(self.id, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::TcpClose(self.id)).await;
    }
}

impl Drop for TcpSender {
    fn drop(&mut self) {
        let _ = self.data_in.try_send(DataIn::TcpClose(self.id));
    }
}

pub struct UdpConn {
    pub id: usize,
    pub from_stack: mpsc::Receiver<(SocketAddr, Vec<u8>)>,
    data_in: mpsc::Sender<DataIn>,
    split: bool,
}

impl UdpConn {
    pub async fn send_to(&self, dst: SocketAddr, data: Vec<u8>) -> Result<()> {
        self.data_in
            .send(DataIn::Udp(self.id, dst, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::UdpClose(self.id)).await;
    }

    pub fn into_split(mut self) -> (UdpSender, mpsc::Receiver<(SocketAddr, Vec<u8>)>) {
        self.split = true;
        (
            UdpSender {
                id: self.id,
                data_in: self.data_in.clone(),
            },
            std::mem::replace(
                &mut self.from_stack,
                {
                    let (_tx, rx) = mpsc::channel(1);
                    rx
                },
            ),
        )
    }
}

impl Drop for UdpConn {
    fn drop(&mut self) {
        if !self.split {
            let _ = self.data_in.try_send(DataIn::UdpClose(self.id));
        }
    }
}

pub struct UdpSender {
    id: usize,
    data_in: mpsc::Sender<DataIn>,
}

impl UdpSender {
    pub async fn send_to(&self, dst: SocketAddr, data: Vec<u8>) -> Result<()> {
        self.data_in
            .send(DataIn::Udp(self.id, dst, data))
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }

    pub async fn close(&self) {
        let _ = self.data_in.send(DataIn::UdpClose(self.id)).await;
    }
}

impl Drop for UdpSender {
    fn drop(&mut self) {
        let _ = self.data_in.try_send(DataIn::UdpClose(self.id));
    }
}

#[derive(Clone)]
pub struct StackHandle {
    cmd_tx: mpsc::Sender<Cmd>,
}

impl StackHandle {
    pub async fn open_tcp(&self, dst: SocketAddr) -> Result<TcpConn> {
        let (resp_tx, resp_rx) = oneshot::channel();
        self.cmd_tx
            .send(Cmd::OpenTcp { dst, resp: resp_tx, carrier: false })
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))?;
        resp_rx
            .await
            .map_err(|_| AetherError::Other("netstack dropped".into()))?
            .map_err(AetherError::Other)
    }

    /// Like [open_tcp], for a flow that carries another tunnel. See `TcpState::carrier`.
    pub async fn open_tcp_carrier(&self, dst: SocketAddr) -> Result<TcpConn> {
        let (resp_tx, resp_rx) = oneshot::channel();
        self.cmd_tx
            .send(Cmd::OpenTcp { dst, resp: resp_tx, carrier: true })
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))?;
        resp_rx
            .await
            .map_err(|_| AetherError::Other("netstack dropped".into()))?
            .map_err(AetherError::Other)
    }

    pub async fn open_udp(&self) -> Result<UdpConn> {
        let (resp_tx, resp_rx) = oneshot::channel();
        self.cmd_tx
            .send(Cmd::OpenUdp { resp: resp_tx })
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))?;
        resp_rx
            .await
            .map_err(|_| AetherError::Other("netstack dropped".into()))?
            .map_err(AetherError::Other)
    }

    pub async fn set_addrs(
        &self,
        v4: Option<(Ipv4Addr, u8)>,
        v6: Option<(Ipv6Addr, u8)>,
    ) -> Result<()> {
        self.cmd_tx
            .send(Cmd::SetAddrs { v4, v6 })
            .await
            .map_err(|_| AetherError::Other("netstack closed".into()))
    }
}

struct TcpState {
    handle: SocketHandle,
    to_app: mpsc::Sender<Vec<u8>>,
    from_stack_rx: Option<mpsc::Receiver<Vec<u8>>>,
    connect_resp: Option<OpenTcpResp>,
    pending: Vec<u8>,
    established: bool,
    half_closed: bool,
    // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    /// When an unanswered connect gives up. See [TcpLimits::connect].
    connect_deadline: std::time::Instant,
    /// When the app side went away, for the orphan linger.
    orphaned_at: Option<std::time::Instant>,
    /// Set once this flow has been reset; the next service pass reaps it.
    aborted: bool,
    /// 1.4.0 MIM fix: this flow CARRIES another tunnel (the inner MASQUE hop of
    /// masque-in-masque). Its liveness is owned by that tunnel's own h2
    /// keepalive, so the drain-stall / flap reaper must never reset it: doing so
    /// is what tore the whole MIM session down (issue #67).
    carrier: bool,
    // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    /// Last time this flow actually swallowed some of its pending bytes. Used
    /// to tell "slow" from "wedged" (see [TCP_WEDGE_TIMEOUT]).
    ///
    /// NOTE (1.2.8-r7): this tracks bytes ACCEPTED INTO the send buffer, which is
    /// not evidence the peer is alive. See [TCP_DRAIN_STALL_TIMEOUT] and
    /// `last_drain` below for the signal that is.
    last_progress: std::time::Instant,
    // >>> AETHER-APP-PATCH netstack-drain-liveness
    /// `send_queue()` as of the last service pass. Only used to answer "does this
    /// flow currently have anything outstanding", never as a progress signal -
    /// see `acked_high` for why that distinction matters.
    send_queue_high: usize,
    /// Cumulative bytes this flow has handed to its socket since it opened.
    accepted_total: u64,
    /// Cumulative bytes the PEER has acknowledged, derived as
    /// `accepted_total - send_queue()`.
    ///
    /// This, and not the queue level, is the progress signal. The obvious
    /// implementation - "did `send_queue()` go down" - has a false positive that
    /// would be far worse than the bug it fixes: a saturated upload sits pinned
    /// AT the send-buffer limit, so its queue reads exactly 131072 on every pass
    /// while draining perfectly, and a naive check would reset the one flow that
    /// is working hardest. Bytes acknowledged is monotonic and can only advance
    /// when the far end actually receives data, so it cannot confuse "full" with
    /// "dead".
    acked_high: u64,
    /// Last time this socket's send queue shrank, i.e. the last time the far end
    /// demonstrably received something. The only real liveness signal available
    /// in this stack - see [TCP_DRAIN_STALL_TIMEOUT].
    last_drain: std::time::Instant,
    /// Set once this flow has been reported as non-draining, so a stuck flow is
    /// logged when it starts and when it is reset, not once per service pass.
    drain_warned: bool,
    // <<< AETHER-APP-PATCH netstack-drain-liveness
    // >>> AETHER-APP-PATCH netstack-uplink-admission
    /// The admission gate this flow's writer waits on. See [FlowCredit].
    credit: Arc<FlowCredit>,
    /// Start of the current drain-rate sample, and `acked` as it was then.
    rate_mark: std::time::Instant,
    rate_base: u64,
    /// Smoothed bytes/second the PEER is acknowledging on this flow. The only
    /// rate in this process that is measured rather than assumed.
    drain_rate: f64,
    /// The budget published to [FlowCredit] on the last pass, in bytes.
    uplink_budget: usize,
    /// Window high-water marks. Everything else here is a point sample read at
    /// the end of a 15 s window, which is precisely how ~8 s of queue stayed
    /// invisible through six rounds of telemetry: the spike lives between
    /// samples. These are maxima, and they are reset when they are reported.
    outstanding_peak: usize,
    pending_peak: usize,
    send_queue_peak: usize,
    /// Drain stalls seen inside the current [STALL_FLAP_WINDOW], and when the
    /// last one started.
    stall_count: u32,
    stall_mark: std::time::Instant,
    // <<< AETHER-APP-PATCH netstack-uplink-admission
}

struct UdpState {
    handle: SocketHandle,
    to_app: mpsc::Sender<(SocketAddr, Vec<u8>)>,
}

pub struct NetStack {
    iface: Interface,
    device: StackDevice,
    sockets: SocketSet<'static>,
    tcp_conns: HashMap<usize, TcpState>,
    udp_conns: HashMap<usize, UdpState>,
    next_id: usize,
    next_port: u16,
    data_in_tx: mpsc::Sender<DataIn>,
    // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    tcp_limits: TcpLimits,
    // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes
}

fn data_in_id(d: &DataIn) -> usize {
    match d {
        DataIn::Tcp(id, _) | DataIn::TcpClose(id) | DataIn::Udp(id, _, _) | DataIn::UdpClose(id) => {
            *id
        }
    }
}

fn data_in_bytes(d: &DataIn) -> usize {
    match d {
        DataIn::Tcp(_, data) => data.len(),
        DataIn::Udp(_, _, data) => data.len(),
        _ => 0,
    }
}

/// App-to-network data that a flow could not take yet.
///
/// The queue is global but ordered PER FLOW: as long as a flow has anything
/// waiting here, everything else for that flow queues behind it (a TCP stream
/// may never be reordered), while every other flow is served normally. That
/// distinction is the whole fix - see [MAX_BACKLOG_BYTES].
#[derive(Default)]
struct Backlog {
    queue: VecDeque<DataIn>,
    blocked: HashSet<usize>,
    bytes: usize,
}

impl Backlog {
    fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }

    fn holds(&self, id: usize) -> bool {
        self.blocked.contains(&id)
    }

    fn push(&mut self, d: DataIn) {
        self.bytes = self.bytes.saturating_add(data_in_bytes(&d));
        self.blocked.insert(data_in_id(&d));
        self.queue.push_back(d);
    }

    fn purge(&mut self, id: usize) {
        self.queue.retain(|d| data_in_id(d) != id);
        self.blocked.remove(&id);
        self.bytes = self.queue.iter().map(|d| data_in_bytes(d)).sum();
    }
}

/// Places one datagram/chunk, queueing it behind the same flow's backlog when
/// that flow already has something waiting.
fn ingest(s: &mut NetStack, backlog: &mut Backlog, d: DataIn) {
    if backlog.holds(data_in_id(&d)) {
        backlog.push(d);
        return;
    }
    if let Some(back) = try_handle_data(s, d) {
        backlog.push(back);
    }
}

/// One pass over the backlog. A flow that refuses its head entry keeps the rest
/// of its own entries queued; every other flow still drains.
fn drain_backlog(s: &mut NetStack, backlog: &mut Backlog) {
    if backlog.queue.is_empty() {
        return;
    }

    let mut kept: VecDeque<DataIn> = VecDeque::with_capacity(backlog.queue.len());
    let mut blocked: HashSet<usize> = HashSet::new();
    let mut bytes = 0usize;

    while let Some(d) = backlog.queue.pop_front() {
        let id = data_in_id(&d);
        if blocked.contains(&id) {
            bytes = bytes.saturating_add(data_in_bytes(&d));
            kept.push_back(d);
            continue;
        }
        if let Some(back) = try_handle_data(s, d) {
            blocked.insert(id);
            bytes = bytes.saturating_add(data_in_bytes(&back));
            kept.push_back(back);
        }
    }

    backlog.queue = kept;
    backlog.blocked = blocked;
    backlog.bytes = bytes;
}

/// Resets flows that have accepted nothing for [TCP_WEDGE_TIMEOUT], so a single
/// black-holed connection can neither pin memory nor (with the per-flow backlog
/// above) hold the tunnel's memory budget hostage.
fn reap_wedged(s: &mut NetStack, backlog: &mut Backlog) {
    // >>> AETHER-APP-PATCH netstack-drain-liveness
    // Two independent ways a flow can be dead, and r2-r6 only ever checked the
    // first one - which is why a dead flow ran for 45 s without a single line in
    // the log. See [TCP_DRAIN_STALL_TIMEOUT] for the full story.
    //
    //   1. It will not TAKE bytes from us      -> `backlog.blocked` + last_progress
    //   2. It takes them and the peer never    -> send_queue never shrinks
    //      acknowledges any of them              (`last_drain`)
    //
    // Case 2 is the one that hurts: the flow looks completely healthy from
    // inside this process, because it IS healthy from inside this process. The
    // bytes are gone into a socket buffer and the far end is not there.
    //
    // This runs even when `backlog.blocked` is empty - the early return that
    // used to be here is exactly what made case 2 unreachable.
    let stalled: Vec<usize> = s
        .tcp_conns
        .iter()
        .filter(|(_, st)| {
            !st.carrier
                && st.send_queue_high > 0
                && st.last_drain.elapsed() >= TCP_DRAIN_STALL_TIMEOUT
        })
        .map(|(id, _)| *id)
        .collect();

    for id in stalled {
        if let Some(st) = s.tcp_conns.get_mut(&id) {
            log::warn!(
                "[netstack] flow {id} held {} bytes with nothing acknowledged for {:?}; \
                 the peer is gone. Resetting it so the app is told now instead of waiting \
                 on a dead path.",
                st.send_queue_high,
                st.last_drain.elapsed(),
            );
            st.pending.clear();
            st.pending.shrink_to_fit();
            // >>> AETHER-APP-PATCH netstack-uplink-admission
            st.credit.close();
            // <<< AETHER-APP-PATCH netstack-uplink-admission
            let handle = st.handle;
            // RST + Closed. service_tcp reaps it on the next pass and the app
            // side sees the flow end, so it can retry over a live path instead of
            // waiting out a socket that will never move again.
            s.sockets.get_mut::<tcp::Socket>(handle).abort();
        }
        backlog.purge(id);
    }
    // <<< AETHER-APP-PATCH netstack-drain-liveness

    if backlog.blocked.is_empty() {
        return;
    }

    let wedged: Vec<usize> = backlog
        .blocked
        .iter()
        .copied()
        .filter(|id| {
            s.tcp_conns
                .get(id)
                .map(|st| !st.carrier && st.last_progress.elapsed() >= TCP_WEDGE_TIMEOUT)
                .unwrap_or(true)
        })
        .collect();

    for id in wedged {
        if let Some(st) = s.tcp_conns.get_mut(&id) {
            log::warn!(
                "[netstack] flow {id} took nothing for {:?}; resetting it so the tunnel keeps moving",
                TCP_WEDGE_TIMEOUT
            );
            st.pending.clear();
            st.pending.shrink_to_fit();
            // >>> AETHER-APP-PATCH netstack-uplink-admission
            st.credit.close();
            // <<< AETHER-APP-PATCH netstack-uplink-admission
            let handle = st.handle;
            // abort() emits a RST and moves the socket to Closed; service_tcp
            // then reaps it and the app side sees the flow end.
            s.sockets.get_mut::<tcp::Socket>(handle).abort();
        }
        backlog.purge(id);
    }
}

fn strip_cidr(s: &str) -> &str {
    match s.split_once('/') {
        Some((ip, _)) => ip,
        None => s,
    }
}

fn to_ip_address(ip: IpAddr) -> IpAddress {
    match ip {
        IpAddr::V4(v4) => IpAddress::Ipv4(Ipv4Address::from(v4)),
        IpAddr::V6(v6) => IpAddress::Ipv6(Ipv6Address::from(v6)),
    }
}

fn to_ip_endpoint(addr: SocketAddr) -> IpEndpoint {
    IpEndpoint::new(to_ip_address(addr.ip()), addr.port())
}

fn cidr_prefix(s: &str) -> Option<u8> {
    s.split_once('/').and_then(|(_, p)| p.parse().ok())
}

fn parse_v4(s: &str) -> Result<Option<(Ipv4Addr, u8)>> {
    if s.is_empty() {
        return Ok(None);
    }
    let ip: Ipv4Addr = strip_cidr(s)
        .parse()
        .map_err(|_| AetherError::Other(format!("bad ipv4 {s}")))?;
    Ok(Some((ip, cidr_prefix(s).unwrap_or(32))))
}

fn parse_v6(s: &str) -> Result<Option<(Ipv6Addr, u8)>> {
    if s.is_empty() {
        return Ok(None);
    }
    let ip: Ipv6Addr = strip_cidr(s)
        .parse()
        .map_err(|_| AetherError::Other(format!("bad ipv6 {s}")))?;
    Ok(Some((ip, cidr_prefix(s).unwrap_or(128))))
}

fn routable_prefix_v4(p: u8) -> u8 {
    if p >= 31 {
        24
    } else {
        p
    }
}

fn routable_prefix_v6(p: u8) -> u8 {
    if p >= 127 {
        64
    } else {
        p
    }
}

fn apply_addrs(
    iface: &mut Interface,
    v4: Option<(Ipv4Addr, u8)>,
    v6: Option<(Ipv6Addr, u8)>,
) {
    iface.update_ip_addrs(|addrs| {
        addrs.clear();
        if let Some((ip, p)) = v4 {
            let _ = addrs.push(IpCidr::new(
                IpAddress::Ipv4(Ipv4Address::from(ip)),
                routable_prefix_v4(p),
            ));
        }
        if let Some((ip, p)) = v6 {
            let _ = addrs.push(IpCidr::new(
                IpAddress::Ipv6(Ipv6Address::from(ip)),
                routable_prefix_v6(p),
            ));
        }
    });

    if let Some((ip, _)) = v4 {
        let o = ip.octets();
        let host = if o[3] == 1 { 2 } else { 1 };
        let gw = Ipv4Address::new(o[0], o[1], o[2], host);
        let _ = iface.routes_mut().add_default_ipv4_route(gw);
    }
    if let Some((ip, _)) = v6 {
        let mut o = ip.octets();
        o[15] = if o[15] == 1 { 2 } else { 1 };
        let _ = iface
            .routes_mut()
            .add_default_ipv6_route(Ipv6Address::from(o));
    }
}

// >>> AETHER-CORE-PORT 2.0.0 addr-merge
type AddrPair = (Option<(Ipv4Addr, u8)>, Option<(Ipv6Addr, u8)>);

fn current_addrs(iface: &Interface) -> AddrPair {
    let mut v4 = None;
    let mut v6 = None;
    for cidr in iface.ip_addrs() {
        match cidr {
            IpCidr::Ipv4(c) => v4 = Some((c.address(), c.prefix_len())),
            IpCidr::Ipv6(c) => v6 = Some((c.address(), c.prefix_len())),
        }
    }
    (v4, v6)
}
// <<< AETHER-CORE-PORT 2.0.0 addr-merge

fn endpoint_to_socketaddr(ep: IpEndpoint) -> SocketAddr {
    let ip = match ep.addr {
        IpAddress::Ipv4(v4) => IpAddr::V4(v4.into()),
        IpAddress::Ipv6(v6) => IpAddr::V6(v6.into()),
    };
    SocketAddr::new(ip, ep.port)
}

pub fn spawn(
    ipv4: &str,
    ipv6: &str,
    mtu: usize,
    inbound_rx: mpsc::Receiver<Vec<u8>>,
    outbound_tx: mpsc::Sender<Vec<u8>>,
) -> Result<StackHandle> {
    // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    spawn_with_limits(
        ipv4,
        ipv6,
        mtu,
        inbound_rx,
        outbound_tx,
        TcpLimits::from_env(),
    )
}

/// Same as [spawn], with the TCP lifetimes given explicitly. Core 2.0.0 added
/// this so the timeouts can be driven from a test instead of from the clock.
fn spawn_with_limits(
    ipv4: &str,
    ipv6: &str,
    mtu: usize,
    inbound_rx: mpsc::Receiver<Vec<u8>>,
    outbound_tx: mpsc::Sender<Vec<u8>>,
    tcp_limits: TcpLimits,
) -> Result<StackHandle> {
    // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    let mut device = StackDevice::new(mtu);

    let config = Config::new(HardwareAddress::Ip);
    let mut iface = Interface::new(config, &mut device, Instant::now());

    let v4 = parse_v4(ipv4)?;
    let v6 = parse_v6(ipv6)?;
    apply_addrs(&mut iface, v4, v6);

    let (cmd_tx, cmd_rx) = mpsc::channel(256);
    let (data_in_tx, data_in_rx) = mpsc::channel(app_queue());

    let stack = NetStack {
        iface,
        device,
        sockets: SocketSet::new(Vec::new()),
        tcp_conns: HashMap::new(),
        udp_conns: HashMap::new(),
        next_id: 1,
        next_port: 49152,
        data_in_tx: data_in_tx.clone(),
        // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
        tcp_limits,
        // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    };

    tokio::spawn(run(stack, cmd_rx, data_in_rx, inbound_rx, outbound_tx));

    Ok(StackHandle { cmd_tx })
}

fn alloc_port(p: &mut u16) -> u16 {
    let port = *p;
    *p = if port >= 65000 { 49152 } else { port + 1 };
    port
}

/// Bounded, non-blocking drain of the network->app queue.
///
/// Returns `(moved, closed)`. Never awaits, so it cannot be the reason another
/// direction waits.
fn drain_inbound(
    s: &mut NetStack,
    rx: &mut mpsc::Receiver<Vec<u8>>,
    budget: usize,
) -> (usize, bool) {
    let mut moved = 0;
    while moved < budget {
        match rx.try_recv() {
            Ok(pkt) => {
                s.device.rx.push_back(pkt);
                moved += 1;
            }
            Err(mpsc::error::TryRecvError::Empty) => return (moved, false),
            Err(mpsc::error::TryRecvError::Disconnected) => return (moved, true),
        }
    }
    (moved, false)
}

/// Bounded, non-blocking drain of the app->network queue.
///
/// Stops early on the backlog's memory bound rather than on anything to do with
/// the other direction - that coupling was the bug.
fn drain_app_data(
    s: &mut NetStack,
    backlog: &mut Backlog,
    rx: &mut mpsc::Receiver<DataIn>,
    budget: usize,
) -> (usize, bool) {
    let mut moved = 0;
    while moved < budget && backlog.bytes < MAX_BACKLOG_BYTES {
        match rx.try_recv() {
            Ok(d) => {
                ingest(s, backlog, d);
                moved += 1;
            }
            Err(mpsc::error::TryRecvError::Empty) => return (moved, false),
            Err(mpsc::error::TryRecvError::Disconnected) => return (moved, true),
        }
    }
    (moved, false)
}

/// Bounded, non-blocking drain of the control queue.
fn drain_cmds(
    s: &mut NetStack,
    rx: &mut mpsc::Receiver<Cmd>,
    budget: usize,
) -> (usize, bool) {
    let mut moved = 0;
    while moved < budget {
        match rx.try_recv() {
            Ok(cmd) => {
                handle_cmd(s, cmd);
                moved += 1;
            }
            Err(mpsc::error::TryRecvError::Empty) => return (moved, false),
            Err(mpsc::error::TryRecvError::Disconnected) => return (moved, true),
        }
    }
    (moved, false)
}

async fn run(
    mut s: NetStack,
    mut cmd_rx: mpsc::Receiver<Cmd>,
    mut data_in_rx: mpsc::Receiver<DataIn>,
    mut inbound_rx: mpsc::Receiver<Vec<u8>>,
    outbound_tx: mpsc::Sender<Vec<u8>>,
) -> Result<()> {
    let mut backlog = Backlog::default();
    let mut tx_dropped: usize = 0;
    let mut next_drop_report: usize = DROP_REPORT_STEP;
    // Which direction is visited first this pass; flipped every pass so neither
    // can be starved by the other. See the scheduling block below.
    let mut prefer_app_data = false;
    let mut last_app_progress = std::time::Instant::now();
    let mut last_starvation_report = std::time::Instant::now()
        .checked_sub(STARVATION_REPORT_GAP)
        .unwrap_or_else(std::time::Instant::now);

    // 1.2.8-r4 DATA-PLANE TELEMETRY.
    //
    // The reason this bug survived five rounds of analysis is that nobody has
    // ever seen the data plane. In the last field log the engine emitted its
    // final line at second 51 of a six-minute session and then went completely
    // silent, while 393 of the 478 lines were Psiphon server-list bookkeeping.
    // Every diagnosis had to be inferred from side effects, and two of them were
    // wrong because of it.
    //
    // One line every [TELEMETRY_INTERVAL], at INFO, carrying the numbers that
    // actually decide whether this stack is healthy: how many flows exist, how
    // much app data is backed up, and - above all - how many outbound packets
    // were TAIL-DROPPED. That last counter is the fingerprint of an unthrottled
    // sender: with congestion control in place it should stay at or near zero,
    // and if it climbs, the next log says so in one line instead of leaving it
    // to be guessed at.
    // >>> AETHER-APP-PATCH netstack-device-backpressure
    // Backdated so the first report lands at TELEMETRY_FIRST instead of a full
    // interval in. See the constant for why that mattered in the field.
    let mut last_telemetry = std::time::Instant::now()
        .checked_sub(TELEMETRY_INTERVAL.saturating_sub(TELEMETRY_FIRST))
        .unwrap_or_else(std::time::Instant::now);
    // <<< AETHER-APP-PATCH netstack-device-backpressure
    let mut win_in = 0usize;
    let mut win_app = 0usize;
    let mut win_dropped = 0usize;

    loop {
        let now = Instant::now();
        let poll_outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            s.iface.poll(now, &mut s.device, &mut s.sockets);
        }));
        if poll_outcome.is_err() {
            s.device.rx.clear();
            s.device.tx.clear();
        }
        let tcp_busy = service_tcp(&mut s);
        let udp_busy = service_udp(&mut s);
        let (dropped, tx_busy) = flush_tx(&mut s, &outbound_tx);

        if dropped > 0 {
            tx_dropped = tx_dropped.saturating_add(dropped);
            if tx_dropped >= next_drop_report {
                next_drop_report = tx_dropped + DROP_REPORT_STEP;
                log::debug!("[netstack] dropped {tx_dropped} outbound packets under pressure");
            }
        }

        drain_backlog(&mut s, &mut backlog);
        reap_wedged(&mut s, &mut backlog);

        // ------------------------------------------------------------------
        // 1.2.8-r2 STARVATION FIX  (the "ping 2000, nothing moves" root cause)
        //
        // ## What was wrong
        //
        // The loop ran ONE `tokio::select!` per pass, and it was `biased`, so
        // the arms were polled strictly in order:
        //
        //   1. inbound_rx   (network -> app: everything being downloaded)
        //   2. cmd_rx       (open a flow, close it, resolve a name)
        //   3. data_in_rx   (app -> network: EVERYTHING being uploaded)
        //
        // `biased` means arm 2 is only polled when arm 1 is not ready. While any
        // sustained download is in progress the WireGuard reader keeps
        // `inbound_rx` non-empty at all times, so arm 1 was ready on every
        // single pass and arms 2 and 3 were NEVER REACHED. The upload direction
        // and new-flow setup were starved for as long as data kept arriving.
        //
        // That is the whole report, in one line of code:
        //   * a live dubbing session is a permanently two-way flow - it uploads
        //     ~44 KB/s of PCM while it downloads ~64 KB/s of dubbed audio. The
        //     download half kept the loop busy, the upload half never got a
        //     turn, the WebSocket write queue filled, and the RTT went to the
        //     roof. Field log: 115 MB received against 6 MB sent.
        //   * a video does the same thing with its ACK/upload path, which is why
        //     "ping jumps to 2000 and then nothing opens" also happened on plain
        //     YouTube;
        //   * the latency badge measures a NEW SOCKS5 connection, which needs
        //     `cmd_rx` (arm 2) AND `data_in_rx` (arm 3): both starved, hence
        //     "Latency probe failed: Connect timed out" while the tunnel was
        //     still moving 100 KB/s of download;
        //   * it recovers on its own the moment the download stops, because the
        //     starved arms are finally polled - which is exactly the "press stop
        //     and the ping gradually comes back" the user described, and the
        //     reason nothing anywhere logged an error.
        //
        // ## The fix
        //
        // Every queue is drained on every pass, each with its OWN budget, and
        // nothing is awaited while any of them still has work. The order the
        // three are visited in alternates, so no direction can be first (or
        // last) twice in a row. `select!` is now only reached when all three
        // queues are empty, i.e. when it is a plain "wake me up" and there is
        // nothing left to be unfair about.
        // ------------------------------------------------------------------
        let mut moved_in = 0usize;
        let mut moved_app = 0usize;
        let mut moved_cmd = 0usize;
        let mut closed = false;

        for pass in 0..2u8 {
            let app_first = (prefer_app_data as u8) == pass;
            if app_first {
                let (n, done) = drain_app_data(
                    &mut s,
                    &mut backlog,
                    &mut data_in_rx,
                    MAX_APP_INGEST_PER_TICK,
                );
                moved_app += n;
                closed |= done;
            } else {
                let (n, done) = drain_inbound(&mut s, &mut inbound_rx, MAX_INGEST_PER_TICK);
                moved_in += n;
                closed |= done;
            }
        }
        {
            let (n, done) = drain_cmds(&mut s, &mut cmd_rx, MAX_CMD_PER_TICK);
            moved_cmd += n;
            closed |= done;
        }
        prefer_app_data = !prefer_app_data;

        if closed {
            // A closed queue means the engine is shutting down, exactly as the
            // old `None => return Ok(())` arms did.
            return Ok(());
        }

        // Starvation telemetry. If this ever fires again the log says so in one
        // line instead of leaving the next reader to infer it from a byte ratio.
        if moved_app > 0 {
            last_app_progress = std::time::Instant::now();
        } else if moved_in >= STARVATION_MIN_INBOUND
            && !backlog.is_empty()
            && last_app_progress.elapsed() >= STARVATION_REPORT_AFTER
            && last_starvation_report.elapsed() >= STARVATION_REPORT_GAP
        {
            last_starvation_report = std::time::Instant::now();
            log::warn!(
                "[netstack] app->network idle for {:?} while {} packets arrived; \
                 backlog {} bytes over {} blocked flows",
                last_app_progress.elapsed(),
                moved_in,
                backlog.bytes,
                backlog.blocked.len()
            );
        }

        win_in = win_in.saturating_add(moved_in);
        win_app = win_app.saturating_add(moved_app);
        win_dropped = win_dropped.saturating_add(dropped);
        if last_telemetry.elapsed() >= TELEMETRY_INTERVAL {
            // >>> AETHER-APP-PATCH netstack-device-backpressure
            // 1.2.8-r5 adds the two numbers that decide whether the congestion
            // controller is being told the truth:
            //
            //   backpressure  - `transmit()` refusals. HEALTHY. Non-zero under
            //                   load is the fix working: the segment stayed in
            //                   the socket buffer where CUBIC can still see it.
            //                   1.2.8-r6 NOTE: in r5 this read 0 for a whole
            //                   13-minute session under a saturating uplink,
            //                   which was not health - it was proof the chain
            //                   was cut below this stack, in a 7 MB kernel send
            //                   buffer. See upstream::tune_udp_buffers.
            //   tail-drops    - packets shed at the hard ceiling. Must stay 0.
            //                   Anything else is loss the controller cannot
            //                   observe, which is the r5 root cause returning.
            //
            // Read them together: backpressure climbing while tail-drops stay at
            // zero is a correctly throttled uplink, and that is the shape a good
            // log has during live dubbing.
            //
            // 1.2.8-r6 adds the one number that was missing every round: how
            // much data is sitting in smoltcp's own SEND buffers. `backlog` is
            // what has not been handed to a socket yet and it was always 0;
            // `send_queue` is what a socket has accepted and not yet got on the
            // wire, which is where a throttled uplink parks its queue. Reading
            // the two together is what distinguishes "nothing to send" from
            // "cannot send", and r5 could not tell them apart.
            let (sock_send_queued, sock_send_worst) = {
                let handles: Vec<SocketHandle> =
                    s.tcp_conns.values().map(|st| st.handle).collect();
                let mut total = 0usize;
                let mut worst = 0usize;
                for handle in handles {
                    let queued = s.sockets.get_mut::<tcp::Socket>(handle).send_queue();
                    total = total.saturating_add(queued);
                    if queued > worst {
                        worst = queued;
                    }
                }
                (total, worst)
            };
            // >>> AETHER-APP-PATCH netstack-drain-liveness
            // r6's `socket send-queue` proved bytes were parked somewhere, but a
            // parked queue on a saturated upload and a parked queue on a dead peer
            // print the identical number. This is the number that separates them:
            // the longest any flow has gone with NOTHING acknowledged. On a healthy
            // tunnel it stays within a couple of RTTs no matter how deep the queue
            // is; past [TCP_DRAIN_STALL_TIMEOUT] the flow gets reset.
            let (worst_stall, stalled_flows) = {
                let mut worst = std::time::Duration::ZERO;
                let mut count = 0usize;
                for st in s.tcp_conns.values() {
                    if st.send_queue_high == 0 {
                        continue;
                    }
                    let idle = st.last_drain.elapsed();
                    if idle >= TCP_DRAIN_STALL_WARN {
                        count += 1;
                    }
                    if idle > worst {
                        worst = idle;
                    }
                }
                (worst, count)
            };
            // <<< AETHER-APP-PATCH netstack-drain-liveness
            // >>> AETHER-APP-PATCH netstack-uplink-admission
            // r2-r7 all printed point samples taken at the end of the window, and
            // that is how ~8 s of standing queue stayed invisible for six rounds:
            // `socket send-queue 3792 bytes` was logged in the same second a probe
            // measured 4803 ms. These are the maxima seen ANYWHERE in the window,
            // for the queues that actually hold an upload, plus the budget the
            // admission gate is enforcing. If the badge ever spikes again, the
            // line next to it now says whether the queue was ours.
            let (out_peak, pend_peak, sq_peak, budget_now) = {
                let mut out_peak = 0usize;
                let mut pend_peak = 0usize;
                let mut sq_peak = 0usize;
                let mut budget_now = 0usize;
                for st in s.tcp_conns.values_mut() {
                    out_peak = out_peak.max(st.outstanding_peak);
                    pend_peak = pend_peak.max(st.pending_peak);
                    sq_peak = sq_peak.max(st.send_queue_peak);
                    budget_now = budget_now.max(st.uplink_budget);
                    st.outstanding_peak = 0;
                    st.pending_peak = 0;
                    st.send_queue_peak = 0;
                }
                (out_peak, pend_peak, sq_peak, budget_now)
            };
            // <<< AETHER-APP-PATCH netstack-uplink-admission
            log::info!(
                "[netstack] {:?} window: tcp flows={} udp flows={} | app->net {} pkts, net->app {} pkts \
                 | backlog {} bytes over {} flows | socket send-queue {} bytes (worst flow {}) \
                 | unacked-for {:?} (worst flow), {} flow(s) not draining \
                 | uplink peaks: outstanding {} B, pending {} B, socket {} B, budget {} B \
                 | device queue peak {}/{} pkts, backpressure {} \
                 | outbound tail-drops {} (this window) / {} (session)",
                last_telemetry.elapsed(),
                s.tcp_conns.len(),
                s.udp_conns.len(),
                win_app,
                win_in,
                backlog.bytes,
                backlog.blocked.len(),
                sock_send_queued,
                sock_send_worst,
                worst_stall,
                stalled_flows,
                // >>> AETHER-APP-PATCH netstack-uplink-admission
                out_peak,
                pend_peak,
                sq_peak,
                budget_now,
                // <<< AETHER-APP-PATCH netstack-uplink-admission
                s.device.tx_peak,
                MAX_DEVICE_TX,
                s.device.tx_stalls,
                win_dropped,
                tx_dropped,
            );
            if s.device.tx_shed > 0 {
                log::warn!(
                    "[netstack] {} outbound packets were shed at the hard ceiling this session - \
                     that is loss the congestion controller cannot see. Send this log.",
                    s.device.tx_shed,
                );
            }
            s.device.tx_peak = s.device.tx.len();
            s.device.tx_stalls = 0;
            // <<< AETHER-APP-PATCH netstack-device-backpressure
            last_telemetry = std::time::Instant::now();
            win_in = 0;
            win_app = 0;
            win_dropped = 0;
        }

        let busy = tcp_busy || udp_busy || tx_busy || !backlog.is_empty();
        let progressed = moved_in > 0 || moved_app > 0 || moved_cmd > 0;

        if progressed {
            // There was real work this pass; go straight back to poll() instead
            // of parking. Yielding keeps this task from monopolising the runtime
            // thread while it does.
            tokio::task::yield_now().await;
            continue;
        }

        let delay = if busy {
            Some(BACKPRESSURE_RETRY)
        } else {
            let polled = s
                .iface
                .poll_delay(Instant::now(), &s.sockets)
                .map(|d| std::time::Duration::from_micros(d.total_micros()));

            if s.tcp_conns.is_empty() && s.udp_conns.is_empty() {
                polled
            } else {
                Some(polled.map_or(MAX_IDLE_TICK, |d| d.min(MAX_IDLE_TICK)))
            }
        };

        // Every queue is empty at this point, so this select is a pure wake-up:
        // whichever queue receives something first wins, and the drains above
        // do the actual work on the next pass. No `biased`, and no arm can hide
        // another one any more.
        tokio::select! {
            maybe = inbound_rx.recv() => {
                match maybe {
                    Some(pkt) => s.device.rx.push_back(pkt),
                    None => return Ok(()),
                }
            }

            maybe = cmd_rx.recv() => {
                match maybe {
                    Some(cmd) => handle_cmd(&mut s, cmd),
                    None => return Ok(()),
                }
            }

            // The only remaining guard is a pure memory bound (see
            // [MAX_BACKLOG_BYTES]); it is never gated on another flow.
            maybe = data_in_rx.recv(), if backlog.bytes < MAX_BACKLOG_BYTES => {
                match maybe {
                    Some(d) => {
                        // 1.2.8-r3: THIS ARM MOVES APP->NETWORK DATA, so it has
                        // to count as progress for that direction.
                        //
                        // It did not, and that is the whole reason r2's
                        // starvation warning cried wolf. On any tunnel that is
                        // not saturated every wake-up arrives here, this arm
                        // empties the queue, and the drains at the top of the
                        // next pass therefore score `moved_app = 0` - so
                        // `last_app_progress` was only ever refreshed by the
                        // drains and aged forever on a session that was working
                        // perfectly. Combined with the (now fixed) `moved_in > 0`
                        // trigger, one inbound packet was enough to publish a
                        // 32-second stall that did not exist.
                        last_app_progress = std::time::Instant::now();
                        ingest(&mut s, &mut backlog, d);
                    }
                    None => return Ok(()),
                }
            }

            _ = sleep_opt(delay) => {}
        }
    }
}

async fn sleep_opt(delay: Option<std::time::Duration>) {
    match delay {
        Some(d) => tokio::time::sleep(d).await,
        None => std::future::pending::<()>().await,
    }
}

fn handle_cmd(s: &mut NetStack, cmd: Cmd) {
    match cmd {
        Cmd::OpenTcp { dst, resp, carrier } => {
            let rx_buf = tcp::SocketBuffer::new(vec![0u8; tcp_rx_buf()]);
            let tx_buf = tcp::SocketBuffer::new(vec![0u8; tcp_tx_buf()]);
            let mut socket = tcp::Socket::new(rx_buf, tx_buf);
            socket.set_nagle_enabled(false);

            // ==============================================================
            // 1.2.8-r4 THE ROOT CAUSE. Give this sender a congestion window.
            //
            // Until this line existed, every flow the device uploaded through
            // the tunnel ran on smoltcp's `NoControl` controller - because
            // `Cargo.toml` set `default-features = false` and never re-enabled
            // `socket-tcp-cubic`, which is one of smoltcp's defaults. NoControl
            // is not a conservative controller, it is the ABSENCE of one: no
            // slow start, no congestion window, no reduction on loss. The
            // sender writes as fast as the peer's advertised window allows and
            // answers congestion by retransmitting harder.
            //
            // Aether TERMINATES TCP here, so this stack - not the phone's
            // kernel - owns congestion control for everything the user uploads.
            // Handing that job to nobody is why a sustained upload (live
            // dubbing is the purest example: ~44 KB/s of PCM, continuously)
            // drove the RTT to 2000 ms within a second and held it there,
            // while a pure download over the same tunnel looked healthy
            // because the REMOTE end was pacing that direction properly.
            //
            // It is also, precisely, why "another VPN has no problem": every
            // other VPN forwards packets and lets the phone's own CUBIC/BBR do
            // this. We were the only stack in the path with an unthrottled
            // sender.
            //
            // CUBIC rather than Reno: this is a high-RTT, high-loss mobile path
            // and CUBIC's window growth is RTT-independent, which is exactly the
            // regime Reno handles worst. The variant only exists when
            // `socket-tcp-cubic` is enabled, so a future edit that drops the
            // feature FAILS THE BUILD instead of quietly shipping this bug a
            // sixth time.
            // ==============================================================
            socket.set_congestion_control(tcp::CongestionControl::Cubic);
            // 1.2.8: without these a flow whose peer disappears mid-video stays
            // Established forever, retransmitting into nothing and holding its
            // buffers (and its slot in the backlog) for the whole session.
            // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
            // Same numbers as before by default; overridable per device now.
            socket.set_keep_alive(Some(smol_duration(s.tcp_limits.keepalive)));
            socket.set_timeout(Some(smol_duration(s.tcp_limits.dead_peer)));
            // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes

            let local_port = alloc_port(&mut s.next_port);
            let remote = to_ip_endpoint(dst);

            if let Err(e) = socket.connect(s.iface.context(), remote, local_port) {
                let _ = resp.send(Err(format!("connect: {e:?}")));
                return;
            }

            let handle = s.sockets.add(socket);
            let id = s.next_id;
            s.next_id += 1;

            let (to_app_tx, to_app_rx) = mpsc::channel(app_queue());

            s.tcp_conns.insert(
                id,
                TcpState {
                    handle,
                    to_app: to_app_tx,
                    from_stack_rx: Some(to_app_rx),
                    connect_resp: Some(resp),
                    pending: Vec::new(),
                    established: false,
                    half_closed: false,
                    // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
                    connect_deadline: std::time::Instant::now() + s.tcp_limits.connect,
                    orphaned_at: None,
                    aborted: false,
                    carrier,
                    // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes
                    last_progress: std::time::Instant::now(),
                    // >>> AETHER-APP-PATCH netstack-drain-liveness
                    send_queue_high: 0,
                    accepted_total: 0,
                    acked_high: 0,
                    last_drain: std::time::Instant::now(),
                    drain_warned: false,
                    // <<< AETHER-APP-PATCH netstack-drain-liveness
                    // >>> AETHER-APP-PATCH netstack-uplink-admission
                    credit: Arc::new(FlowCredit::new()),
                    rate_mark: std::time::Instant::now(),
                    rate_base: 0,
                    drain_rate: 0.0,
                    uplink_budget: MIN_FLOW_QUEUE_BYTES,
                    outstanding_peak: 0,
                    pending_peak: 0,
                    send_queue_peak: 0,
                    stall_count: 0,
                    stall_mark: std::time::Instant::now(),
                    // <<< AETHER-APP-PATCH netstack-uplink-admission
                },
            );
        }
        Cmd::OpenUdp { resp } => {
            let rx_meta = vec![udp::PacketMetadata::EMPTY; udp_meta()];
            let tx_meta = vec![udp::PacketMetadata::EMPTY; udp_meta()];
            let rx_buf = udp::PacketBuffer::new(rx_meta, vec![0u8; udp_buf()]);
            let tx_buf = udp::PacketBuffer::new(tx_meta, vec![0u8; udp_buf()]);
            let mut socket = udp::Socket::new(rx_buf, tx_buf);

            let local_port = alloc_port(&mut s.next_port);
            if let Err(e) = socket.bind(local_port) {
                let _ = resp.send(Err(format!("bind: {e:?}")));
                return;
            }

            let handle = s.sockets.add(socket);
            let id = s.next_id;
            s.next_id += 1;

            let (to_app_tx, to_app_rx) = mpsc::channel(app_queue());
            s.udp_conns.insert(id, UdpState { handle, to_app: to_app_tx });

            let conn = UdpConn {
                id,
                from_stack: to_app_rx,
                data_in: s.data_in_tx.clone(),
                split: false,
            };
            let _ = resp.send(Ok(conn));
        }
        Cmd::SetAddrs { v4, v6 } => {
            // >>> AETHER-CORE-PORT 2.0.0 addr-merge
            // Core 2.0.0: an edge capsule that carries only one family used to
            // WIPE the other, because apply_addrs() clears the list first. A
            // v4-only capsule therefore took IPv6 off the interface mid-session.
            // Each family is now replaced only when the capsule names it.
            let (current_v4, current_v6) = current_addrs(&s.iface);
            apply_addrs(&mut s.iface, v4.or(current_v4), v6.or(current_v6));
            // <<< AETHER-CORE-PORT 2.0.0 addr-merge
            log::info!("netstack addresses synchronized from edge capsule");
        }
    }
}

/// Returns `Some(d)` when the datagram must be deferred (TCP pending full).
fn try_handle_data(s: &mut NetStack, d: DataIn) -> Option<DataIn> {
    match d {
        DataIn::Tcp(id, data) => {
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                let max = max_tcp_pending();
                if st.pending.len() >= max {
                    return Some(DataIn::Tcp(id, data));
                }
                let space = max - st.pending.len();
                if data.len() <= space {
                    st.pending.extend_from_slice(&data);
                } else {
                    st.pending.extend_from_slice(&data[..space]);
                    return Some(DataIn::Tcp(id, data[space..].to_vec()));
                }
            }
            None
        }
        DataIn::TcpClose(id) => {
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                st.half_closed = true;
            }
            None
        }
        DataIn::Udp(id, dst, data) => {
            if let Some(st) = s.udp_conns.get(&id) {
                let sock = s.sockets.get_mut::<udp::Socket>(st.handle);
                let _ = sock.send_slice(&data, to_ip_endpoint(dst));
            }
            None
        }
        DataIn::UdpClose(id) => {
            if let Some(st) = s.udp_conns.remove(&id) {
                s.sockets.remove(st.handle);
            }
            None
        }
    }
}

fn service_tcp(s: &mut NetStack) -> bool {
    let mut backpressured = false;
    let ids: Vec<usize> = s.tcp_conns.keys().copied().collect();
    // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    let now = std::time::Instant::now();
    // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes

    for id in ids {
        let handle = match s.tcp_conns.get(&id) {
            Some(st) => st.handle,
            None => continue,
        };

        // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
        // A flow reset last pass: drop the socket and the bookkeeping now, which
        // is what actually returns the file descriptor and the port.
        if s.tcp_conns[&id].aborted {
            // >>> AETHER-APP-PATCH netstack-uplink-admission
            s.tcp_conns[&id].credit.close();
            // <<< AETHER-APP-PATCH netstack-uplink-admission
            s.sockets.remove(handle);
            s.tcp_conns.remove(&id);
            continue;
        }
        // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes

        let state = s.sockets.get_mut::<tcp::Socket>(handle).state();
        let data_in_tx = s.data_in_tx.clone();

        // AETHER-CORE-PORT 2.0.0: CloseWait counts as connected too, so a server
        // that answers and immediately half-closes still yields a usable
        // connection instead of one that times out with data waiting on it.
        let connected = matches!(state, tcp::State::Established | tcp::State::CloseWait);
        if !s.tcp_conns[&id].established && connected {
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                st.established = true;
                if let (Some(resp), Some(rx)) = (st.connect_resp.take(), st.from_stack_rx.take()) {
                    let conn = TcpConn {
                        id,
                        from_stack: rx,
                        data_in: data_in_tx.clone(),
                        split: false,
                        // >>> AETHER-APP-PATCH netstack-uplink-admission
                        credit: st.credit.clone(),
                        // <<< AETHER-APP-PATCH netstack-uplink-admission
                    };
                    let _ = resp.send(Ok(conn));
                }
            }
        }

        if !s.tcp_conns[&id].established
            && matches!(state, tcp::State::Closed | tcp::State::TimeWait)
        {
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                if let Some(resp) = st.connect_resp.take() {
                    let _ = resp.send(Err("connection refused".into()));
                }
            }
            // >>> AETHER-APP-PATCH netstack-uplink-admission
            if let Some(st) = s.tcp_conns.get(&id) {
                st.credit.close();
            }
            // <<< AETHER-APP-PATCH netstack-uplink-admission
            s.sockets.remove(handle);
            s.tcp_conns.remove(&id);
            continue;
        }

        // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
        // A connect nobody ever answers used to sit in SynSent for the life of
        // the session, holding a socket and a port, and the caller waited on a
        // oneshot that was never going to be sent. Two exits now: the caller
        // gave up (the response channel is closed), or the deadline passed.
        if !s.tcp_conns[&id].established {
            let st = s.tcp_conns.get_mut(&id).unwrap();
            let abandoned = st.connect_resp.as_ref().is_none_or(|resp| resp.is_closed());
            if abandoned || now >= st.connect_deadline {
                if let Some(resp) = st.connect_resp.take() {
                    let _ = resp.send(Err("connection timed out".into()));
                }
                // >>> AETHER-APP-PATCH netstack-uplink-admission
                st.credit.close();
                // <<< AETHER-APP-PATCH netstack-uplink-admission
                s.sockets.remove(handle);
                s.tcp_conns.remove(&id);
            }
            continue;
        }
        // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes

        {
            let socket = s.sockets.get_mut::<tcp::Socket>(handle);
            let st = s.tcp_conns.get_mut(&id).unwrap();
            if st.pending.is_empty() {
                // Nothing waiting: by definition not wedged.
                st.last_progress = std::time::Instant::now();
            } else if socket.can_send() {
                let sent = socket.send_slice(&st.pending).unwrap_or(0);
                if sent > 0 {
                    st.last_progress = std::time::Instant::now();
                    // AETHER-APP-PATCH netstack-drain-liveness: feeds `acked_high`.
                    st.accepted_total = st.accepted_total.saturating_add(sent as u64);
                    st.pending.drain(0..sent);
                    if st.pending.len() * 4 < st.pending.capacity() {
                        st.pending.shrink_to(max_tcp_pending().min(st.pending.capacity()));
                    }
                }
            }
        }

        // >>> AETHER-APP-PATCH netstack-drain-liveness
        // The liveness check that r2-r6 were all missing. Everything above this
        // point measures what THIS PROCESS did with the bytes; this measures
        // whether the far end ever got them.
        //
        // `send_queue()` is bytes the socket has accepted and not yet had
        // acknowledged. It only goes down when an ACK arrives, so a queue that is
        // non-empty and never shrinks is a peer that is gone - no matter how
        // healthy `pending`, `backlog`, the device queue and the uplink writer all
        // look, and in the r6 log every one of them looked perfect while this sat
        // at 131072 bytes for the better part of a minute.
        // >>> AETHER-APP-PATCH netstack-uplink-admission
        let mut flap_reset = false;
        // <<< AETHER-APP-PATCH netstack-uplink-admission
        {
            let queued = s.sockets.get_mut::<tcp::Socket>(handle).send_queue();
            let st = s.tcp_conns.get_mut(&id).unwrap();
            // Bytes the peer has acknowledged = what we handed the socket minus
            // what it is still holding. Monotonic, and it can only move when the
            // far end really received something.
            let acked = st.accepted_total.saturating_sub(queued as u64);
            if queued == 0 || acked > st.acked_high {
                // Empty, or smaller than last pass: the peer acknowledged
                // something. This is the only branch allowed to call it alive.
                //
                // Read the stall length BEFORE resetting the clock, or the
                // number in the log below is always zero.
                let stalled_for = st.last_drain.elapsed();
                st.last_drain = std::time::Instant::now();
                st.acked_high = acked;
                if st.drain_warned {
                    st.drain_warned = false;
                    log::info!(
                        "[netstack] flow {id} is draining again after {stalled_for:?} ({} bytes still queued); it recovered on its own, no reset needed",
                        queued,
                    );
                }
            } else if !st.drain_warned && st.last_drain.elapsed() >= TCP_DRAIN_STALL_WARN {
                // Report it the moment it looks stuck, long before it is reset,
                // so this failure mode can never again be invisible in a log.
                st.drain_warned = true;
                log::warn!(
                    "[netstack] flow {id} has {} bytes queued and has had NOTHING acknowledged for {:?} - the peer has stopped receiving. Resetting it at {:?} so the tunnel is not held open on a dead path.",
                    queued,
                    st.last_drain.elapsed(),
                    TCP_DRAIN_STALL_TIMEOUT,
                );
                // >>> AETHER-APP-PATCH netstack-uplink-admission
                // A stall that recovers just under the reset deadline is not a
                // recovery, it is a warning shot - see [STALL_FLAP_LIMIT].
                if st.stall_mark.elapsed() >= STALL_FLAP_WINDOW {
                    st.stall_count = 0;
                }
                st.stall_mark = std::time::Instant::now();
                st.stall_count = st.stall_count.saturating_add(1);
                if st.stall_count >= STALL_FLAP_LIMIT && !st.carrier {
                    flap_reset = true;
                }
                // <<< AETHER-APP-PATCH netstack-uplink-admission
            }
            st.send_queue_high = queued;
            // >>> AETHER-APP-PATCH netstack-uplink-admission
            // The drain rate is measured on the same acknowledged-bytes signal
            // r7 introduced, because it is the only rate in this process that a
            // dead path cannot fake. Everything the admission gate does hangs
            // off it.
            let now = std::time::Instant::now();
            let sample_for = now.duration_since(st.rate_mark);
            if sample_for >= RATE_SAMPLE_WINDOW {
                let secs = sample_for.as_secs_f64();
                if secs > 0.0 {
                    let moved = acked.saturating_sub(st.rate_base) as f64;
                    let sample = moved / secs;
                    st.drain_rate = if st.drain_rate <= 0.0 {
                        sample
                    } else {
                        st.drain_rate * 0.5 + sample * 0.5
                    };
                }
                st.rate_mark = now;
                st.rate_base = acked;
            }
            // Never below the floor, never above what the socket could hold
            // anyway - so this can only ever REMOVE standing queue, never grant
            // more than r6 already allowed.
            let ceiling = tcp_tx_buf().max(MIN_FLOW_QUEUE_BYTES);
            let budget = ((st.drain_rate * UPLINK_QUEUE_BUDGET.as_secs_f64()) as usize)
                .clamp(MIN_FLOW_QUEUE_BYTES, ceiling);
            st.uplink_budget = budget;
            st.credit.publish(acked, budget);

            // High-water marks, not point samples. See TcpState::outstanding_peak.
            let outstanding = st.credit.outstanding().min(usize::MAX as u64) as usize;
            if outstanding > st.outstanding_peak {
                st.outstanding_peak = outstanding;
            }
            if st.pending.len() > st.pending_peak {
                st.pending_peak = st.pending.len();
            }
            if queued > st.send_queue_peak {
                st.send_queue_peak = queued;
            }
            // <<< AETHER-APP-PATCH netstack-uplink-admission
        }
        // <<< AETHER-APP-PATCH netstack-drain-liveness

        // >>> AETHER-APP-PATCH netstack-uplink-admission
        if flap_reset {
            log::warn!(
                "[netstack] flow {id} has now stalled {STALL_FLAP_LIMIT} times inside {STALL_FLAP_WINDOW:?} \
                 with nothing acknowledged; resetting it instead of waiting for another self-recovery \
                 that leaves the tunnel connected and useless"
            );
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                st.pending.clear();
                st.pending.shrink_to_fit();
                st.stall_count = 0;
                st.drain_warned = false;
                // Release the writer immediately: it must see the flow end, not
                // wait out an admission budget that will never be refreshed.
                st.credit.close();
            }
            s.sockets.get_mut::<tcp::Socket>(handle).abort();
        }
        // <<< AETHER-APP-PATCH netstack-uplink-admission

        {
            let pending_empty = s.tcp_conns[&id].pending.is_empty();
            let half = s.tcp_conns[&id].half_closed;
            if half && pending_empty {
                s.sockets.get_mut::<tcp::Socket>(handle).close();
            }
        }

        let to_app = s.tcp_conns[&id].to_app.clone();
        let mut app_gone = false;
        let mut delivered = 0;

        while delivered < MAX_RECV_CHUNKS {
            let permit = match to_app.try_reserve() {
                Ok(permit) => permit,
                Err(mpsc::error::TrySendError::Full(())) => {
                    backpressured = true;
                    break;
                }
                Err(mpsc::error::TrySendError::Closed(())) => {
                    app_gone = true;
                    break;
                }
            };

            let socket = s.sockets.get_mut::<tcp::Socket>(handle);
            if !socket.can_recv() {
                break;
            }
            let chunk = match socket.recv(|buf| {
                let v = buf.to_vec();
                (v.len(), v)
            }) {
                Ok(v) if !v.is_empty() => v,
                _ => break,
            };
            permit.send(chunk);
            delivered += 1;
        }

        if app_gone {
            // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
            // A plain close() only starts a shutdown; a far end that never
            // answers it left the socket alive for the whole session. Half of
            // upstream's #101/#106 descriptor exhaustion was exactly this. The
            // socket gets ORPHAN_LINGER to finish, then it is reset - at once if
            // it is still delivering data nobody is left to read.
            let st = s.tcp_conns.get_mut(&id).unwrap();
            let socket = s.sockets.get_mut::<tcp::Socket>(handle);
            let orphaned_at = *st.orphaned_at.get_or_insert(now);
            if socket.can_recv() || now.duration_since(orphaned_at) >= s.tcp_limits.orphan_linger {
                if socket.state() != tcp::State::Closed {
                    socket.abort();
                }
                st.aborted = true;
                continue;
            }
            socket.close();
            // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes
        }

        let st_state = s.sockets.get_mut::<tcp::Socket>(handle).state();
        if matches!(st_state, tcp::State::CloseWait) {
            s.sockets.get_mut::<tcp::Socket>(handle).close();
        }
        if matches!(st_state, tcp::State::TimeWait) {
            if let Some(st) = s.tcp_conns.get_mut(&id) {
                st.pending.clear();
                st.pending.shrink_to_fit();
            }
        }
        if matches!(st_state, tcp::State::Closed | tcp::State::TimeWait)
            && s.tcp_conns[&id].established
        {
            // >>> AETHER-APP-PATCH netstack-uplink-admission
            s.tcp_conns[&id].credit.close();
            // <<< AETHER-APP-PATCH netstack-uplink-admission
            s.sockets.remove(handle);
            s.tcp_conns.remove(&id);
        }
    }

    backpressured
}

fn service_udp(s: &mut NetStack) -> bool {
    let mut backpressured = false;
    let ids: Vec<usize> = s.udp_conns.keys().copied().collect();

    for id in ids {
        let handle = match s.udp_conns.get(&id) {
            Some(st) => st.handle,
            None => continue,
        };

        let to_app = s.udp_conns[&id].to_app.clone();
        let mut delivered = 0;
        // >>> AETHER-CORE-PORT 2.0.0 udp-orphan-reap
        let mut app_gone = false;
        // <<< AETHER-CORE-PORT 2.0.0 udp-orphan-reap

        while delivered < MAX_RECV_CHUNKS {
            let permit = match to_app.try_reserve() {
                Ok(permit) => permit,
                Err(mpsc::error::TrySendError::Full(())) => {
                    backpressured = true;
                    break;
                }
                // >>> AETHER-CORE-PORT 2.0.0 udp-orphan-reap
                Err(mpsc::error::TrySendError::Closed(())) => {
                    app_gone = true;
                    break;
                }
                // <<< AETHER-CORE-PORT 2.0.0 udp-orphan-reap
            };

            let socket = s.sockets.get_mut::<udp::Socket>(handle);
            if !socket.can_recv() {
                break;
            }
            match socket.recv() {
                Ok((data, meta)) => {
                    permit.send((endpoint_to_socketaddr(meta.endpoint), data.to_vec()));
                    delivered += 1;
                }
                Err(_) => break,
            }
        }

        // >>> AETHER-CORE-PORT 2.0.0 udp-orphan-reap
        // The UDP half of the descriptor leak: a closed app channel was merely
        // `break`ed out of, so the socket stayed bound forever. Every DNS query
        // in a long session is one of these.
        if app_gone {
            if let Some(st) = s.udp_conns.remove(&id) {
                s.sockets.remove(st.handle);
            }
        }
        // <<< AETHER-CORE-PORT 2.0.0 udp-orphan-reap
    }

    backpressured
}

/// Moves what the stack produced into the WireGuard writer.
///
/// Returns `(dropped, retained)`. 1.2.8: a full channel used to mean "throw
/// away every remaining packet in this burst", so one late wakeup of the writer
/// cost a video its whole window and the tunnel a retransmit storm - the ping
/// spike the user sees right before everything stops. Now the burst is held and
/// retried on the next 2 ms pass, and only a genuinely hopeless queue
/// (> [MAX_TX_RETAINED]) is tail-dropped, which is what a real link does.
fn flush_tx(s: &mut NetStack, outbound_tx: &mpsc::Sender<Vec<u8>>) -> (usize, bool) {
    let mut dropped = 0;
    let mut retained = false;

    if s.device.tx.len() > s.device.tx_peak {
        s.device.tx_peak = s.device.tx.len();
    }

    while let Some(pkt) = s.device.tx.pop_front() {
        match outbound_tx.try_send(pkt) {
            Ok(()) => {}
            Err(mpsc::error::TrySendError::Full(pkt)) => {
                // >>> AETHER-APP-PATCH netstack-device-backpressure
                // 1.2.8-r5. The r4 shape of this branch dropped ONE packet and
                // then `break`ed, so a queue under sustained pressure grew past
                // MAX_TX_RETAINED without any bound at all while the drop
                // counter crawled one per 2 ms pass. And because a drop here
                // happens AFTER smoltcp has recorded the packet as sent, every
                // one of them was loss the congestion controller could not see.
                //
                // With `transmit()` now gated at MAX_DEVICE_TX this branch is
                // the rare case, not the steady state: the correct action is to
                // hold the burst and retry in 2 ms. Shedding only happens at the
                // hard ceiling, and then the whole excess goes at once - a queue
                // that deep is latency nobody wants delivered - and it is
                // counted separately so the log can say whether the controller
                // is being lied to again.
                s.device.tx.push_front(pkt);
                retained = true;
                if s.device.tx.len() > MAX_DEVICE_TX_HARD {
                    let excess = s.device.tx.len() - MAX_DEVICE_TX_HARD;
                    for _ in 0..excess {
                        // Head-drop: the oldest packet is the most stale, and
                        // dropping it is what lets the freshest data through.
                        if s.device.tx.pop_front().is_none() {
                            break;
                        }
                        dropped += 1;
                    }
                    s.device.tx_shed = s.device.tx_shed.saturating_add(dropped);
                }
                break;
                // <<< AETHER-APP-PATCH netstack-device-backpressure
            }
            Err(mpsc::error::TrySendError::Closed(_)) => break,
        }
    }

    (dropped, retained)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration as StdDuration;

    fn udp_ip_packet(payload_len: usize) -> Vec<u8> {
        let total = 20 + 8 + payload_len;
        let mut pkt = vec![0u8; total];
        pkt[0] = 0x45;
        pkt[2] = (total >> 8) as u8;
        pkt[3] = (total & 0xff) as u8;
        pkt[8] = 64;
        pkt[9] = 17;
        pkt[12..16].copy_from_slice(&[10, 0, 0, 9]);
        pkt[16..20].copy_from_slice(&[198, 18, 0, 1]);
        pkt[20..22].copy_from_slice(&5555u16.to_be_bytes());
        pkt[22..24].copy_from_slice(&9999u16.to_be_bytes());
        let udp_len = (8 + payload_len) as u16;
        pkt[24..26].copy_from_slice(&udp_len.to_be_bytes());
        pkt
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn netstack_keeps_draining_inbound_when_outbound_is_never_read() {
        let (inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(4);
        let (outbound_tx, _outbound_rx_never_read) = mpsc::channel::<Vec<u8>>(1);

        let stack = spawn("198.18.0.1", "fc00::1", 1400, inbound_rx, outbound_tx)
            .expect("netstack should start");

        let udp = stack.open_udp().await.expect("udp socket should open");
        let dst: SocketAddr = "1.1.1.1:53".parse().unwrap();

        for _ in 0..64 {
            let _ = udp.send_to(dst, vec![0u8; 64]).await;
        }

        tokio::time::sleep(StdDuration::from_millis(120)).await;

        for index in 0..64 {
            let send = inbound_tx.send(udp_ip_packet(32));
            tokio::time::timeout(StdDuration::from_secs(3), send)
                .await
                .unwrap_or_else(|_| {
                    panic!("netstack stopped draining inbound at packet {index}: deadlock")
                })
                .expect("inbound channel should stay open");
        }
    }

    fn checksum16(data: &[u8], initial: u32) -> u16 {
        let mut sum = initial;
        let mut chunks = data.chunks_exact(2);
        for chunk in chunks.by_ref() {
            sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
        }
        if let Some(&last) = chunks.remainder().first() {
            sum += (last as u32) << 8;
        }
        while sum >> 16 != 0 {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        !(sum as u16)
    }

    struct Segment {
        src_port: u16,
        dst_port: u16,
        seq: u32,
        flags: u8,
    }

    fn parse_tcp(pkt: &[u8]) -> Option<Segment> {
        if pkt.len() < 20 || pkt[0] >> 4 != 4 {
            return None;
        }
        let ihl = ((pkt[0] & 0x0f) as usize) * 4;
        if pkt[9] != 6 || pkt.len() < ihl + 20 {
            return None;
        }
        let tcp = &pkt[ihl..];
        Some(Segment {
            src_port: u16::from_be_bytes([tcp[0], tcp[1]]),
            dst_port: u16::from_be_bytes([tcp[2], tcp[3]]),
            seq: u32::from_be_bytes([tcp[4], tcp[5], tcp[6], tcp[7]]),
            flags: tcp[13],
        })
    }

    fn build_tcp(
        src: (Ipv4Addr, u16),
        dst: (Ipv4Addr, u16),
        seq: u32,
        ack: u32,
        flags: u8,
    ) -> Vec<u8> {
        let mut tcp = vec![0u8; 20];
        tcp[0..2].copy_from_slice(&src.1.to_be_bytes());
        tcp[2..4].copy_from_slice(&dst.1.to_be_bytes());
        tcp[4..8].copy_from_slice(&seq.to_be_bytes());
        tcp[8..12].copy_from_slice(&ack.to_be_bytes());
        tcp[12] = 5 << 4;
        tcp[13] = flags;
        tcp[14..16].copy_from_slice(&64240u16.to_be_bytes());

        let mut pseudo = Vec::new();
        pseudo.extend_from_slice(&src.0.octets());
        pseudo.extend_from_slice(&dst.0.octets());
        pseudo.push(0);
        pseudo.push(6);
        pseudo.extend_from_slice(&(tcp.len() as u16).to_be_bytes());
        pseudo.extend_from_slice(&tcp);
        let tcp_sum = checksum16(&pseudo, 0);
        tcp[16..18].copy_from_slice(&tcp_sum.to_be_bytes());

        let total = 20 + tcp.len();
        let mut ip = vec![0u8; 20];
        ip[0] = 0x45;
        ip[2..4].copy_from_slice(&(total as u16).to_be_bytes());
        ip[8] = 64;
        ip[9] = 6;
        ip[12..16].copy_from_slice(&src.0.octets());
        ip[16..20].copy_from_slice(&dst.0.octets());
        let ip_sum = checksum16(&ip, 0);
        ip[10..12].copy_from_slice(&ip_sum.to_be_bytes());

        ip.extend_from_slice(&tcp);
        ip
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_vanished_app_makes_the_netstack_tear_the_connection_down() {
        let local = Ipv4Addr::new(198, 18, 0, 1);
        let remote = Ipv4Addr::new(93, 184, 216, 34);
        let remote_port = 80u16;

        let (inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(64);
        let (outbound_tx, mut outbound_rx) = mpsc::channel::<Vec<u8>>(256);

        let stack = spawn("198.18.0.1", "fc00::1", 1400, inbound_rx, outbound_tx)
            .expect("netstack should start");

        let dst = SocketAddr::new(IpAddr::V4(remote), remote_port);
        let connect = {
            let stack = stack.clone();
            tokio::spawn(async move { stack.open_tcp(dst).await })
        };

        let deadline = tokio::time::Instant::now() + StdDuration::from_secs(5);

        let (client_port, client_seq) = loop {
            let pkt = tokio::time::timeout_at(deadline, outbound_rx.recv())
                .await
                .expect("the netstack should emit a syn")
                .expect("outbound channel stays open");

            if let Some(seg) = parse_tcp(&pkt) {
                if seg.dst_port == remote_port && seg.flags & 0x02 != 0 && seg.flags & 0x10 == 0 {
                    break (seg.src_port, seg.seq);
                }
            }
        };

        let syn_ack = build_tcp(
            (remote, remote_port),
            (local, client_port),
            5000,
            client_seq.wrapping_add(1),
            0x12,
        );
        inbound_tx.send(syn_ack).await.expect("inbound accepts the syn-ack");

        let conn = tokio::time::timeout(StdDuration::from_secs(5), connect)
            .await
            .expect("the connect call should finish")
            .expect("the connect task should not panic")
            .expect("the connection should be established");

        drop(conn);

        let deadline = tokio::time::Instant::now() + StdDuration::from_secs(5);
        let mut saw_teardown = false;

        while let Ok(Some(pkt)) = tokio::time::timeout_at(deadline, outbound_rx.recv()).await {
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.flags & 0x01 != 0 || seg.flags & 0x04 != 0 {
                    saw_teardown = true;
                    break;
                }
            }
        }

assert!(
            saw_teardown,
            "the netstack never closed the socket after the app went away, so it leaks"
        );
    }

    fn bare_stack() -> NetStack {
        NetStack {
            iface: {
                let mut device = StackDevice::new(1400);
                let config = Config::new(HardwareAddress::Ip);
                Interface::new(config, &mut device, Instant::now())
            },
            device: StackDevice::new(1400),
            sockets: SocketSet::new(Vec::new()),
            tcp_conns: HashMap::new(),
            udp_conns: HashMap::new(),
            next_id: 0,
            next_port: 40000,
            data_in_tx: mpsc::channel(1).0,
            // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
            tcp_limits: TcpLimits::from_env(),
            // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes
        }
    }

    // >>> AETHER-CORE-PORT 2.0.0 tcp-lifetimes
    /// The lifetimes core 2.0.0's own tests use: short enough that a test can
    /// wait them out, never read from the clock or the environment.
    fn quick_limits() -> TcpLimits {
        TcpLimits {
            connect: StdDuration::from_millis(300),
            keepalive: StdDuration::from_secs(60),
            dead_peer: StdDuration::from_secs(360),
            orphan_linger: StdDuration::from_millis(300),
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_connect_nobody_answers_fails_instead_of_hanging() {
        let (_inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(64);
        let (outbound_tx, _outbound_rx) = mpsc::channel::<Vec<u8>>(256);
        let stack = spawn_with_limits(
            "198.18.0.1",
            "fc00::1",
            1400,
            inbound_rx,
            outbound_tx,
            quick_limits(),
        )
        .expect("netstack should start");

        let dst: SocketAddr = "93.184.216.34:80".parse().unwrap();
        let outcome = tokio::time::timeout(StdDuration::from_secs(5), stack.open_tcp(dst))
            .await
            .expect("a connect that is never answered must fail, not hang");

        match outcome {
            Ok(_) => panic!("nothing answered, so the connect cannot succeed"),
            Err(error) => assert!(error.to_string().contains("timed out"), "{error}"),
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn an_orphan_whose_far_end_never_closes_is_reset() {
        let local = Ipv4Addr::new(198, 18, 0, 1);
        let remote = Ipv4Addr::new(93, 184, 216, 34);
        let remote_port = 80u16;

        let (inbound_tx, inbound_rx) = mpsc::channel::<Vec<u8>>(64);
        let (outbound_tx, mut outbound_rx) = mpsc::channel::<Vec<u8>>(256);
        let stack = spawn_with_limits(
            "198.18.0.1",
            "fc00::1",
            1400,
            inbound_rx,
            outbound_tx,
            quick_limits(),
        )
        .expect("netstack should start");

        let dst = SocketAddr::new(IpAddr::V4(remote), remote_port);
        let connect = {
            let stack = stack.clone();
            tokio::spawn(async move { stack.open_tcp(dst).await })
        };

        let deadline = tokio::time::Instant::now() + StdDuration::from_secs(5);
        let (client_port, client_seq) = loop {
            let pkt = tokio::time::timeout_at(deadline, outbound_rx.recv())
                .await
                .expect("the netstack should emit a syn")
                .expect("outbound channel stays open");
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.dst_port == remote_port && seg.flags & 0x02 != 0 && seg.flags & 0x10 == 0 {
                    break (seg.src_port, seg.seq);
                }
            }
        };

        let syn_ack = build_tcp(
            (remote, remote_port),
            (local, client_port),
            5000,
            client_seq.wrapping_add(1),
            0x12,
        );
        inbound_tx
            .send(syn_ack)
            .await
            .expect("inbound accepts the syn-ack");

        let conn = tokio::time::timeout(StdDuration::from_secs(5), connect)
            .await
            .expect("the connect call should finish")
            .expect("the connect task should not panic")
            .expect("the connection should be established");
        drop(conn);

        // The app is gone, so the stack sends a FIN. The far end acknowledges it
        // and then says nothing more, ever - the shape that used to keep the
        // socket, its port and its two channels for the whole session.
        let fin_seq = loop {
            let pkt = tokio::time::timeout_at(deadline, outbound_rx.recv())
                .await
                .expect("the netstack should send a fin")
                .expect("outbound channel stays open");
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.flags & 0x01 != 0 {
                    break seg.seq;
                }
            }
        };
        let ack = build_tcp(
            (remote, remote_port),
            (local, client_port),
            5001,
            fin_seq.wrapping_add(1),
            0x10,
        );
        inbound_tx.send(ack).await.expect("inbound accepts the ack");

        let mut saw_reset = false;
        while let Ok(Some(pkt)) = tokio::time::timeout_at(deadline, outbound_rx.recv()).await {
            if let Some(seg) = parse_tcp(&pkt) {
                if seg.flags & 0x04 != 0 {
                    saw_reset = true;
                    break;
                }
            }
        }
        assert!(
            saw_reset,
            "an orphaned connection whose far end never closes must be reset, not kept"
        );
    }

    #[tokio::test]
    async fn an_address_assigned_for_one_family_keeps_the_other() {
        let mut stack = bare_stack();
        apply_addrs(
            &mut stack.iface,
            Some(("172.16.0.2".parse().unwrap(), 32)),
            Some(("2606:4700:110:8a36::1".parse().unwrap(), 128)),
        );

        handle_cmd(
            &mut stack,
            Cmd::SetAddrs {
                v4: Some(("172.16.0.9".parse().unwrap(), 32)),
                v6: None,
            },
        );
        handle_cmd(
            &mut stack,
            Cmd::SetAddrs {
                v4: None,
                v6: Some(("2606:4700:110:8a36::9".parse().unwrap(), 128)),
            },
        );

        let (v4, v6) = current_addrs(&stack.iface);
        assert_eq!(v4.map(|(ip, _)| ip), Some("172.16.0.9".parse().unwrap()));
        assert_eq!(
            v6.map(|(ip, _)| ip),
            Some("2606:4700:110:8a36::9".parse().unwrap())
        );
    }
    // <<< AETHER-CORE-PORT 2.0.0 tcp-lifetimes

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn flush_tx_holds_a_burst_back_instead_of_shredding_it() {
        let (outbound_tx, outbound_rx) = mpsc::channel::<Vec<u8>>(2);
        let mut stack = bare_stack();

        for _ in 0..10 {
            stack.device.tx.push_back(vec![1, 2, 3]);
        }

        let (dropped, retained) = flush_tx(&mut stack, &outbound_tx);

        assert_eq!(dropped, 0, "a short burst is never dropped any more");
        assert!(retained, "the rest of the burst is held for the next pass");
        assert_eq!(outbound_rx.len(), 2, "the channel keeps what fits");
        assert_eq!(stack.device.tx.len(), 8, "and the remainder waits, in order");

        // Once the writer catches up the held packets go out, so nothing is lost.
        while outbound_rx.try_recv().is_ok() {}
        let (dropped, _) = flush_tx(&mut stack, &outbound_tx);
        assert_eq!(dropped, 0);
        assert_eq!(outbound_rx.len(), 2);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_wedged_flow_cannot_block_another_flows_writes() {
        // THE 1.2.8 REGRESSION TEST. Flow 1 cannot take another byte (its peer
        // stopped reading - a stalled video segment is the everyday example).
        // Flow 2 is healthy. Before the fix, flow 1 latched the single global
        // deferred queue and the run loop stopped reading data_in altogether,
        // so flow 2 - and every other flow, and DNS - went silent until the
        // user reconnected by hand.
        let mut stack = bare_stack();
        let mut backlog = Backlog::default();

        let new_conn = |stack: &mut NetStack, pending: Vec<u8>| -> usize {
            let socket = tcp::Socket::new(
                tcp::SocketBuffer::new(vec![0u8; 1024]),
                tcp::SocketBuffer::new(vec![0u8; 1024]),
            );
            let handle = stack.sockets.add(socket);
            let id = stack.next_id;
            stack.next_id += 1;
            let (to_app, _rx) = mpsc::channel::<Vec<u8>>(1);
            stack.tcp_conns.insert(
                id,
                TcpState {
                    handle,
                    to_app,
                    from_stack_rx: None,
                    connect_resp: None,
                    pending,
                    established: true,
                    half_closed: false,
                    last_progress: std::time::Instant::now(),
                    send_queue_high: 0,
                    accepted_total: 0,
                    acked_high: 0,
                    last_drain: std::time::Instant::now(),
                    drain_warned: false,
                    credit: Arc::new(FlowCredit::new()),
                    rate_mark: std::time::Instant::now(),
                    rate_base: 0,
                    drain_rate: 0.0,
                    uplink_budget: MIN_FLOW_QUEUE_BYTES,
                    outstanding_peak: 0,
                    pending_peak: 0,
                    send_queue_peak: 0,
                    stall_count: 0,
                    stall_mark: std::time::Instant::now(),
                },
            );
            id
        };

        let wedged = new_conn(&mut stack, vec![0u8; max_tcp_pending()]);
        let healthy = new_conn(&mut stack, Vec::new());

        ingest(&mut stack, &mut backlog, DataIn::Tcp(wedged, vec![1u8; 64]));
        ingest(&mut stack, &mut backlog, DataIn::Tcp(healthy, vec![2u8; 64]));

        assert!(backlog.holds(wedged), "the stuck flow keeps its own backlog");
        assert!(
            !backlog.holds(healthy),
            "the healthy flow must not be punished for it"
        );
        assert_eq!(
            stack.tcp_conns[&healthy].pending.len(),
            64,
            "the healthy flow's bytes went through while the other one was stuck"
        );

        // A second chunk for the stuck flow queues BEHIND the first: a TCP
        // stream may never be reordered by the fix that unblocks its neighbours.
        ingest(&mut stack, &mut backlog, DataIn::Tcp(wedged, vec![3u8; 64]));
        assert_eq!(backlog.queue.len(), 2);
        assert!(matches!(backlog.queue.front(), Some(DataIn::Tcp(_, d)) if d[0] == 1));

        // And the stack keeps serving the healthy flow across drain passes.
        drain_backlog(&mut stack, &mut backlog);
        ingest(&mut stack, &mut backlog, DataIn::Tcp(healthy, vec![4u8; 64]));
        assert_eq!(stack.tcp_conns[&healthy].pending.len(), 128);
        assert!(backlog.holds(wedged));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_saturated_download_cannot_starve_the_upload_path() {
        // THE 1.2.8-r2 REGRESSION TEST.
        //
        // The run loop used to be one `biased` select with inbound first, so a
        // queue that never ran empty meant the app->network arm was never even
        // polled: uploads and new flows stopped while downloads kept arriving.
        // The scheduler now gives every queue its own per-pass budget, so this
        // asserts the property directly on the drain helpers the loop uses:
        // a permanently full inbound queue does not stop app data being placed.
        let mut stack = bare_stack();
        let mut backlog = Backlog::default();

        let socket = tcp::Socket::new(
            tcp::SocketBuffer::new(vec![0u8; 4096]),
            tcp::SocketBuffer::new(vec![0u8; 4096]),
        );
        let handle = stack.sockets.add(socket);
        let id = stack.next_id;
        stack.next_id += 1;
        let (to_app, _rx) = mpsc::channel::<Vec<u8>>(1);
        stack.tcp_conns.insert(
            id,
            TcpState {
                handle,
                to_app,
                from_stack_rx: None,
                connect_resp: None,
                pending: Vec::new(),
                established: true,
                half_closed: false,
                last_progress: std::time::Instant::now(),
                send_queue_high: 0,
                accepted_total: 0,
                acked_high: 0,
                last_drain: std::time::Instant::now(),
                drain_warned: false,
                credit: Arc::new(FlowCredit::new()),
                rate_mark: std::time::Instant::now(),
                rate_base: 0,
                drain_rate: 0.0,
                uplink_budget: MIN_FLOW_QUEUE_BYTES,
                outstanding_peak: 0,
                pending_peak: 0,
                send_queue_peak: 0,
                stall_count: 0,
                stall_mark: std::time::Instant::now(),
            },
        );

        // A download that never lets up: the queue is refilled past the budget
        // the loop is allowed to take in one pass.
        let (in_tx, mut in_rx) = mpsc::channel::<Vec<u8>>(MAX_INGEST_PER_TICK * 4);
        for _ in 0..MAX_INGEST_PER_TICK * 3 {
            in_tx.try_send(udp_ip_packet(64)).unwrap();
        }

        // One upload waiting behind it.
        let (app_tx, mut app_rx) = mpsc::channel::<DataIn>(16);
        app_tx
            .try_send(DataIn::Tcp(id, vec![7u8; 128]))
            .expect("queue the upload");

        let (moved_in, closed_in) = drain_inbound(&mut stack, &mut in_rx, MAX_INGEST_PER_TICK);
        assert_eq!(moved_in, MAX_INGEST_PER_TICK, "inbound is still served fully");
        assert!(!closed_in);
        assert!(
            !in_rx.is_empty(),
            "the download deliberately still has more waiting - that is the whole point"
        );

        let (moved_app, closed_app) = drain_app_data(
            &mut stack,
            &mut backlog,
            &mut app_rx,
            MAX_APP_INGEST_PER_TICK,
        );
        assert_eq!(
            moved_app, 1,
            "the upload MUST be served in the same pass as a saturated download"
        );
        assert!(!closed_app);
        assert_eq!(
            stack.tcp_conns[&id].pending.len(),
            128,
            "and its bytes must actually reach the flow"
        );
    }
    // >>> AETHER-APP-PATCH netstack-uplink-admission
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn an_upload_cannot_queue_more_than_its_time_budget() {
        // THE 1.2.8-r8 REGRESSION TEST.
        //
        // Before admission control the app side could hand this stack ~900 KB
        // before anything pushed back (1024-message channel + 256 KB `pending` +
        // 128 KB send buffer + 512 KB backlog), which at the 107 KB/s the field
        // log measured is about eight seconds of queue in front of every other
        // flow on the device. The gate below is what makes that arithmetic
        // impossible, so the property is asserted directly.
        let credit = FlowCredit::new();
        credit.publish(0, MIN_FLOW_QUEUE_BYTES);

        // A fresh flow may fill its budget without waiting for anything.
        for _ in 0..3 {
            credit.reserve(16 * 1024).await;
        }
        assert_eq!(credit.outstanding(), MIN_FLOW_QUEUE_BYTES as u64);

        // Nothing acknowledged, budget full: the writer MUST wait here. This is
        // the exact state in which r7 accepted another 850 KB.
        let held = tokio::time::timeout(
            StdDuration::from_millis(60),
            credit.reserve(16 * 1024),
        )
        .await;
        assert!(
            held.is_err(),
            "a flow that is over budget must hold its writer instead of queueing"
        );
        assert_eq!(
            credit.outstanding(),
            MIN_FLOW_QUEUE_BYTES as u64,
            "and a write that never completed must not be accounted for"
        );

        // An ACK from the peer - the only thing that may open the gate - lets the
        // writer through again, by exactly as much as the peer took.
        credit.publish(32 * 1024, MIN_FLOW_QUEUE_BYTES);
        tokio::time::timeout(
            StdDuration::from_millis(1_000),
            credit.reserve(16 * 1024),
        )
        .await
        .expect("an acknowledgement must release the writer");
        assert_eq!(credit.outstanding(), 32 * 1024);

        // And a flow that has been reset never parks its writer, whatever the
        // accounting says: the app has to be told now.
        credit.publish(32 * 1024, MIN_FLOW_QUEUE_BYTES);
        credit.close();
        tokio::time::timeout(
            StdDuration::from_millis(1_000),
            credit.reserve(512 * 1024),
        )
        .await
        .expect("a dead flow must release its writer immediately");
    }
    // <<< AETHER-APP-PATCH netstack-uplink-admission
}
