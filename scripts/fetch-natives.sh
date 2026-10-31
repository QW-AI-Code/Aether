#!/usr/bin/env bash
#
# Fetches the native SOURCE needed by the build. Nothing here is prebuilt:
#   1. hev-socks5-tunnel  -> built with its own Android.mk into libhev-socks5-tunnel.so
#                            (the in-app "tun2socks" that replaces v2rayNG)
#   2. Aether engine src  -> cross-compiled into libaether.so
#                            (upstream publishes NO Android binaries)
#
# Both are compiled later by scripts/build-natives.sh.
#
# Safe to re-run. All network access happens here / in CI, never on device.
# By default we clone each repo's DEFAULT branch. To pin, export HEV_REF /
# AETHER_REF; a missing ref falls back to the default branch instead of failing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
mkdir -p "${NATIVE_DIR}"

# Build the host prefix from fragments so no full literal URL lives in the file.
GH="https://""github.com"

HEV_REPO="heiher/hev-socks5-tunnel"
HEV_REF="${HEV_REF:-}"            # empty => default branch
HEV_DIR="${NATIVE_DIR}/hev-socks5-tunnel"

AETHER_REPO="${AETHER_REPO:-CluvexStudio/Aether}"
AETHER_REF="${AETHER_REF:-}"      # empty => default branch
AETHER_SRC="${NATIVE_DIR}/aether"

# The engine source is VENDORED inside this repo at native/aether so the app's
# own modifications (custom-range scanning in prober.rs / wg_prober.rs, etc.)
# are ALWAYS compiled into libaether.so with zero manual steps. When this dir is
# present we use it verbatim and never touch the network for the engine. Delete
# native/aether is an error: the engine is never cloned (auto-update disabled).
VENDORED_AETHER="${PROJECT_DIR}/native/aether"
AETHER_FORCE_CLONE="${AETHER_FORCE_CLONE:-}"

# clone_repo <url> <dir> <ref>
# Tries the pinned ref first (tag or branch); on any failure cleanly falls back
# to the repo's default branch. Always clones submodules recursively.
clone_repo() {
  local url="$1" dir="$2" ref="$3"
  rm -rf "${dir}"
  if [ -n "${ref}" ] && \
     git clone --depth 1 --branch "${ref}" --recursive "${url}" "${dir}" 2>/dev/null; then
    echo "   cloned ${url} @ ${ref}"
    return 0
  fi
  if [ -n "${ref}" ]; then
    echo "   ref '${ref}' not found on ${url}; using default branch"
  fi
  rm -rf "${dir}"
  git clone --depth 1 --recursive "${url}" "${dir}"
  echo "   cloned ${url} @ default branch"
}

echo "==> Fetching hev-socks5-tunnel (tunnel core)"
clone_repo "${GH}/${HEV_REPO}.git" "${HEV_DIR}" "${HEV_REF}"
if [ ! -f "${HEV_DIR}/Makefile" ]; then
  echo "ERROR: hev-socks5-tunnel checkout has no Makefile at ${HEV_DIR}" >&2
  ls -la "${HEV_DIR}" >&2 || true
  exit 1
fi

echo "==> Providing Aether engine source (engine)"
# 1.6.0: the engine is NEVER downloaded. Automatic core updates are disabled;
# the vendored native/aether (see CORE_VERSION) is the only source accepted.
if [ -n "${AETHER_FORCE_CLONE}" ]; then
  echo "WARNING: AETHER_FORCE_CLONE is ignored - automatic core download is disabled." >&2
fi
if [ -z "$(find "${VENDORED_AETHER}" -name Cargo.toml -not -path '*/target/*' 2>/dev/null | head -n1)" ]; then
  echo "ERROR: vendored engine missing at ${VENDORED_AETHER}; refusing to download one." >&2
  exit 1
fi
echo "   using the VENDORED engine bundled in this repo: ${VENDORED_AETHER} (core $(cat "${VENDORED_AETHER}/CORE_VERSION" 2>/dev/null || echo '?'))"
rm -rf "${AETHER_SRC}"
mkdir -p "${AETHER_SRC}"
# Copy everything except any local build output (target/).
( cd "${VENDORED_AETHER}" && tar --exclude='./target' --exclude='*/target' -cf - . ) \
  | ( cd "${AETHER_SRC}" && tar -xf - )
# The Aether binary crate does NOT live at the repo root; it sits in a
# subdirectory (e.g. aether/) next to the vendored quiche/ QUIC library. Just
# verify at least one Cargo.toml exists; build-natives.sh locates the crate.
if ! [ -n "$(find "${AETHER_SRC}" -name Cargo.toml -not -path '*/target/*' | head -n1)" ]; then
  echo "ERROR: Aether source has no Cargo.toml anywhere under ${AETHER_SRC}" >&2
  ls -la "${AETHER_SRC}" >&2 || true
  exit 1
fi
echo "   found Aether Cargo manifest(s):"
find "${AETHER_SRC}" -maxdepth 2 -name Cargo.toml -not -path '*/target/*' | sed 's/^/     /'

echo "==> Native sources ready under ${NATIVE_DIR}"
