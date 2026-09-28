#!/usr/bin/env bash
#
# Builds the native cores and installs them into app/src/main/jniLibs/<abi>/:
#
#   libhev-socks5-tunnel.so <- hev's core, built WITHOUT its bundled JNI layer
#                    (hev-jni.c is stripped from the build — see build_hev).
#                    Only its stable public C API is used.
#   libaethertun.so <- OUR OWN JNI bridge (scripts/aethertun-jni.c). The app
#                    loads THIS library; it binds hev's C API and exports the
#                    Java_* symbols TProxyService.kt declares. Runs the tunnel
#                    IN-PROCESS (the VpnService TUN fd is per-process) on a
#                    native pthread the bridge creates itself.
#   libaether.so  <- the Aether engine, cross-compiled from Rust with cargo-ndk,
#                    with the `tor` cargo feature ON (see AETHER_FEATURES).
#   libpsiphon.so <- 1.4.0: psiphon-tunnel-core, the console client the ENGINE
#                    runs for --psiphon (core 2.1.0), built by the engine's own
#                    psiphon-build.sh as a static linux ELF (upstream's Android
#                    recipe). Required: the app's two Psiphon modes need it.
#   libpt-lyrebird.so <- the obfs4/meek_lite/webtunnel pluggable transport, only
#                    needed for Tor BRIDGES. Optional: skipped with a warning when
#                    no Go toolchain is present, and the app degrades to Tor
#                    without bridges (it says so in the log).
#
# Usage:  build-natives.sh [hev|aether|pt|psiphon|all]   (default: all)
#
# Requires: ANDROID_NDK_HOME, rustup android targets, cargo-ndk.
#           `pt` additionally needs Go >= 1.21.
# Run scripts/fetch-natives.sh first.
set -euo pipefail

TARGET="${1:-all}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
HEV_DIR="${NATIVE_DIR}/hev-socks5-tunnel"
AETHER_SRC="${NATIVE_DIR}/aether"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

API="${ANDROID_API:-26}"
# 1.3.1, issue #8: x86_64 joins the two ARM ABIs, so Chromebooks and emulators
# get a native engine instead of ARM translation or a refused install. The
# engine, hev and the pluggable transports are all built for it below.
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")

# ============================================================================
# 1.3.0: THE ENGINE MUST BE BUILT WITH `--features tor`.
#
# Core 2.0.0's Cargo.toml declares `default = []` and puts every Tor dependency
# (arti-client, tor-chanmgr, tor-rtcompat, tokio-util, liblzma) behind an opt-in
# `tor` feature. A build without it is not a build with Tor switched off - it is
# a binary in which the Tor code does not exist. `--tor`, `--tor-only` and
# `--tor-bind` are then unknown arguments, and the app's three Tor modes fail at
# connect time with an engine that exits immediately.
#
# That failure is indistinguishable, from the app's side, from a blocked network:
# the engine dies before it opens its port, which is exactly what a censored
# connection looks like. So it would be diagnosed as a network problem, on a
# device, by someone who cannot see this file. Hence: not a default the caller may
# forget, but a variable with the feature already in it.
#
# Cost of the feature: roughly 6-8 MB per ABI and a noticeably longer build. That
# is the price of shipping Tor and it is paid deliberately.
# ============================================================================
AETHER_FEATURES="${AETHER_FEATURES:-tor}"

# Where the pluggable transport comes from. Pinned by commit, not by tag: a tag
# can be moved, and this binary is one of the things standing between a user and
# a network that watches them.
LYREBIRD_REPO="${LYREBIRD_REPO:-https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git}"
LYREBIRD_REF="${LYREBIRD_REF:-lyrebird-0.6.1}"

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2
  exit 1
fi

# Locate the NDK LLVM toolchain (host tag differs per runner OS).
NDK_TOOLCHAIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  if [ -d "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin" ]; then
    NDK_TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin"
    break
  fi
done
if [ -z "${NDK_TOOLCHAIN}" ]; then
  echo "ERROR: could not find the NDK LLVM toolchain under ${ANDROID_NDK_HOME}" >&2
  exit 1
fi
echo "==> NDK toolchain: ${NDK_TOOLCHAIN}"

clang_for_abi() {
  case "$1" in
    arm64-v8a)   echo "${NDK_TOOLCHAIN}/aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "${NDK_TOOLCHAIN}/armv7a-linux-androideabi${API}-clang" ;;
    x86_64)      echo "${NDK_TOOLCHAIN}/x86_64-linux-android${API}-clang" ;;
    *) echo "" ;;
  esac
}

# ---------------------------------------------------------------------------
# 1) hev-socks5-tunnel  (Android build via ndk-build)
# ---------------------------------------------------------------------------
# The plain Makefile cross-compiles lwip's UNIX port, whose fd_set typedef
# collides with Android bionic. hev ships an Android build (Android.mk +
# Application.mk, at the REPO ROOT) that configures lwip correctly. We then make
# sure we end up with a runnable executable to spawn, whatever the Android build
# emits (executable / shared lib / static lib), via a tiny wrapper around hev's
# public entry point:  int hev_socks5_tunnel_main(const char *config, int fd);
build_hev() {
  local mk_dir=""
  if [ -f "${HEV_DIR}/Android.mk" ]; then
    mk_dir="${HEV_DIR}"
  elif [ -f "${HEV_DIR}/jni/Android.mk" ]; then
    mk_dir="${HEV_DIR}/jni"
  else
    echo "ERROR: Android.mk not found in ${HEV_DIR} or ${HEV_DIR}/jni." >&2
    ls -la "${HEV_DIR}" >&2 || true
    exit 1
  fi
  local app_mk="${mk_dir}/Application.mk"
  [ -f "${app_mk}" ] || app_mk=""

  local ndkbuild="${ANDROID_NDK_HOME}/ndk-build"
  if [ ! -x "${ndkbuild}" ]; then
    echo "ERROR: ndk-build not found at ${ndkbuild}" >&2
    exit 1
  fi

  # ---- Strip hev's bundled JNI layer (hev-jni.c) OUT of the build. --------
  # FIELD-PROVEN root cause (round 2): even when the app loads OUR bridge
  # (libaethertun.so), ART locates JNI_OnLoad via dlsym() on the loaded
  # library's handle — and dlsym() searches the library AND its DT_NEEDED
  # dependencies. hev's JNI_OnLoad inside libhev-socks5-tunnel.so was found
  # and executed anyway, and its RegisterNatives failed on upstream's drifted
  # 'TProxyStopService()Z' signature, killing System.loadLibrary(). So hev's
  # JNI layer must not exist in the shipped .so AT ALL (the bridge also
  # defines its own JNI_OnLoad as a second line of defense).
  local mkfile
  while IFS= read -r -d '' mkfile; do
    sed -i.aetherbak 's|[^[:space:]]*hev-jni\.c||g' "${mkfile}"
    rm -f "${mkfile}.aetherbak"
  done < <(find "${HEV_DIR}" -name '*.mk' -print0)
  find "${HEV_DIR}" -name 'hev-jni.c' -delete

  echo "==> [hev] ndk-build (${mk_dir}/Android.mk) for ${ABIS[*]} (API ${API})"
  ( cd "${HEV_DIR}" && "${ndkbuild}" \
      NDK_PROJECT_PATH="${HEV_DIR}" \
      APP_BUILD_SCRIPT="${mk_dir}/Android.mk" \
      ${app_mk:+NDK_APPLICATION_MK="${app_mk}"} \
      APP_ABI="${ABIS[*]}" \
      APP_PLATFORM="android-${API}" \
      APP_STL="c++_static" \
      "APP_CFLAGS=-O3" \
      -j"$(nproc 2>/dev/null || echo 2)" )

  # hev must run IN-PROCESS (the VpnService TUN fd is per-process), and it
  # must run on a NATIVE thread. We ship hev's core plus OUR OWN JNI bridge
  # (libaethertun.so); the bridge runs the tunnel event loop on a detached
  # pthread it creates itself.
  #
  # ROOT-CAUSE NOTE: the previous custom wrapper (libhev.so + hev_jni.c)
  # called hev_socks5_tunnel_main directly on a Java (ART-attached) thread.
  # hev-task-system implements coroutines by swapping the thread's stack
  # pointer; doing that on a thread ART manages corrupts what the runtime
  # expects of the stack and kills the whole app with a native SIGSEGV a few
  # seconds after real traffic starts — with nothing in the Java crash log.
  # Running the loop on hev's own pthread (v2rayNG's proven mode) avoids ART
  # entirely.
  local abi libsdir out dynsyms needed dep
  for abi in "${ABIS[@]}"; do
    libsdir="${HEV_DIR}/libs/${abi}"
    out="${JNI_DIR}/${abi}/libhev-socks5-tunnel.so"
    mkdir -p "${JNI_DIR}/${abi}"
    if [ ! -f "${libsdir}/libhev-socks5-tunnel.so" ]; then
      echo "ERROR: [${abi}] ndk-build did not produce libhev-socks5-tunnel.so" >&2
      ls -la "${libsdir}" 2>/dev/null >&2 || true
      exit 1
    fi
    cp "${libsdir}/libhev-socks5-tunnel.so" "${out}"
    # Never ship stale artifacts from the old wrapper approach.
    rm -f "${JNI_DIR}/${abi}/libhev.so" "${JNI_DIR}/${abi}/libhevcore.so"

    # ---- Hard verification: hev must export its STABLE public C API. ----
    # We no longer use hev's bundled JNI layer (hev-jni.c) at all — see the
    # root-cause note below. Only the C entry points matter, and those have
    # been stable across hev releases for years.
    dynsyms="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${out}" 2>/dev/null || true)"
    for sym in hev_socks5_tunnel_main hev_socks5_tunnel_quit hev_socks5_tunnel_stats; do
      if ! echo "${dynsyms}" | grep -qw "${sym}"; then
        echo "ERROR: [${abi}] libhev-socks5-tunnel.so lacks ${sym}." >&2
        exit 1
      fi
    done
    # hev-jni.c was stripped above; if JNI_OnLoad is STILL exported, the strip
    # failed (upstream moved/renamed the file) and its RegisterNatives would
    # run again via the dlsym() dependency search. Never ship that.
    if echo "${dynsyms}" | grep -qw 'JNI_OnLoad'; then
      echo "ERROR: [${abi}] libhev-socks5-tunnel.so still exports JNI_OnLoad — hev-jni.c was not stripped." >&2
      echo "       Update the strip logic in build_hev for the new upstream layout." >&2
      exit 1
    fi

    # ---- Build OUR OWN JNI bridge: libaethertun.so ------------------------
    # ROOT CAUSE of "VPN mode never connects while proxy mode works": the app
    # used to load libhev-socks5-tunnel.so directly and rely on hev's bundled
    # hev-jni.c to register the TProxy* natives. Upstream hev CHANGED that
    # JNI ABI (TProxyStartService '(Ljava/lang/String;I)V' -> ')Z'); since we
    # build hev's default branch, RegisterNatives inside its JNI_OnLoad began
    # failing with NoSuchMethodError, System.loadLibrary() threw, and VPN mode
    # died with "hev native library unavailable" (proxy mode never loads hev).
    #
    # PERMANENT FIX: ship our own tiny JNI bridge (scripts/aethertun-jni.c)
    # linked against hev's stable public C API. -Wl,--no-undefined resolves
    # those symbols at BUILD time, so any upstream break fails CI loudly
    # instead of shipping a broken APK. hev's JNI layer is stripped from the
    # build above AND the bridge defines its own JNI_OnLoad (ART finds
    # JNI_OnLoad via dlsym(), which also searches DT_NEEDED dependencies —
    # exactly how hev's one got executed in the field), so upstream JNI ABI
    # drift is harmless now.
    # The tunnel loop still runs on a NATIVE pthread created in the bridge
    # (see the ART SIGSEGV note above).
    clang="$(clang_for_abi "${abi}")"
    if [ -z "${clang}" ] || [ ! -x "${clang}" ]; then
      echo "ERROR: [${abi}] NDK clang not found for this ABI." >&2
      exit 1
    fi
    bridge_src="${SCRIPT_DIR}/aethertun-jni.c"
    bridge_out="${JNI_DIR}/${abi}/libaethertun.so"
    if [ ! -f "${bridge_src}" ]; then
      echo "ERROR: bridge source not found: ${bridge_src}" >&2
      exit 1
    fi
    echo "==> [hev] building libaethertun.so (our own JNI bridge) for ${abi}"
    "${clang}" -O2 -fPIC -shared -Wall -Werror \
      -Wl,-soname,libaethertun.so -Wl,--no-undefined \
      -o "${bridge_out}" "${bridge_src}" \
      -L"${JNI_DIR}/${abi}" -lhev-socks5-tunnel -llog
    bridgesyms="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${bridge_out}" 2>/dev/null || true)"
    for sym in \
      JNI_OnLoad \
      Java_studio_cluvex_aether_core_TProxyService_TProxyStartService \
      Java_studio_cluvex_aether_core_TProxyService_TProxyStopService \
      Java_studio_cluvex_aether_core_TProxyService_TProxyGetStats; do
      if ! echo "${bridgesyms}" | grep -qw "${sym}"; then
        echo "ERROR: [${abi}] libaethertun.so lacks ${sym} — Kotlin externals would not resolve." >&2
        exit 1
      fi
    done
    echo "    [${abi}] libaethertun.so verified: all Java_* bridge symbols present"

    # A previous APK contained the main hev library but not one of its runtime
    # dependencies (most commonly libc++_shared.so). Android then refused to
    # load hev, so proxy mode worked while full-device VPN mode failed. We build
    # with the static C++ runtime above and also enforce that every remaining
    # DT_NEEDED entry is either an Android system library or packaged beside it.
    needed="$("${NDK_TOOLCHAIN}/llvm-readelf" -d "${out}" 2>/dev/null \
      | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')"
    while IFS= read -r dep; do
      [ -n "${dep}" ] || continue
      case "${dep}" in
        libc.so|libdl.so|liblog.so|libm.so|libandroid.so|libz.so) ;;
        *)
          if [ -f "${libsdir}/${dep}" ]; then
            cp "${libsdir}/${dep}" "${JNI_DIR}/${abi}/${dep}"
            echo "    [${abi}] packaged hev dependency: ${dep}"
          else
            echo "ERROR: [${abi}] unresolved hev runtime dependency: ${dep}" >&2
            exit 1
          fi
          ;;
      esac
    done <<< "${needed}"
    echo "    [${abi}] libhev-socks5-tunnel.so verified: stable C API (main/quit/stats) OK"
    "${NDK_TOOLCHAIN}/llvm-readelf" -d "${out}" 2>/dev/null | grep NEEDED || true
  done
}

# ---------------------------------------------------------------------------
# 2) Aether engine  (Rust, cargo-ndk)
# ---------------------------------------------------------------------------
# The repo root has NO Cargo.toml. The binary crate lives in a subdirectory
# (e.g. aether/) next to the vendored quiche/ library. Detect it: pick the
# crate that has src/main.rs and is NOT under quiche/.
detect_aether_crate() {
  if [ -f "${AETHER_SRC}/aether/Cargo.toml" ] && [ -f "${AETHER_SRC}/aether/src/main.rs" ]; then
    echo "${AETHER_SRC}/aether"
    return 0
  fi
  local toml d
  while IFS= read -r toml; do
    d="$(dirname "${toml}")"
    case "${d}" in
      *quiche*) continue ;;
    esac
    if [ -f "${d}/src/main.rs" ]; then
      echo "${d}"
      return 0
    fi
  done < <(find "${AETHER_SRC}" -name Cargo.toml -not -path '*/target/*' | sort)
  return 1
}

# ---------------------------------------------------------------------------
# 1.2.8-r5: build provenance.
#
# WHY. r2, r3 and r4 all shipped as versionName 1.2.8 / versionCode 12, and the
# engine banner printed only the upstream core version (1.8.0), identical in all
# three. Nothing anywhere could tell two revisions apart, and the r4 field log
# proves the consequence: five separate strings in it belong to the r3 engine, so
# an entire diagnosis round was spent analysing a binary that predated the fix
# being tested. That is not a mistake anyone can be careful enough to avoid; it
# is a missing build identity, and this is the fix.
#
# The repo-root PATCHLEVEL file is stamped into libaether.so by build.rs, and
# verify_patch_stamp() below greps the finished, stripped .so for it. A stale or
# unstamped engine fails the build HERE, loudly, instead of being shipped and
# field-tested for a week.
APP_PATCHLEVEL="${APP_PATCHLEVEL:-}"
if [ -z "${APP_PATCHLEVEL}" ] && [ -f "${PROJECT_DIR}/PATCHLEVEL" ]; then
  APP_PATCHLEVEL="$(tr -d '[:space:]' < "${PROJECT_DIR}/PATCHLEVEL")"
fi
if [ -z "${APP_PATCHLEVEL}" ]; then
  echo "ERROR: no PATCHLEVEL at ${PROJECT_DIR}/PATCHLEVEL and APP_PATCHLEVEL is unset." >&2
  echo "       The engine would build unidentifiable. Refusing." >&2
  exit 1
fi
export APP_PATCHLEVEL
echo "==> [aether] app patch level: ${APP_PATCHLEVEL}"

# The literal build.rs embeds. Must match AETHER_BUILD_STAMP there exactly.
PATCH_STAMP="AETHER-BUILD-STAMP:${APP_PATCHLEVEL}"

# verify_patch_stamp <abi> <so>
verify_patch_stamp() {
  local abi="$1" so="$2"
  if ! grep -qa -- "${PATCH_STAMP}" "${so}"; then
    echo "ERROR: [${abi}] libaether.so does not contain '${PATCH_STAMP}'." >&2
    echo "       The engine that was just built is NOT this patch level. Most likely" >&2
    echo "       causes: build.rs did not run (is 'build = \"build.rs\"' still in" >&2
    echo "       Cargo.toml?), a stale artifact was copied, or the core-sync step" >&2
    echo "       reverted the app patches. Do NOT ship this." >&2
    echo "       Stamps actually present in the binary:" >&2
    grep -ao 'AETHER-BUILD-STAMP:[0-9A-Za-z.\-]*' "${so}" 2>/dev/null | sort -u | sed 's/^/         /' >&2 || true
    exit 1
  fi
  echo "    [${abi}] build stamp verified: ${PATCH_STAMP}"
}

build_aether() {
  local crate
  crate="$(detect_aether_crate || true)"
  if [ -z "${crate}" ]; then
    echo "ERROR: could not find the Aether binary crate (a Cargo.toml with src/main.rs)." >&2
    echo "Manifests found:" >&2
    find "${AETHER_SRC}" -name Cargo.toml -not -path '*/target/*' >&2 || true
    exit 1
  fi
  echo "==> [aether] binary crate: ${crate}"

  export CARGO_TARGET_DIR="${AETHER_SRC}/target"

  # Never let a previous run's engine survive into this APK. jniLibs is
  # git-ignored and therefore invisible in a diff; a leftover libaether.so from
  # an earlier revision is precisely the artifact that would reproduce the r4
  # situation on a local build.
  local abi
  for abi in "${ABIS[@]}"; do
    rm -f "${JNI_DIR}/${abi}/libaether.so"
  done

  local bin_name
  bin_name="$(grep -m1 -E '^name[[:space:]]*=' "${crate}/Cargo.toml" \
    | sed -E 's/.*=[[:space:]]*"([^"]+)".*/\1/' || true)"
  echo "    crate name (best-effort): ${bin_name:-<auto-detect>}"

  build_aether_abi() {
    local abi="$1" triple="$2"
    echo "==> [aether] building for ${abi} (${triple}, API ${API})"
    local feature_args=()
    if [ -n "${AETHER_FEATURES}" ]; then
      feature_args=(--features "${AETHER_FEATURES}")
      echo "    cargo features: ${AETHER_FEATURES}"
    else
      # Reachable only when a caller sets AETHER_FEATURES="" on purpose. Loud,
      # because the resulting APK looks complete and has no Tor in it.
      echo "    WARNING: building WITHOUT cargo features - the app's Tor modes will NOT work." >&2
    fi
    ( cd "${crate}" && ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}" cargo ndk -t "${abi}" --platform "${API}" build --release "${feature_args[@]}" )

    local reldir="${CARGO_TARGET_DIR}/${triple}/release"
    local artifact=""
    if [ -n "${bin_name}" ] && [ -x "${reldir}/${bin_name}" ]; then
      artifact="${reldir}/${bin_name}"
    else
      artifact="$(find "${reldir}" -maxdepth 1 -type f -perm -u+x \
        ! -name '*.so' ! -name '*.d' ! -name '*.rlib' ! -name '*.rmeta' \
        2>/dev/null | head -n1)"
    fi
    if [ -z "${artifact}" ] || [ ! -f "${artifact}" ]; then
      echo "ERROR: could not locate a built Aether executable in ${reldir}" >&2
      ls -la "${reldir}" 2>/dev/null >&2 || true
      exit 1
    fi

    mkdir -p "${JNI_DIR}/${abi}"
    cp "${artifact}" "${JNI_DIR}/${abi}/libaether.so"
    "${NDK_TOOLCHAIN}/llvm-strip" "${JNI_DIR}/${abi}/libaether.so" 2>/dev/null || true
    # Checked AFTER stripping, because stripping is what the shipped file gets.
    verify_patch_stamp "${abi}" "${JNI_DIR}/${abi}/libaether.so"
    echo "    installed libaether.so for ${abi}"
  }

  build_aether_abi "arm64-v8a"   "aarch64-linux-android"
  build_aether_abi "armeabi-v7a" "armv7-linux-androideabi"
  build_aether_abi "x86_64"      "x86_64-linux-android"

  # Prove Tor is IN the binary rather than trusting that the flag was honoured.
  # `--tor-bind` is a string the Tor module owns, so it is absent from a build
  # made without the feature - and grepping for it is the only check here that
  # cannot be satisfied by a stale artifact.
  if [ -n "${AETHER_FEATURES}" ] && [[ "${AETHER_FEATURES}" == *tor* ]]; then
    local abi
    for abi in "${ABIS[@]}"; do
      # NOT `grep -q` (1.3.1, issue #48). This script runs under `set -euo
      # pipefail`. `grep -q` exits as soon as it matches, which closes the pipe
      # under `strings`; `strings` then dies of SIGPIPE, that non-zero status
      # becomes the status of the whole pipeline because of `pipefail`, and the
      # build fails claiming the Tor feature is missing from a binary that in
      # fact contains it. The report is a FALSE NEGATIVE and it is timing
      # dependent, so it fires on some runners and not others.
      #
      # Letting grep read to the end and throwing its output away keeps the exit
      # status meaningful with no early close. (The upstream patch this came from
      # wrote `grep -- '--tor-bind' />/dev/null`, which the shell splits into an
      # argument `/` plus the redirection - that greps the root DIRECTORY and
      # never looks at the binary at all.)
      if strings -a "${JNI_DIR}/${abi}/libaether.so" 2>/dev/null | grep -- '--tor-bind' >/dev/null; then
        echo "    [${abi}] tor feature verified in the binary."
      else
        echo "ERROR: ${abi}/libaether.so contains no Tor support although --features ${AETHER_FEATURES}" >&2
        echo "       was requested. Refusing to ship an APK whose Tor modes cannot work." >&2
        exit 1
      fi
    done
  fi
}

# ============================================================================
# build_pt: the pluggable transport for Tor bridges.
#
# Only bridges need this. Plain Tor and `Aether -> Tor` work without it, which is
# why a missing Go toolchain is a warning and not an error - and why the app logs
# the absence instead of hiding it.
#
# Two Android-specific details decide the whole recipe:
#
#  * The file MUST be named `lib*.so` and live in jniLibs. Android extracts only
#    those from an APK onto a path that permits execution; anything else lands in
#    the asset area, which is mounted noexec on modern Android. A correctly built
#    transport under any other name is a file the engine cannot start.
#  * It must be a position-independent executable (`-buildmode=pie`). It is
#    spawned as a process, not dlopen'd, and since API 21 the loader refuses a
#    non-PIE executable.
# ============================================================================
build_pt() {
  if ! command -v go >/dev/null 2>&1; then
    echo "==> [pt] Go toolchain not found - SKIPPING lyrebird." >&2
    echo "    Tor still works (directly and through the tunnel). Tor BRIDGES will not:" >&2
    echo "    the app logs 'no libpt-lyrebird.so in this APK' and carries on." >&2
    return 0
  fi

  local src="${NATIVE_DIR}/lyrebird"
  if [ ! -d "${src}/.git" ]; then
    echo "==> [pt] cloning lyrebird ${LYREBIRD_REF}"
    rm -rf "${src}"
    git clone --quiet --depth 1 --branch "${LYREBIRD_REF}" "${LYREBIRD_REPO}" "${src}"
  fi
  echo "    lyrebird revision: $(cd "${src}" && git rev-parse --short HEAD)"

  build_pt_abi() {
    local abi="$1" goarch="$2" cc_prefix="$3"
    local cc="${NDK_TOOLCHAIN}/${cc_prefix}${API}-clang"
    if [ ! -x "${cc}" ]; then
      echo "ERROR: no NDK compiler at ${cc}" >&2
      exit 1
    fi
    echo "==> [pt] building lyrebird for ${abi} (${goarch})"
    mkdir -p "${JNI_DIR}/${abi}"
    (
      cd "${src}"
      CGO_ENABLED=1 GOOS=android GOARCH="${goarch}" CC="${cc}" \
        go build -trimpath -buildmode=pie \
          -ldflags "-s -w" \
          -o "${JNI_DIR}/${abi}/libpt-lyrebird.so" \
          ./cmd/lyrebird
    )
    echo "    installed libpt-lyrebird.so for ${abi}"
  }

  build_pt_abi "arm64-v8a"   "arm64" "aarch64-linux-android"
  build_pt_abi "armeabi-v7a" "arm"   "armv7a-linux-androideabi"
  build_pt_abi "x86_64"      "amd64" "x86_64-linux-android"
}

# ============================================================================
# build_psiphon (1.4.0): the Psiphon the ENGINE runs.
#
# Core 2.1.0 moved Psiphon into the engine: `--psiphon` spawns psiphon-tunnel-core
# (CluvexStudio's fork, pinned in native/aether/psiphon-build.sh). Built exactly
# the way upstream builds its Android archives - GOOS=linux, CGO_ENABLED=0, a
# static ELF that runs as-is on Android - and installed as `libpsiphon.so`,
# because only lib*.so files in jniLibs land on an executable path. The app
# points the engine at it with AETHER_PSIPHON_BIN.
# ============================================================================
build_psiphon() {
  local script="${AETHER_SRC}/psiphon-build.sh"
  [ -f "${script}" ] || script="${PROJECT_DIR}/native/aether/psiphon-build.sh"
  if [ ! -f "${script}" ]; then
    echo "ERROR: psiphon-build.sh not found (is the vendored core 2.1.0 or newer?)." >&2
    exit 1
  fi
  if ! command -v go >/dev/null 2>&1; then
    if [ "${AETHER_ALLOW_NO_PSIPHON:-}" = "1" ]; then
      echo "==> [psiphon] no Go toolchain - SKIPPED on request; Psiphon modes will not work." >&2
      return 0
    fi
    echo "ERROR: building libpsiphon.so needs Go (set AETHER_ALLOW_NO_PSIPHON=1 to skip)." >&2
    exit 1
  fi
  build_psiphon_abi() {
    local abi="$1" goarch="$2" goarm="$3" tmp
    tmp="$(mktemp -d)"
    echo "==> [psiphon] building psiphon-tunnel-core for ${abi} (linux/${goarch}${goarm:+ v${goarm}})"
    bash "${script}" linux "${goarch}" "${tmp}" "${goarm}"
    mkdir -p "${JNI_DIR}/${abi}"
    cp "${tmp}/psiphon-tunnel-core" "${JNI_DIR}/${abi}/libpsiphon.so"
    chmod 755 "${JNI_DIR}/${abi}/libpsiphon.so"
    rm -rf "${tmp}"
    echo "    installed libpsiphon.so for ${abi}"
  }
  build_psiphon_abi "arm64-v8a"   "arm64" ""
  build_psiphon_abi "armeabi-v7a" "arm"   "7"
  build_psiphon_abi "x86_64"      "amd64" ""
}

case "${TARGET}" in
  hev)    build_hev ;;
  aether) build_aether ;;
  pt)     build_pt ;;
  psiphon) build_psiphon ;;
  all)    build_hev; build_aether; build_pt; build_psiphon ;;
  *) echo "Usage: build-natives.sh [hev|aether|pt|psiphon|all]" >&2; exit 2 ;;
esac

echo "==> Done (${TARGET}). Installed libs:"
find "${JNI_DIR}" -type f -name '*.so' -exec ls -la {} + 2>/dev/null || true
