#!/usr/bin/env bash
#
# h2-bench.sh — measure what a MASQUE carrier actually downloads.
#
# HTTP/2 flow control caps a download at window / round-trip-time, so a narrow
# window shows up as a speed that will not move however fat the line is. This
# brings up a tunnel, prints the window it announced, downloads a file through
# the SOCKS proxy, and tells you the measured rate next to the ceiling that
# window and the measured round trip imply. Run it against two builds to
# compare them, or with --mode h3 to see what the QUIC carrier does on the same
# line right now.
#
# Usage:
#   ./h2-bench.sh [options] [path-to-aether]
#
#   --mode h2|h3|wg     carrier to test          (default h2)
#   --peer ip:port      skip the scan            (default: scan)
#   --scan MODE         turbo|balanced|thorough  (default turbo)
#   --size MB           download size in MB      (default 50)
#   --url URL           download from here instead
#   --port N            local SOCKS port         (default 11899)
#   --startup SECONDS   how long to wait for the tunnel (default 180)
#   --keep              keep the log file and say where it is
#   -h, --help
#
# The identity is provisioned once into ~/.cache/aether-h2-bench and reused, so
# repeated runs do not register a new WARP device every time.

set -uo pipefail

MODE=h2
PEER=""
SCAN=turbo
SIZE_MB=50
URL=""
PORT=11899
STARTUP=180
KEEP=0
BIN=""

die() { printf '\n%s\n' "$*" >&2; exit 1; }
need() { [ $# -ge 2 ] || die "$1 needs a value (try --help)"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --mode)    need "$@"; MODE="$2"; shift 2 ;;
        --peer)    need "$@"; PEER="$2"; shift 2 ;;
        --scan)    need "$@"; SCAN="$2"; shift 2 ;;
        --size)    need "$@"; SIZE_MB="$2"; shift 2 ;;
        --url)     need "$@"; URL="$2"; shift 2 ;;
        --port)    need "$@"; PORT="$2"; shift 2 ;;
        --startup) need "$@"; STARTUP="$2"; shift 2 ;;
        --keep)    KEEP=1; shift ;;
        -h|--help) awk 'NR>1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
        -*)        die "unknown option $1 (try --help)" ;;
        *)         BIN="$1"; shift ;;
    esac
done

case "$MODE" in
    h2|h3|wg) ;;
    *) die "--mode takes h2, h3 or wg, not '$MODE'" ;;
esac

# ---------------------------------------------------------------- the binary
if [ -z "$BIN" ]; then
    here="$(cd "$(dirname "$0")" && pwd)"
    for candidate in \
        "$here/aether/target/release/aether" \
        "$here/aether/target/debug/aether" \
        "$here/target/release/aether" \
        "$here/target/debug/aether" \
        "$(command -v aether 2>/dev/null)"
    do
        if [ -n "$candidate" ] && [ -x "$candidate" ]; then BIN="$candidate"; break; fi
    done
fi
[ -n "$BIN" ] && [ -x "$BIN" ] || die "no aether binary found; pass one: ./h2-bench.sh /path/to/aether"
BIN="$(cd "$(dirname "$BIN")" && pwd)/$(basename "$BIN")"

command -v curl >/dev/null || die "curl is needed for the download"

[ -n "$URL" ] || URL="https://speed.cloudflare.com/__down?bytes=$((SIZE_MB * 1000 * 1000))"

STATE="${XDG_CACHE_HOME:-$HOME/.cache}/aether-h2-bench"
mkdir -p "$STATE" || die "cannot create $STATE"
LOG="$(mktemp "${TMPDIR:-/tmp}/aether-h2-bench.XXXXXX.log")"

PID=""
cleanup() {
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
        kill "$PID" 2>/dev/null
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$PID" 2>/dev/null || break
            sleep 0.3
        done
        kill -9 "$PID" 2>/dev/null
    fi
    if [ "$KEEP" = 1 ]; then
        printf '\nlog kept at %s\n' "$LOG"
    else
        rm -f "$LOG"
    fi
}
trap cleanup EXIT INT TERM

# ------------------------------------------------------------------ the run
ARGS=(--bind "127.0.0.1:$PORT" --config "$STATE/aether.toml" --quick-reconnect -4)
case "$MODE" in
    h2) ARGS+=(--masque --h2) ;;
    h3) ARGS+=(--masque) ;;
    wg) ARGS+=(--wg) ;;
esac
[ -n "$PEER" ] && ARGS+=(--peer "$PEER") || ARGS+=(--scan "$SCAN")

printf 'aether     %s\n' "$BIN"
printf 'carrier    %s\n' "$MODE"
printf 'download   %s\n' "$URL"
printf 'starting   %s %s\n\n' "$(basename "$BIN")" "${ARGS[*]}"

"$BIN" "${ARGS[@]}" </dev/null >"$LOG" 2>&1 &
PID=$!

waited=0
while [ "$waited" -lt "$STARTUP" ]; do
    grep -q "socks5 listening" "$LOG" && break
    if ! kill -0 "$PID" 2>/dev/null; then
        printf 'aether exited before the proxy came up:\n\n'
        tail -25 "$LOG"
        exit 1
    fi
    sleep 1
    waited=$((waited + 1))
done

if ! grep -q "socks5 listening" "$LOG"; then
    printf 'the proxy did not come up within %ss:\n\n' "$STARTUP"
    tail -25 "$LOG"
    exit 1
fi

printf 'tunnel up after %ss\n' "$waited"
grep -m1 -oE "flow control: .*" "$LOG" | sed 's/^/h2 window  /'
grep -m1 -oE "netstack tcp buffers=[^,]*" "$LOG" | sed 's/^/netstack   /'
grep -m1 -oE "(selected MASQUE gateway|using cloudflare edge|MASQUE transport:) .*" "$LOG" | sed 's/^/edge       /'
grep -m1 -oE "inner mtu [0-9]+" "$LOG" | sed 's/^/mtu        /'

# ------------------------------------------------------------- the download
# time_connect only reaches the local proxy, so the round trip that matters is
# the one from sending the request to the first byte coming back.
printf '\nmeasuring the round trip through the tunnel...\n'
read -r T_APP T_FIRST < <(curl -s -o /dev/null --max-time 30 \
        -x "socks5h://127.0.0.1:$PORT" \
        -w '%{time_appconnect} %{time_starttransfer}' \
        "https://speed.cloudflare.com/__down?bytes=1" 2>/dev/null)
RTT=$(awk -v a="${T_APP:-0}" -v b="${T_FIRST:-0}" 'BEGIN { d = b - a; print (d > 0 ? d : 0) }')

printf 'downloading %sMB through the tunnel...\n' "$SIZE_MB"
read -r SPEED SIZE TOTAL < <(curl -s -o /dev/null --max-time 300 \
    -x "socks5h://127.0.0.1:$PORT" \
    -w '%{speed_download} %{size_download} %{time_total}' "$URL" 2>/dev/null)

if [ -z "${SPEED:-}" ] || [ "${SIZE:-0}" = "0" ]; then
    printf '\nthe download failed; the last lines of the log:\n\n'
    tail -20 "$LOG"
    exit 1
fi

# ---------------------------------------------------------------- the report
H2_WINDOW_KB=$(grep -m1 -oE "stream window [0-9]+KB" "$LOG" | grep -oE "[0-9]+")
TCP_WINDOW_KB=$(grep -m1 -oE "netstack tcp buffers=[0-9]+KB rx" "$LOG" | grep -oE "[0-9]+")
[ -n "$H2_WINDOW_KB" ] || H2_WINDOW_KB=0
[ -n "$TCP_WINDOW_KB" ] || TCP_WINDOW_KB=0

awk -v speed="$SPEED" -v size="$SIZE" -v total="$TOTAL" -v rtt="$RTT" \
    -v h2_kb="$H2_WINDOW_KB" -v tcp_kb="$TCP_WINDOW_KB" -v mode="$MODE" '
function ceiling(kb) { return (kb * 1024) / rtt / 1048576 }
BEGIN {
    printf "\n------------------------------------------------------------\n";
    printf "carrier          %s\n", mode;
    printf "downloaded       %.1f MB in %.1f s\n", size / 1048576, total;
    printf "measured         %.2f MB/s  (%.1f Mbit/s)\n", speed / 1048576, speed * 8 / 1000000;

    if (rtt < 0.001) {
        printf "\nthe round trip came back as zero, so no ceiling can be worked out.\n";
        printf "------------------------------------------------------------\n";
        exit;
    }

    printf "round trip       %.0f ms through the tunnel\n\n", rtt * 1000;

    measured = speed / 1048576;
    tightest = "";
    tightest_at = 0;

    if (tcp_kb > 0) {
        c = ceiling(tcp_kb);
        printf "inner tcp window %5d KB -> allows %7.2f MB/s\n", tcp_kb, c;
        if (tightest == "" || c < tightest_at) { tightest = "the netstack tcp receive buffer"; tightest_at = c }
    }
    if (h2_kb > 0 && mode == "h2") {
        c = ceiling(h2_kb);
        printf "h2 stream window %5d KB -> allows %7.2f MB/s\n", h2_kb, c;
        if (tightest == "" || c < tightest_at) { tightest = "the h2 flow-control window"; tightest_at = c }
    }

    if (tightest == "") {
        printf "------------------------------------------------------------\n";
        exit;
    }

    printf "\n";
    if (measured > tightest_at * 0.75)
        printf "%s is what is holding this back.\n", tightest;
    else
        printf "every window has room to spare (nearest is %s at %.2f MB/s),\nso the limit is the line itself, the edge, or tcp-in-tcp.\n", tightest, tightest_at;
    printf "------------------------------------------------------------\n";
}'
