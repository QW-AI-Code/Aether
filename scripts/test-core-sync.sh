#!/usr/bin/env bash
#
# test-core-sync.sh - offline regression test for scripts/sync-core.sh.
#
# Reproduces the exact failure that broke the first 1.2.2 build: upstream
# changes a file that this app also patches (a new struct field / a new
# function argument), and the sync step must NOT throw the upstream change
# away. Runs entirely against a local git repository - no network needed.
#
# Scenarios covered:
#   1. Upstream changes a patched file  -> three-way merge keeps BOTH sides.
#   2. Upstream rewrites it beyond merge -> pure upstream is kept (compiles),
#                                           and the run is flagged, not failed.
#   3. Downgrade / same version         -> no-op.
#   4. Polluted merge baseline          -> the base is rebuilt from the app's
#                                          AETHER-APP-PATCH markers and the
#                                          patch still survives (the 1.2.5 bug).
#
# Usage: bash scripts/test-core-sync.sh
#
set -euo pipefail

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sync-core.sh"

pass=0
fail=0
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; fail=$((fail + 1)); }
check() { if [[ "$2" == "yes" ]]; then ok "$1"; else bad "$1"; fi; }

# --------------------------------------------------------------- fixtures
# A miniature engine file that mimics prober.rs: an upstream part (a struct
# literal that upstream will later grow a field on) and an app patch.
upstream_v1() {
  cat <<'RS'
pub fn probe() {
    let cfg = H2TunnelConfig {
        peer: addr,
        sni: sni.clone(),
        quiet: true,
    };
}
RS
}

# Upstream 1.5: two new required fields (this is what E0063 was about).
upstream_v2() {
  cat <<'RS'
pub fn probe() {
    let cfg = H2TunnelConfig {
        peer: addr,
        sni: sni.clone(),
        expected_pins: pins.clone(),
        pin_endpoint: true,
        quiet: true,
    };
}
RS
}

# The app patch: an extra function appended by this repo (manual range mode).
app_patch_marker="fn custom_cidrs_v4()"
ours_v1() {
  upstream_v1
  cat <<'RS'

/// APP PATCH: custom IPv4 scan ranges (AETHER_SCAN_CIDRS).
fn custom_cidrs_v4() -> Option<Vec<String>> {
    std::env::var("AETHER_SCAN_CIDRS").ok()
}
RS
}

# 1.2.6: the same patch, wrapped in the markers the real engine patches use.
ours_marked() {
  upstream_v1
  cat <<'RS'

// >>> AETHER-APP-PATCH manual-range
/// APP PATCH: custom IPv4 scan ranges (AETHER_SCAN_CIDRS).
fn custom_cidrs_v4() -> Option<Vec<String>> {
    std::env::var("AETHER_SCAN_CIDRS").ok()
}
// <<< AETHER-APP-PATCH manual-range
RS
}

make_upstream_repo() {
  local root="$1"
  rm -rf "$root"
  mkdir -p "$root/CluvexStudio/Aether.git"
  local wt="$TMP/upstream-wt"
  rm -rf "$wt"
  mkdir -p "$wt/aether/src"
  git -C "$wt" init -q
  git -C "$wt" config user.email t@t.t
  git -C "$wt" config user.name t

  upstream_v1 > "$wt/aether/src/prober.rs"
  echo "fn wg() {}" > "$wt/aether/src/wg_prober.rs"
  git -C "$wt" add -A && git -C "$wt" commit -qm v1 && git -C "$wt" tag 1.4

  "$2" > "$wt/aether/src/prober.rs"
  git -C "$wt" add -A && git -C "$wt" commit -qm v2 && git -C "$wt" tag 1.5

  git clone -q --bare "$wt" "$root/CluvexStudio/Aether.git"
}

make_app_repo() {
  local app="$TMP/app"
  rm -rf "$app"
  mkdir -p "$app/native/aether/aether/src" "$app/scripts" "$app/app/src/main/java"
  cp "$SCRIPT" "$app/scripts/sync-core.sh"
  echo "1.4" > "$app/native/aether/CORE_VERSION"
  if [[ "${1:-plain}" == "marked" ]]; then
    ours_marked > "$app/native/aether/aether/src/prober.rs"
  else
    ours_v1 > "$app/native/aether/aether/src/prober.rs"
  fi
  echo "fn wg() {}" > "$app/native/aether/aether/src/wg_prober.rs"
  printf '## v1.2.2\n<!-- core-sync:en -->\n' > "$app/README.md"
  printf '## v1.2.2\n<!-- core-sync:fa -->\n' > "$app/README.fa.md"
  git -C "$app" init -q
  git -C "$app" config user.email t@t.t
  git -C "$app" config user.name t
  git -C "$app" add -A
  git -C "$app" commit -qm init
  echo "$app"
}

run_sync() {
  ( cd "$1" && CORE_TARGET="$2" CORE_GIT_BASE="file://$TMP/upstream" \
      bash scripts/sync-core.sh ) 2>&1
}

# ------------------------------------------------------- 1. merge scenario
echo "[1] upstream adds fields to a file the app patches"
make_upstream_repo "$TMP/upstream" upstream_v2
APP="$(make_app_repo)"
out="$(run_sync "$APP" 1.5)"
echo "$out" | sed 's/^/      /'
res="$APP/native/aether/aether/src/prober.rs"

grep -q 'expected_pins' "$res" && r=yes || r=no
check "upstream's new fields survived the upgrade" "$r"
grep -qF "$app_patch_marker" "$res" && r=yes || r=no
check "the app patch survived the upgrade" "$r"
grep -q '<<<<<<<\|>>>>>>>' "$res" && r=no || r=yes
check "no conflict markers were written into the source" "$r"
[[ "$(tr -d '[:space:]' < "$APP/native/aether/CORE_VERSION")" == "1.5" ]] && r=yes || r=no
check "CORE_VERSION bumped to 1.5" "$r"
[[ -f "$APP/native/aether/.upstream-baseline/aether/src/prober.rs" ]] && r=yes || r=no
check "baseline cached for the next upgrade" "$r"
upstream_v2 > "$TMP/expected_v2.rs"
if diff -q "$APP/native/aether/.upstream-baseline/aether/src/prober.rs" "$TMP/expected_v2.rs" >/dev/null; then r=yes; else r=no; fi
check "cached baseline is the pristine upstream file" "$r"
[[ -d "$APP/native/.core-prev/aether" ]] && r=yes || r=no
check "rollback snapshot of the previous core was taken" "$r"
[[ -f "$APP/native/.core-sync-state" ]] && r=yes || r=no
check "state marker written for the CI commit step" "$r"
grep -q 'Engine (core) upgraded to v1.5' "$APP/README.md" && r=yes || r=no
check "English changelog documented the upgrade" "$r"
grep -q '1.5' "$APP/README.fa.md" && r=yes || r=no
check "Persian changelog documented the upgrade" "$r"
echo "$out" | grep -qi 'invalid option' && r=no || r=yes
check "no 'grep: invalid option' noise" "$r"

# --------------------------------------------------- 2. unmergeable rewrite
echo
echo "[2] upstream rewrites the patched file completely"
make_upstream_repo "$TMP/upstream" upstream_v2
APP2="$(make_app_repo)"
# Replace the 1.5 content with something that overlaps our patch region.
wt="$TMP/rewrite"
rm -rf "$wt"
git clone -q "$TMP/upstream/CluvexStudio/Aether.git" "$wt"
git -C "$wt" config user.email t@t.t
git -C "$wt" config user.name t
git -C "$wt" checkout -q 1.4
printf 'pub fn probe() { totally_rewritten(); }\nfn custom_cidrs_v4() -> u8 { 0 }\n' \
  > "$wt/aether/src/prober.rs"
git -C "$wt" add -A
git -C "$wt" commit -qm rewrite
git -C "$wt" tag -f 1.6 >/dev/null
git -C "$wt" push -q --tags --force origin HEAD:refs/heads/rewritten
out2="$(run_sync "$APP2" 1.6)"
echo "$out2" | sed 's/^/      /'
res2="$APP2/native/aether/aether/src/prober.rs"
grep -q '<<<<<<<' "$res2" && r=no || r=yes
check "never leaves conflict markers in a shipped source file" "$r"
grep -q 'totally_rewritten' "$res2" && r=yes || r=no
check "falls back to the compilable upstream file" "$r"

# ------------------------------------------------------------ 3. no-op path
echo
echo "[3] upstream is not newer"
APP3="$(make_app_repo)"
out3="$(run_sync "$APP3" 1.3)"
echo "$out3" | sed 's/^/      /'
echo "$out3" | grep -q 'already current' && r=yes || r=no
check "a downgrade is refused" "$r"
[[ "$(tr -d '[:space:]' < "$APP3/native/aether/CORE_VERSION")" == "1.4" ]] && r=yes || r=no
check "vendored core left untouched" "$r"

# -------------------------------------------- 4. polluted baseline self-heal
echo
echo "[4] the cached baseline is the PATCHED file (the bug shipped in 1.2.5)"
make_upstream_repo "$TMP/upstream" upstream_v2
APP4="$(make_app_repo marked)"
# Reproduce the pollution exactly: baseline = our patched file, not upstream's.
mkdir -p "$APP4/native/aether/.upstream-baseline/aether/src"
cp "$APP4/native/aether/aether/src/prober.rs" \
   "$APP4/native/aether/.upstream-baseline/aether/src/prober.rs"
out4="$(run_sync "$APP4" 1.5)"
echo "$out4" | sed 's/^/      /'
res4="$APP4/native/aether/aether/src/prober.rs"

echo "$out4" | grep -q 'Rebuilt a pristine merge base' && r=yes || r=no
check "the polluted baseline was detected and rebuilt from the markers" "$r"
grep -qF 'AETHER-APP-PATCH' "$res4" && r=yes || r=no
check "the app patch survived a polluted baseline" "$r"
grep -q 'expected_pins' "$res4" && r=yes || r=no
check "upstream's new fields survived too" "$r"
grep -qF 'AETHER-APP-PATCH' "$APP4/native/aether/.upstream-baseline/aether/src/prober.rs" && r=no || r=yes
check "the cached baseline is pristine again for the next upgrade" "$r"

echo
printf 'core-sync tests: %d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
