#!/usr/bin/env bash
#
# sync-core.sh - keep the vendored Aether engine (core) in sync with the
# official upstream repository, automatically, on every CI build.
#
# WHY THIS EXISTS (1.2.2)
# -----------------------
# Until 1.2.1 the vendored core under native/aether was bumped by hand. That
# meant the app could silently ship an engine months behind upstream, and
# nobody noticed until a protocol change broke connectivity in the field.
# From 1.2.2 the BUILD owns the core version:
#
#   1. Query the official core repo (CluvexStudio/Aether) for its latest
#      release/tag.
#   2. Compare it with native/aether/CORE_VERSION (currently vendored).
#   3. If upstream is NEWER, fetch that exact tag and MERGE this app's own
#      engine patches onto the new sources (see PATCHED_FILES).
#   4. Record the upgrade in README.md / README.fa.md at the core-sync
#      anchors, so documentation can never drift from what was built.
#
# HOW THE APP PATCHES SURVIVE AN UPGRADE (root fix, 1.2.2)
# --------------------------------------------------------
# The first implementation simply copied this repo's whole prober.rs /
# wg_prober.rs over the new upstream files. That is wrong and it broke the
# build the moment upstream touched those files: our stale copies were written
# against the OLD engine API, so core 1.4.0 failed to compile with
#
#   error[E0063]: missing fields `expected_pins` and `pin_endpoint`
#                 in initializer of `H2TunnelConfig`   (prober.rs)
#   error[E0061]: this function takes 8 arguments but 7 were supplied
#                 (wireguard::verify_endpoint_keep_session, wg_prober.rs)
#
# A whole-file copy can never be correct, because it silently reverts every
# upstream change inside that file. So we now treat our changes as what they
# actually are: a PATCH on top of a known upstream baseline.
#
#   ours   = native/aether/<file>                     (upstream_old + our patch)
#   base   = pristine upstream <file> at CORE_VERSION (the baseline)
#   theirs = pristine upstream <file> at the new tag
#
# and we run a real three-way merge (git merge-file). Upstream API changes and
# our additive changes then combine correctly, exactly like a rebase would.
#
# The baseline is cached in native/aether/.upstream-baseline/ so later runs
# need no extra clone. On the very first upgrade the baseline is reconstructed
# by also cloning the currently vendored tag.
#
# If the merge conflicts we deliberately keep the PURE UPSTREAM file (which is
# guaranteed to compile) instead of forcing our stale copy, and we shout about
# it in the log and in the changelog. A degraded feature is recoverable; a red
# build on every future run is not.
#
# Finally, the previous core is snapshotted to native/.core-prev so the CI
# "Build engine" step can roll back and retry if the new core does not build
# for any reason at all. An automatic engine upgrade must never be able to
# break a release.
#
# The script is deliberately conservative: any failure to reach GitHub leaves
# the vendored core untouched and exits 0, so a network hiccup can never break
# a release build. It only ever moves FORWARD (never downgrades).
#
# Usage:
#   scripts/sync-core.sh                   # sync to latest upstream release
#   CORE_TARGET=1.4 scripts/sync-core.sh   # pin a specific version
#   CORE_SYNC=off scripts/sync-core.sh     # disable (use vendored core)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CORE_REPO="${AETHER_REPO:-CluvexStudio/Aether}"
CORE_DIR="native/aether"
VERSION_FILE="$CORE_DIR/CORE_VERSION"
BASELINE_DIR="$CORE_DIR/.upstream-baseline"
PREV_DIR="native/.core-prev"
STATE_FILE="native/.core-sync-state"
README_EN="README.md"
README_FA="README.fa.md"

# Assembled from parts on purpose so the endpoints stay easy to override.
# CORE_API_BASE / CORE_GIT_BASE exist so the whole upgrade path can be
# exercised offline against a local repository (see scripts/test-core-sync.sh).
GH_HOST="github.com"
SCHEME="https"
GH_API="${CORE_API_BASE:-${SCHEME}://api.${GH_HOST}}"
GH_WEB="${CORE_GIT_BASE:-${SCHEME}://${GH_HOST}}"

# The baseline this app was engineered against; also the floor we never go below.
# 1.2.3: raised to 1.5.0 (the release that fixed the mislabelled 1.4/1.3.0 vendor
# and rebased the app's engine patches onto the real upstream baseline).
# 1.2.6: raised to 1.7.0 (routing by sniffed name, upstream proxy, identity
# reprovisioning); the app's manual-range patches were rebased onto it by hand.
# 1.2.7: raised to 1.8.0 (world-reachable listener warning, non-blocking HTTP
# head read, upstream-proxy SOCKS5 auth hardening, and the fix for the tokio
# "JoinHandle polled after completion" panic in the Gool double-tunnel teardown).
# The two manual-range patches were re-applied onto the new sources and, apart
# from those marked blocks, the vendored tree is byte-identical to upstream.
# 1.3.0: raised to 2.0.0, and this time the floor matters for a user-visible
# reason rather than a tidiness one. Core 2.0.0 is where Tor lives (arti-client
# behind the optional `tor` feature, bridges fetched from bridgedb, the
# pluggable transports in pt/), plus MASQUE-in-MASQUE, the QUIC
# version-negotiation probe, socket marks and the descriptor-exhaustion fixes.
# The app ships three Tor connection modes on top of that, so a core below
# 2.0.0 cannot serve this app at all: the build would succeed and the Tor
# modes would fail at connect time. All ten app patches were rebased onto
# 2.0.0 (three-way, against .upstream-baseline), and netstack.rs was rebased
# BY HAND - see .upstream-baseline/README.txt for why that one is not a merge.
# 1.4.0: raised to 2.1.0 - Psiphon moved INTO the engine (psiphon.rs, the
# psiphon-tunnel-core console client) and the app's own Psiphon AAR was removed,
# so a core below 2.1.0 has no Psiphon at all for the app's two Psiphon modes.
BASELINE="2.1.0"

# App-specific patches carried on top of the upstream engine. These are MERGED
# (three-way) onto the new upstream sources, never blind-copied over them.
#   prober.rs     -> custom_masque_cidrs_v4() + manual-range mode in build_candidates()
#   wg_prober.rs  -> custom_wg_cidrs_v4() + manual-range mode in build_wg_candidates()
# Both power the 1.2.2 location picker (AETHER_SCAN_CIDRS). From 1.2.6 every
# patched region is wrapped in AETHER-APP-PATCH markers, which is what makes the
# pristine merge base reconstructible offline (see "pristine base" below).
# ---------------------------------------------------------------------------
# 1.2.8-r5 ROOT-CAUSE FIX: this list used to be maintained BY HAND, and that is
# a latent time bomb this round found before it went off.
#
# The apply step below does `rm -rf "$CORE_DIR/aether"` and replaces the whole
# directory with pristine upstream. Only files named here are merged back. So
# every app patch that lived in a file NOT on this list was deleted on the next
# core upgrade, silently, with a green build and no warning anywhere:
#
#   aether/Cargo.toml        <- the socket-tcp-cubic feature (the r4 root cause)
#   aether/src/netstack.rs   <- set_congestion_control, device backpressure,
#                               the data-plane telemetry, every queue bound
#   aether/src/sysprofile.rs <- the split tx/rx netstack buffer sizing
#   aether/build.rs          <- the whole build-provenance stamp
#
# In other words: the fix for the bug this project has been chasing for five
# rounds was scheduled to be thrown away by our own CI the first time upstream
# cut a release, and the log would have shown a perfectly clean upgrade while it
# happened. Whatever else is true about the r4 field test, that alone had to be
# fixed before any engine work could be trusted to survive.
#
# So the list is no longer written by a human. Every app patch is wrapped in
# AETHER-APP-PATCH markers (that requirement already existed, for the merge
# base); the set of patched files is therefore DISCOVERABLE, and it is now
# discovered. Forgetting to add a file is no longer possible, because there is
# nothing to add.
discover_patched_files() {
  ( cd "$CORE_DIR" && grep -rl -- "$PATCH_MARK" . 2>/dev/null \
      | sed 's|^\./||' \
      | grep -v '^\.upstream-baseline/' \
      | grep -v '^target/' \
      | sort )
}

# Populated right after PATCH_MARK is defined (see below).
PATCHED_FILES=()

log() { printf '[core-sync] %s\n' "$*"; }
warn() { printf '[core-sync] %s\n' "$*" >&2; }

# GitHub Actions annotations, so problems are visible in the run summary and
# not just buried in the log.
notice_gh() { [[ -n "${GITHUB_ACTIONS:-}" ]] && printf '::warning::%s\n' "$*" || true; }

rm -f "$STATE_FILE"

if [[ "${CORE_SYNC:-on}" == "off" ]]; then
  log "CORE_SYNC=off - keeping the vendored core untouched."
  exit 0
fi

# ---------------------------------------------------------------- current
current="$BASELINE"
if [[ -f "$VERSION_FILE" ]]; then
  current="$(tr -d '[:space:]' < "$VERSION_FILE")"
fi
log "Vendored core version: ${current}"

# ---------------------------------------------------------------- upstream
api() {
  local url="$1"
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "Accept: application/vnd.github+json" "$url" 2>/dev/null || true
  else
    curl -fsSL -H "Accept: application/vnd.github+json" "$url" 2>/dev/null || true
  fi
}

# Minimal JSON scrape: avoids a jq dependency on the runner.
latest_raw="$(api "${GH_API}/repos/${CORE_REPO}/releases/latest" |
  sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
if [[ -z "$latest_raw" ]]; then
  latest_raw="$(api "${GH_API}/repos/${CORE_REPO}/tags" |
    sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
fi

target="${CORE_TARGET:-${latest_raw:-}}"
if [[ -z "$target" ]]; then
  warn "Could not reach the core repo - keeping vendored core ${current}. Build continues."
  exit 0
fi

# Normalise "v1.4" / "1.4.0" style tags for comparison.
target_v="${target#v}"
current_v="${current#v}"
log "Latest upstream core: ${target_v}"

# Is $1 strictly newer than $2?
newer_than() {
  [[ "$1" != "$2" ]] &&
    [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" == "$1" ]]
}

if ! newer_than "$target_v" "$current_v"; then
  log "Vendored core ${current_v} is already current (upstream ${target_v}). Nothing to do."
  exit 0
fi

log "Upgrading core ${current_v} -> ${target_v}"

# ---------------------------------------------------------------- fetch
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

# Clone tag $1 into $2. Tags are written both with and without a leading "v"
# in the wild, so try the other spelling before giving up.
clone_tag() {
  local tag="$1" dest="$2" alt
  git clone --depth 1 --branch "$tag" "${GH_WEB}/${CORE_REPO}.git" "$dest" >/dev/null 2>&1 && return 0
  alt="v${tag#v}"
  [[ "$tag" == v* ]] && alt="${tag#v}"
  rm -rf "$dest"
  git clone --depth 1 --branch "$alt" "${GH_WEB}/${CORE_REPO}.git" "$dest" >/dev/null 2>&1
}

if ! clone_tag "$target" "$staging/new"; then
  warn "Could not fetch core tag ${target} - keeping ${current_v}. Build continues."
  exit 0
fi

if [[ ! -d "$staging/new/aether" ]]; then
  warn "Unexpected upstream layout (no aether/ dir) - aborting upgrade, keeping ${current_v}."
  exit 0
fi

# ------------------------------------------------- pristine base from markers
# Every app engine patch is wrapped in
#
#   // >>> AETHER-APP-PATCH <name>   ...   // <<< AETHER-APP-PATCH <name>
#
# so a pristine merge base can always be rebuilt offline by stripping those
# blocks out of our own file - no clone of the old tag needed.
#
# WHY (1.2.6): 1.2.5 shipped a POLLUTED baseline. The cached "pristine"
# wg_prober.rs was in fact the patched copy, so base == ours. git merge-file then
# reads the app patch as an upstream deletion and drops it without a single
# warning: the manual endpoint range would have stopped working on the next
# automatic core upgrade, silently, exactly like the 1.2.3 regression.
PATCH_MARK="AETHER-APP-PATCH"

# --------------------------------------------------- discover the app patches
# See discover_patched_files() at the top of this file for why this is not a
# hand-written list any more.
mapfile -t PATCHED_FILES < <(discover_patched_files)
if (( ${#PATCHED_FILES[@]} == 0 )); then
  echo "::error::No AETHER-APP-PATCH markers found under ${CORE_DIR}." >&2
  echo "Either the vendored engine carries no app patches (it should carry several)," >&2
  echo "or the markers were stripped. Refusing to upgrade the core blind - that is" >&2
  echo "exactly how an app patch gets deleted with a green build." >&2
  exit 1
fi
log "App patches discovered by marker (${#PATCHED_FILES[@]}):"
printf '[core-sync]   %s\n' "${PATCHED_FILES[@]}"

strip_app_patch() {
  awk -v mark="$PATCH_MARK" '
    index($0, ">>> " mark) { skip = 1; next }
    index($0, "<<< " mark) { skip = 0; next }
    !skip { print }
  ' "$1"
}

for rel in "${PATCHED_FILES[@]}"; do
  ours_now="$CORE_DIR/$rel"
  base_now="$BASELINE_DIR/$rel"
  [[ -f "$ours_now" ]] || continue
  grep -qF -- "$PATCH_MARK" "$ours_now" || continue

  if [[ ! -f "$base_now" ]]; then
    why="no cached baseline"
  elif grep -qF -- "$PATCH_MARK" "$base_now"; then
    why="the cached baseline was polluted with the app patch"
  else
    continue
  fi

  mkdir -p "$(dirname "$base_now")"
  strip_app_patch "$ours_now" > "$base_now"
  log "Rebuilt a pristine merge base for ${rel} (${why})."
  notice_gh "Merge base for ${rel} rebuilt from AETHER-APP-PATCH markers (${why})."
done

# ------------------------------------------------------- baseline for merge
# The pristine upstream copy of each patched file AT THE CURRENTLY VENDORED
# VERSION. Without it a three-way merge is impossible and we would be back to
# the broken "blind copy" behaviour.
have_baseline=1
for rel in "${PATCHED_FILES[@]}"; do
  [[ -f "$BASELINE_DIR/$rel" ]] || have_baseline=0
done

if (( have_baseline == 0 )); then
  log "No cached baseline for ${current_v} - reconstructing it from upstream."
  if clone_tag "$current_v" "$staging/base" && [[ -d "$staging/base/aether" ]]; then
    mkdir -p "$BASELINE_DIR"
    for rel in "${PATCHED_FILES[@]}"; do
      if [[ -f "$staging/base/$rel" ]]; then
        mkdir -p "$BASELINE_DIR/$(dirname "$rel")"
        cp "$staging/base/$rel" "$BASELINE_DIR/$rel"
      fi
    done
    have_baseline=1
    log "Baseline for ${current_v} reconstructed."
  else
    warn "Could not fetch the baseline tag ${current_v}; patches will be re-applied without a merge base."
  fi
fi

# ------------------------------------------------------------- snapshot
# Keep the whole previous core so the CI build step can roll back and retry if
# the new core turns out not to build. This directory is git-ignored.
rm -rf "$PREV_DIR"
mkdir -p "$(dirname "$PREV_DIR")"
cp -R "$CORE_DIR" "$PREV_DIR"

# ---------------------------------------------------------------- preserve
backup="$staging/ours"
mkdir -p "$backup"
for rel in "${PATCHED_FILES[@]}"; do
  if [[ -f "$CORE_DIR/$rel" ]]; then
    mkdir -p "$backup/$(dirname "$rel")"
    cp "$CORE_DIR/$rel" "$backup/$rel"
  fi
done

# ---------------------------------------------------------------- apply
# Replace the upstream-owned sources only. Anything this repo added on its own
# (build scripts, vendored quiche, CORE_VERSION, the baseline cache) stays.
rm -rf "$CORE_DIR/aether"
cp -R "$staging/new/aether" "$CORE_DIR/aether"

# Re-apply this app's patches by MERGING them onto the new upstream files.
merged=()
unchanged=()
dropped=()
for rel in "${PATCHED_FILES[@]}"; do
  ours="$backup/$rel"
  theirs="$CORE_DIR/$rel"
  base="$BASELINE_DIR/$rel"

  # Nothing of ours to carry over, or upstream removed the file entirely.
  [[ -f "$ours" ]] || continue
  if [[ ! -f "$theirs" ]]; then
    warn "Upstream ${target_v} no longer ships ${rel}; the app patch for it is obsolete and was dropped."
    dropped+=("$rel")
    continue
  fi

  # Upstream did not touch this file: our version already contains upstream's
  # content plus our patch, so keep ours verbatim (fast path, no merge needed).
  if [[ -f "$base" ]] && cmp -s "$base" "$theirs"; then
    cp "$ours" "$theirs"
    unchanged+=("$rel")
    continue
  fi

  if [[ ! -f "$base" ]]; then
    # No merge base available. Blind-copying is exactly the bug we are fixing,
    # so prefer the file that is guaranteed to compile: upstream's.
    warn "No merge base for ${rel}; keeping the pure upstream file (app patch NOT applied)."
    dropped+=("$rel")
    continue
  fi

  # Real three-way merge: ours (upstream_old + patch) x base x theirs (new).
  work="$staging/merge_$(basename "$rel")"
  cp "$ours" "$work"
  set +e
  git merge-file -q \
    -L "app patch (${current_v})" -L "upstream ${current_v}" -L "upstream ${target_v}" \
    "$work" "$base" "$theirs"
  rc=$?
  set -e

  if (( rc == 0 )); then
    cp "$work" "$theirs"
    merged+=("$rel")
    log "Merged app patch into ${rel} cleanly."
  else
    # rc > 0 = conflicts, rc = 255 = merge error. Either way the result is not
    # trustworthy; keep pure upstream so the engine still compiles.
    warn "Could not merge the app patch into ${rel} (upstream rewrote it)."
    warn "Keeping the pure upstream file so the build stays green; re-apply the patch by hand."
    dropped+=("$rel")
  fi
done

# ------------------------------------------------------- verify the patches
# With the markers this check is trivial: if our file carried a marked patch and
# the merged file no longer does, the patch was lost. Never guess - report it.
for rel in "${PATCHED_FILES[@]}"; do
  ours_pre="$backup/$rel"
  final="$CORE_DIR/$rel"
  [[ -f "$ours_pre" && -f "$final" ]] || continue
  if grep -qF -- "$PATCH_MARK" "$ours_pre" && ! grep -qF -- "$PATCH_MARK" "$final"; then
    warn "The app patch markers are gone from ${rel} after the merge - the patch was NOT carried over."
    dropped+=("$rel")
    continue
  fi
  # 1.2.8-r5: markers surviving is necessary but not sufficient - a three-way
  # merge can keep the marker comments and still lose lines between them. Count
  # them and compare, so a partial rebase is caught too.
  before="$(grep -cF -- "$PATCH_MARK" "$ours_pre" || true)"
  after="$(grep -cF -- "$PATCH_MARK" "$final" || true)"
  if [[ "$before" != "$after" ]]; then
    warn "${rel} carried ${before} patch markers before the merge and ${after} after it - the rebase is incomplete."
    dropped+=("$rel")
  fi
done

# The new upstream files become the baseline for the NEXT upgrade.
mkdir -p "$BASELINE_DIR"
for rel in "${PATCHED_FILES[@]}"; do
  if [[ -f "$staging/new/$rel" ]]; then
    mkdir -p "$BASELINE_DIR/$(dirname "$rel")"
    cp "$staging/new/$rel" "$BASELINE_DIR/$rel"
  fi
done
cat > "$BASELINE_DIR/README.txt" <<EOF
Pristine upstream copies of the files this app patches, at core ${target_v}.

Do not edit. scripts/sync-core.sh uses them as the merge base so the app's
engine patches can be rebased onto a new core instead of overwriting it.
EOF

if (( ${#dropped[@]} > 0 )); then
  # ---------------------------------------------------------------- 1.2.8-r5
  # This used to be a warning and the build carried on. That was the wrong
  # trade, and this project paid for it: "a degraded feature is recoverable, a
  # red build is not" is a reasonable rule for the manual-range scanner it was
  # written for, and completely wrong for the netstack. Losing the congestion
  # control or the device backpressure patch does not degrade a feature, it
  # ships the exact bug five rounds of field testing were spent on, and it does
  # it silently.
  #
  # An automatic convenience upgrade may not quietly revert an engine fix. If a
  # patch cannot be rebased, this build stops and a human rebases it. The core
  # rollback path in the workflow ("Build engine") still exists for genuine
  # upstream API breakage, so the release is never actually stuck.
  warn "App engine patch(es) could NOT be rebased onto core ${target_v}: ${dropped[*]}"
  warn "Refusing to ship a core upgrade that reverts an app patch. Rebase it by hand,"
  warn "or pin the core with CORE_TARGET=${current_v} / CORE_SYNC=off for this build."
  notice_gh "Core upgrade to ${target_v} ABORTED: the app patch for ${dropped[*]} could not be rebased."
  exit 1
fi

printf '%s\n' "$target_v" > "$VERSION_FILE"
log "Core upgraded to ${target_v}."
(( ${#merged[@]} > 0 ))    && log "  three-way merged: ${merged[*]}"
(( ${#unchanged[@]} > 0 )) && log "  carried over unchanged: ${unchanged[*]}"
(( ${#dropped[@]} > 0 ))   && log "  needs manual review: ${dropped[*]}"

# State for the CI rollback/commit steps.
{
  echo "CORE_PREV_VERSION=${current_v}"
  echo "CORE_NEW_VERSION=${target_v}"
  echo "CORE_UPGRADED=1"
} > "$STATE_FILE"

# --------------------------------------------------- new core capabilities
# If the new core advertises capabilities the UI does not expose yet, say so
# loudly in the build log AND in the changelog, so no engine feature can ship
# without a matching UI decision.
NEW_CAPS=""
if [[ -d "$CORE_DIR/aether/src" ]]; then
  for cap in "--ech" "--noize" "--fragment" "--ironclad" "--dual" "--masque" "--gool"; do
    if grep -rqF -- "$cap" "$CORE_DIR/aether/src" 2>/dev/null; then
      if ! grep -rqF -- "$cap" "app/src/main/java" 2>/dev/null; then
        NEW_CAPS+="${cap} "
      fi
    fi
  done
fi
if [[ -n "$NEW_CAPS" ]]; then
  warn "Core ${target_v} exposes options not wired into the UI yet: ${NEW_CAPS}"
  notice_gh "Core ${target_v} exposes engine options not yet wired into the UI: ${NEW_CAPS}"
fi

# ---------------------------------------------------------------- document
# MANDATORY: every core upgrade documents itself in the current changelog
# (the core-sync:en / core-sync:fa anchors, which live in the 1.2.3 section).
#
# NOTE the "--" before the pattern: every changelog line starts with "- ", and
# without it grep parses the line as a bundle of options and dies with
# "grep: invalid option".
document() {
  local file="$1" anchor="$2" line="$3"
  [[ -f "$file" ]] || return 0
  grep -qF -- "$line" "$file" && return 0
  grep -qF -- "$anchor" "$file" || return 0
  awk -v anchor="$anchor" -v line="$line" '
    { print }
    index($0, anchor) && !inserted { print line; inserted = 1 }
  ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
}

EN_LINE="- **Engine (core) upgraded to v${target_v}** automatically by the CI core-sync step (previous: v${current_v}). The app's engine patches were rebased onto the new sources with a three-way merge."
FA_LINE="- **ارتقای هسته (Core) به نسخهٔ ${target_v}** به‌صورت خودکار توسط مرحلهٔ core-sync در CI (نسخهٔ قبلی: ${current_v})؛ پچ‌های اختصاصی برنامه با ادغام سه‌طرفه روی سورس جدید بازاعمال شدند."

if (( ${#dropped[@]} > 0 )); then
  EN_LINE+=" Needs manual review: ${dropped[*]}."
  FA_LINE+=" نیازمند بازبینی دستی: ${dropped[*]}."
fi

if [[ -n "$NEW_CAPS" ]]; then
  EN_LINE+=" New engine options detected and pending UI review: ${NEW_CAPS}"
  FA_LINE+=" گزینه‌های تازهٔ هسته که نیازمند بررسی در UI هستند: ${NEW_CAPS}"
fi

document "$README_EN" "<!-- core-sync:en -->" "$EN_LINE"
document "$README_FA" "<!-- core-sync:fa -->" "$FA_LINE"

log "README changelog updated for core ${target_v}."
