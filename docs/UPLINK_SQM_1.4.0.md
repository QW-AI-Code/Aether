# Uplink bufferbloat under Gemini Live on home Wi-Fi - 1.4.0

Report: `Aether → Psiphon` and `Tor → Psiphon` are perfect on mobile data, Gemini
Live dubbing YouTube included (loge1). On home Wi-Fi, as soon as Gemini Live runs,
ping goes past 1000 ms, video will not open and data stops (loge2, loge3). Stop
Gemini and the session recovers on its own.

## Evidence

| | loge1 (mobile) | loge2 (Wi-Fi, Aether→Psiphon) | loge3 (Wi-Fi, Tor→Psiphon) |
|---|---|---|---|
| idle RTT | 194 ms | 157 ms | 577 ms |
| RTT under Gemini | 200-320 ms | 1183 / 1476 ms, probe fails | 990 / 1322 / 2662 ms |
| stage-1 flow | healthy | "NOTHING acknowledged for 8 s" → reset | n/a (Tor) |
| Psiphon | stable | `underlying conn is closed`, tunnel failed x3 | `sendSshKeepAlive timed out`, tunnel failed |

Stage 1 is different in loge2 and loge3 (WireGuard vs Tor) and the failure is
identical, so the cause is not in stage 1. The WireGuard writer never waited and the
device queue never passed 20/64, so the queue is not on the phone's socket either.

## Root cause

Gemini Live is an UPLOAD workload (camera + microphone). A mobile uplink has
megabits of headroom; a home fixed line has a thin upstream behind a CPE with a deep
FIFO. Nothing in the chain knew the bottleneck rate, so:

1. Every app flow is multiplexed by Psiphon onto ONE SSH connection; an SSH channel
   window (2 MB in x/crypto/ssh) lets a single uploader park megabytes in front of
   every other flow, the SSH keep-alive included.
2. The CPE's upstream FIFO turns the excess into seconds of delay, then drops.
3. The keep-alive times out (loge3) or the stage-1 flow goes 8 s without an ACK and
   is reset (loge2); either way Psiphon's tunnel dies, redials, and the cycle
   repeats until the upload stops.

This is textbook bufferbloat plus head-of-line blocking. The engine-side admission (1.2.8)
cannot fix it: it bounds bytes per stage-1 flow, but by then every app is already
inside the one SSH stream.

## Fix: SQM in `PsiphonSocksFront` (new `transport/UplinkGovernor.kt`)

The standard cure (FQ-CoDel RFC 8290 / CAKE, with a delay-driven autorate
controller for variable-rate links) is to shape egress just below the real
bottleneck so the dumb queue downstream stays empty, and to schedule the queue you
now own fairly. The front is the last point where flows are still separate, so the
governor sits there and covers every Psiphon chain (Aether→Psiphon, Tor→Psiphon).

- **Delay probe.** A cached-name DoH exchange on a dedicated warm connection through
  the full chain: every 400 ms while the uplink is loaded (and for 8 s after start or
  a server rotation), every 3 s when idle. A probe stuck behind a queue is fed in as
  a sample before it returns.
- **UplinkRateController.** Baseline = 60 s windowed minimum of clean samples.
  Target = max(40 ms, 25 % of baseline, 2x measured jitter). A queue raises the
  delay FLOOR (min of the last 4 samples); jitter does not. The clamp only engages
  when the uplink is loaded AND the floor is over target and still growing (or 3x
  over, or a deep excursion confirmed by two samples). Once engaged: rate = bottleneck
  estimate x 0.9 (x 0.5 to drain a deep queue), bottleneck estimated from delivery
  rate and delay gradient (out = in / (1 + d(delay)/dt)); probe up by 3-8 % when
  the floor is well under target; relax and finally open when idle.
- **UplinkShaper.** Token bucket at that rate, start-time fair queueing between bulk
  flows (8 KB slices), and FQ-CoDel's sparse-flow rule: the first 3 KB of a burst
  from a flow that was idle >= 250 ms go ahead of all bulk. DNS, TLS handshakes,
  HTTP requests, chat messages and the latency badge's own probe are never behind
  Gemini's upload. udpgw frames are paced too: DNS always sparse, QUIC/RTP bulk with
  head-drop as the loss signal.
- **Backpressure** leaves the process the right way: the uploading app's 64 KB
  loopback leg stops being read, hev's TCP window closes, and the app's congestion
  control lowers its bitrate.

A fat uplink never shows load-correlated delay, so on mobile the governor stays
**open** (no regression for loge1). Model results (`UplinkGovernorTest`):

| scenario | unmanaged queue | governed queue (settled) | delivered |
|---|---|---|---|
| 40 KB/s up, 120 KB/s demand | 20 s | ~0.5-0.7 s peak, ~0 median | 33-35 KB/s |
| Tor, 600 ms base, ±300 ms jitter, 60 KB/s | 20 s | ~0.55 s peak | ~58 KB/s |
| 1.5 MB/s up (mobile), ±80 ms jitter | 0 | 0 | 118-120 of 120 KB/s |

## Log lines

```
PsiphonSocksFront UplinkGovernor uplink bufferbloat detected - pacing to the bottleneck ...: rate=34 KB/s, baseline=158 ms, target=+40 ms, last rtt=610 ms, uplink=118 KB/s ...
PsiphonSocksFront UplinkGovernor pacing: ...            (every 15 s while engaged)
PsiphonSocksFront UplinkGovernor uplink clamp released - the path carries this load without queueing
PsiphonSocksFront UplinkGovernor stopped: ...           (session summary)
```

## Verify on device

1. Home Wi-Fi, `Aether → Psiphon`, start Gemini Live. Within ~1 s the log shows
   "bufferbloat detected"; the badge should stay within a few hundred ms of the idle
   value, YouTube must still open, and there must be no `tunnel failed` /
   `sendSshKeepAlive timed out`.
2. Same on `Tor → Psiphon`.
3. Mobile data: no "bufferbloat detected" line at all, same speed as before.

`UplinkGovernorTest` runs in CI with the unit tests. The governor lives in the app,
not in the engine.
