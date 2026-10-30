#!/usr/bin/env bash
#
# core-rollback.sh - undo the automatic engine upgrade performed by
# scripts/sync-core.sh and restore the exact core the repository shipped
# before this build started.
#
# WHY THIS EXISTS (1.2.2)
# -----------------------
# The core auto-upgrade is a convenience. A convenience must never be able to
# break a release. Upstream can rename a type, add a field to a struct or add
# a parameter to a function at any time, and no amount of textual merging can
# guarantee that the result still compiles - that is exactly how core 1.4.0
# broke the first 1.2.2 build:
#
#   error[E0063]: missing fields `expected_pins` and `pin_endpoint`
#   error[E0061]: this function takes 8 arguments but 7 arguments were supplied
#
# So the CI "Build engine (Aether)" step is allowed to fail ONCE: it calls this
# script, which puts the previously vendored core back, and then builds again.
# The release still ships - just with the engine the repo already had - and the
# run is annotated so the upgrade can be finished by hand.
#
# Everything sync-core.sh wrote is reverted: the sources, CORE_VERSION, the
# baseline cache and the changelog lines in both READMEs.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CORE_DIR="native/aether"
PREV_DIR="native/.core-prev"
STATE_FILE="native/.core-sync-state"

log() { printf '[core-rollback] %s\n' "$*"; }

if [[ ! -d "$PREV_DIR/aether" ]]; then
  log "No snapshot of a previous core exists - nothing to roll back."
  exit 1
fi

PREV_VERSION="unknown"
NEW_VERSION="unknown"
if [[ -f "$STATE_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$STATE_FILE"
  PREV_VERSION="${CORE_PREV_VERSION:-unknown}"
  NEW_VERSION="${CORE_NEW_VERSION:-unknown}"
fi

log "Restoring the vendored core ${PREV_VERSION} (the upgrade to ${NEW_VERSION} did not build)."

rm -rf "$CORE_DIR"
cp -R "$PREV_DIR" "$CORE_DIR"

# Drop the changelog lines the sync step inserted; they describe an upgrade
# that is no longer part of this build.
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git checkout -- README.md README.fa.md 2>/dev/null || true
fi

# Make sure a later step cannot commit the reverted upgrade.
rm -rf "$PREV_DIR"
rm -f "$STATE_FILE"

log "Core ${PREV_VERSION} restored. The build will continue with it."

if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
  printf '::warning::Engine core %s failed to compile and was rolled back to %s. The APK was built with core %s. Re-apply the app engine patches against %s and bump native/aether/CORE_VERSION manually.\n' \
    "$NEW_VERSION" "$PREV_VERSION" "$PREV_VERSION" "$NEW_VERSION"
fi
