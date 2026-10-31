#!/usr/bin/env bash
# sync-core.sh - DISABLED (1.6.0).
#
# This script used to pull the newest CluvexStudio/Aether release from GitHub
# on every CI run and swap it into native/aether. Automatic core updates are
# turned off on purpose: the vendored core (native/aether/CORE_VERSION) only
# changes through a reviewed, manual commit. The script is kept as a harmless
# stub so old docs or muscle memory cannot silently re-enable the old behaviour.
set -euo pipefail
echo "[sync-core] automatic core update is DISABLED; vendored core stays at $(cat "$(dirname "$0")/../native/aether/CORE_VERSION" 2>/dev/null || echo unknown)."
exit 0
