#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Make the repository contain EXACTLY the files of the release that was uploaded.
#
# WHY THIS EXISTS
# ---------------
# A new release is published by copying its files on top of the previous one
# (GitHub "Upload files", or a copy into an existing clone). That overwrites
# every file the release ships, but it can never DELETE a file the release no
# longer has. Those leftovers are not harmless:
#   * a leftover Kotlin file still references classes, strings and resources
#     that are gone, and compileReleaseKotlin fails with "Unresolved reference";
#   * a leftover library in app/libs (1.3.0's psiphontunnel AAR) is a second,
#     conflicting Psiphon next to the engine's own;
#   * a leftover Rust file or vendored crate file changes what the engine build
#     sees.
# .github/removed-sources.txt only covers files someone remembered to list.
# This script needs no list: SOURCE_MANIFEST.sha256 already names every file
# of the release (with its hash), so anything tracked in the repository that is
# NOT in the manifest is, by definition, not part of this release.
#
# HOW IT DECIDES
# --------------
# 1. It runs ONCE per release. The manifest's own SHA-256 identifies the
#    release; after a successful run it is recorded in .github/release-tree.applied
#    and later builds of the same release skip straight through. So files that
#    CI adds afterwards (a core auto-upgrade, a pinned signer) or that you add
#    by hand are never touched by a later build. Uploading the next release
#    (a new manifest) arms it again.
# 2. Before deleting ANYTHING it proves the upload is complete:
#    every manifest entry must exist. If a file is MISSING the build stops here
#    with the list, and nothing is deleted.
#    A file that exists but whose content differs from the manifest (a CRLF-only
#    difference, a file rewritten by an earlier CI run such as the core
#    auto-upgrade, a Dependabot bump, a hand edit) does NOT stop the release:
#    the purge only depends on WHICH paths belong to the release, never on their
#    content. Every such file is printed by name in the log and the job summary
#    so it can be re-uploaded if needed. Only an implausible number of
#    differences (more than MAX_CHANGED, default 25 - i.e. the manifest and the
#    tree are clearly from different releases) is still a refusal.
#    (1.4.0 fix: the first 1.4.0 build stopped on "0 missing, 1 different",
#    and the file name only appeared as an annotation, not in the raw log.)
# 3. Only tracked files outside the manifest are removed (git rm), so the
#    purge is an ordinary commit you can read, revert or cherry-pick.
#
# NEVER DELETED (even when absent from the manifest)
#   .github/workflows/*          GITHUB_TOKEN may not push workflow changes;
#                                extra workflows are reported, not removed
#   .github/expected-signer.txt  release-key pin written by CI
#   .github/expected-signer-ci.txt, .github/ci-keystore.jks.b64
#                                CI signing identity (over-install guarantee)
#   .github/release-tree.applied this script's own marker
#   SOURCE_MANIFEST.sha256       the manifest itself
#
# Exit codes: 0 = tree is exactly the release (or already enforced),
#             1 = upload incomplete / manifest invalid / refusal (nothing deleted).
# ---------------------------------------------------------------------------
set -euo pipefail

MANIFEST="SOURCE_MANIFEST.sha256"
MARKER=".github/release-tree.applied"
PROTECTED_RE='^(\.github/workflows/.*|\.github/expected-signer\.txt|\.github/expected-signer-ci\.txt|\.github/ci-keystore\.jks\.b64|\.github/release-tree\.applied|SOURCE_MANIFEST\.sha256)$'
MIN_ENTRIES=500
MAX_CHANGED="${MAX_CHANGED:-25}"

summary() { [ -n "${GITHUB_STEP_SUMMARY:-}" ] && printf '%s\n' "$@" >> "$GITHUB_STEP_SUMMARY" || true; }

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
	echo "::error::enforce-release-tree.sh must run inside the git checkout."
	exit 1
fi
cd "$(git rev-parse --show-toplevel)"

if [ ! -f "$MANIFEST" ]; then
	echo "::warning::No $MANIFEST in this tree - cannot tell which files belong to the release. Skipping (scripts/purge-stale-sources.sh still runs)."
	exit 0
fi

RELEASE_ID="$(sha256sum "$MANIFEST" | cut -c1-64)"
VERSION="$(tr -d '[:space:]' < PATCHLEVEL 2>/dev/null || echo unknown)"

if [ -f "$MARKER" ] && grep -qx "manifest=${RELEASE_ID}" "$MARKER"; then
	echo "Release tree already enforced for ${VERSION} (manifest ${RELEASE_ID:0:12}) - nothing to do."
	exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --------------------------------------------------------------- 1. manifest
# Format: "<sha256>  ./<path>" (sha256sum text mode), one file per line.
bad=0
: > "$WORK/release.tsv"
while IFS= read -r line || [ -n "$line" ]; do
	[ -n "$line" ] || continue
	hash="${line:0:64}"
	path="${line:66}"
	path="${path#./}"
	if ! [[ "$hash" =~ ^[0-9a-f]{64}$ ]] || [ "${line:64:2}" != "  " ] || [ -z "$path" ]; then
		echo "::error file=${MANIFEST}::malformed manifest line: ${line:0:120}"
		bad=1; continue
	fi
	case "/$path/" in
		*/../*|*/./*|//*) echo "::error file=${MANIFEST}::refusing suspicious path: $path"; bad=1; continue ;;
	esac
	printf '%s\t%s\n' "$path" "$hash" >> "$WORK/release.tsv"
done < "$MANIFEST"
[ "$bad" -eq 0 ] || { echo "::error::$MANIFEST is invalid - nothing was deleted."; exit 1; }

entries="$(wc -l < "$WORK/release.tsv" | tr -d ' ')"
if [ "$entries" -lt "$MIN_ENTRIES" ]; then
	echo "::error::$MANIFEST lists only $entries files (expected at least $MIN_ENTRIES). Refusing to prune against a truncated manifest - nothing was deleted."
	exit 1
fi
cut -f1 "$WORK/release.tsv" | LC_ALL=C sort -u > "$WORK/release.paths"

# ----------------------------------------------- 2. the upload must be complete
# One sha256sum pass over the whole release (fast), then a closer look only at
# the entries that did not verify.
missing=0; changed=0; crlf=0
: > "$WORK/check.sha256"
: > "$WORK/changed.paths"; : > "$WORK/missing.paths"
while IFS=$'\t' read -r path hash; do
	# CI-managed files may legitimately differ from the uploaded copy.
	[[ "$path" =~ $PROTECTED_RE ]] && continue
	printf '%s  ./%s\n' "$hash" "$path" >> "$WORK/check.sha256"
done < "$WORK/release.tsv"

sha256sum --check --quiet "$WORK/check.sha256" 2>/dev/null \
	| sed -n 's#^\./\(.*\): FAILED.*$#\1#p' > "$WORK/failed.paths" || true

while IFS= read -r path; do
	[ -n "$path" ] || continue
	if [ ! -f "$path" ]; then
		echo "::error::missing from the repository: $path"
		echo "MISSING:   $path"
		printf '%s\n' "$path" >> "$WORK/missing.paths"
		missing=$((missing + 1)); continue
	fi
	hash="$(awk -F'\t' -v p="$path" '$1 == p { print $2; exit }' "$WORK/release.tsv")"
	if [ "$(tr -d '\r' < "$path" | sha256sum | cut -c1-64)" = "$hash" ]; then
		echo "::warning file=${path}::differs from the release only by CRLF line endings (accepted)."
		crlf=$((crlf + 1)); continue
	fi
	echo "::warning file=${path}::content differs from the release ($MANIFEST) - kept as it is in the repository."
	echo "DIFFERENT: $path"
	printf '%s\n' "$path" >> "$WORK/changed.paths"
	changed=$((changed + 1))
done < "$WORK/failed.paths"

if [ "$missing" -gt 0 ]; then
	echo "::error::The upload of ${VERSION} is incomplete: ${missing} missing, ${changed} different. Nothing was deleted."
	echo "::error::Upload the release again (all folders, including .github), then re-run the build."
	summary "### Release tree ${VERSION}: upload incomplete" "${missing} missing, ${changed} different - nothing deleted." \
		"$(sed 's/^/- missing: `/; s/$/`/' "$WORK/missing.paths")" \
		"$(sed 's/^/- different: `/; s/$/`/' "$WORK/changed.paths")"
	exit 1
fi

if [ "$changed" -gt "$MAX_CHANGED" ]; then
	echo "::error::${changed} files differ from ${MANIFEST} (limit ${MAX_CHANGED}) - the manifest and the tree look like different releases. Nothing was deleted."
	echo "::error::Upload the release again (all folders, including .github), then re-run the build."
	summary "### Release tree ${VERSION}: ${changed} files differ from the manifest - nothing deleted."
	exit 1
fi

if [ "$changed" -gt 0 ]; then
	echo "::warning::${changed} file(s) differ from the ${VERSION} manifest (listed above as DIFFERENT). They are kept; the leftover cleanup continues. Re-upload them from the release archive if they were not meant to change."
fi

# ------------------------------------------------ 3. find files not in release
git ls-files -z | tr '\0' '\n' | LC_ALL=C sort -u > "$WORK/tracked.paths"
LC_ALL=C comm -23 "$WORK/tracked.paths" "$WORK/release.paths" > "$WORK/extra.paths"

: > "$WORK/delete.paths"; : > "$WORK/kept.paths"
while IFS= read -r path; do
	[ -n "$path" ] || continue
	if [ "$path" = "$MANIFEST" ] || [ "$path" = "$MARKER" ]; then
		continue   # the manifest never lists itself; the marker is ours
	elif [[ "$path" =~ $PROTECTED_RE ]]; then
		printf '%s\n' "$path" >> "$WORK/kept.paths"
	else
		printf '%s\n' "$path" >> "$WORK/delete.paths"
	fi
done < "$WORK/extra.paths"

ndel="$(wc -l < "$WORK/delete.paths" | tr -d ' ')"
tracked="$(wc -l < "$WORK/tracked.paths" | tr -d ' ')"

# Sanity: a real upgrade removes a handful of files, never most of the repository.
if [ "$ndel" -gt 0 ] && [ $((ndel * 2)) -gt "$tracked" ]; then
	echo "::error::Would delete ${ndel} of ${tracked} tracked files - that is not a leftover cleanup. Refusing; nothing was deleted."
	head -n 50 "$WORK/delete.paths"
	exit 1
fi

while IFS= read -r path; do
	[ -n "$path" ] || continue
	echo "::warning file=${path}::not part of ${VERSION} but protected (CI-managed or a workflow) - left in place. Delete it by hand if it is really obsolete."
done < "$WORK/kept.paths"

if [ "$ndel" -gt 0 ]; then
	while IFS= read -r path; do
		[ -n "$path" ] || continue
		git rm -q --ignore-unmatch -- "$path"
		echo "Removed (not part of ${VERSION}): $path"
	done < "$WORK/delete.paths"
	echo "Removed ${ndel} file(s) left over from an older version."
else
	echo "No leftovers: the repository already contains only ${VERSION} files."
fi

{
	echo "# Written by scripts/enforce-release-tree.sh - do not edit."
	echo "# The repository was reduced to exactly the files of this release."
	echo "version=${VERSION}"
	echo "manifest=${RELEASE_ID}"
	echo "removed=${ndel}"
	echo "differing=${changed}"
} > "$MARKER"
git add -- "$MARKER"

summary "### Release tree ${VERSION}" \
	"Verified ${entries} release files (${crlf} CRLF-only, ${changed} differing). Removed ${ndel} leftover file(s)." \
	"$(sed 's/^/- differs from manifest (kept): `/; s/$/`/' "$WORK/changed.paths")" \
	"$(sed 's/^/- `/; s/$/`/' "$WORK/delete.paths")"
