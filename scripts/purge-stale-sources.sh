#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Purge sources that 1.2.2 removed but that still exist in an older checkout.
#
# WHY THIS EXISTS
# ---------------
# 1.2.2 deleted several source files (the in-app updater, the country/location
# picker, the forced-exit policy). When the new sources are copied on top of an
# existing 1.2.1 repository, *added and changed* files are overwritten but the
# *deleted* ones stay behind. Those orphans still reference symbols and string
# resources that no longer exist, so the Kotlin compiler fails with a wall of
# "Unresolved reference" errors (UpdateChecker.kt -> GITHUB_REPO,
# UpdatePrompt.kt -> update_available, ...) even though the shipped source tree
# compiles perfectly on its own. That is exactly why the same sources built
# fine in a fresh repository and failed in the 1.2.1 one.
#
# The build now removes those orphans itself before compiling, so the release
# pipeline can never be broken again by a leftover file from an older version.
#
# Safety: only paths listed in .github/removed-sources.txt are ever touched,
# and only inside app/ , native/aether/aether/src/ , docs/ and scripts/ , plus
# these four EXACT paths that 1.6.0 dropped (1.6.0 automatic cleanup, so nobody
# has to delete them by hand in GitHub):
#   BUILD_VALIDATION.md  PROJECT_STATUS.md  _META/TODO/bhs.md
#   native/aether/Docs/Aether.png
# A tracked file is removed with `git rm` (the deletion is then committed by the
# next workflow step); an untracked one is simply deleted. Neither can fail the
# build: a path that is already gone is skipped silently.
# ---------------------------------------------------------------------------
set -euo pipefail

MANIFEST=".github/removed-sources.txt"
removed=0

if [ ! -f "$MANIFEST" ]; then
	echo "No removal manifest at $MANIFEST - nothing to purge."
else
	while IFS= read -r raw || [ -n "$raw" ]; do
		path="${raw%%#*}"
		path="$(printf '%s' "$path" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
		[ -n "$path" ] || continue

		case "$path" in
			/*|*..*)
				echo "::error::Refusing suspicious path in $MANIFEST: $path"; exit 1 ;;
			app/*|native/aether/aether/src/*|docs/*|scripts/*) : ;;
			BUILD_VALIDATION.md|PROJECT_STATUS.md|_META/TODO/bhs.md|native/aether/Docs/Aether.png) : ;;
			*)
				echo "::error::Path outside the allowed trees in $MANIFEST: $path"; exit 1 ;;
		esac

		if [ -e "$path" ]; then
			# ---------------------------------------------------------------
			# 1.3.0: refuse to delete a file the CURRENT sources depend on.
			#
			# This list is a record of what an OLD version deleted. A later
			# version may legitimately bring a path back - 1.3.0 does exactly
			# that with transport/TorSocksFront.kt, because Tor moved into the
			# engine and the app front was rewritten against it. When that
			# happens and the line is still here, this script deletes a file
			# that belongs to the release, the commit step pushes the deletion
			# to the branch, and the build fails with "Unresolved reference" in
			# whichever file imported it. The source tree looked broken while
			# the only broken thing was this manifest - and the deletion had
			# already been committed, so the next build started from a tree
			# that really was missing the file.
			#
			# So: a Kotlin file whose top-level name is referenced ANYWHERE
			# else in the sources is not an orphan. Abort loudly instead of
			# deleting it, and name the line to remove.
			case "$path" in
				*.kt)
					symbol="$(basename "$path" .kt)"
					if [ -d app/src/main/java ] && grep -rlqE "(^|[^A-Za-z0-9_.])${symbol}([^A-Za-z0-9_]|$)" \
						--include='*.kt' app/src/main/java \
						--exclude="$(basename "$path")" 2>/dev/null; then
						echo "::error file=${MANIFEST}::${path} is still referenced by the current sources - it is NOT a leftover from an older version."
						echo "::error::This release brings that file back. Remove its line from ${MANIFEST} instead of deleting the file,"
						echo "::error::or the build will delete a source it needs and fail with 'Unresolved reference ${symbol}'."
						exit 1
					fi ;;
			esac

			if git ls-files --error-unmatch -- "$path" >/dev/null 2>&1; then
				# Tracked by git: stage the deletion (git rm). Never fail the build.
				git rm -rq --ignore-unmatch -- "$path" 2>/dev/null || true
			fi
			rm -rf -- "$path"
			echo "Removed stale file left over from an older version: $path"
			removed=$((removed + 1))
		fi
	done < "$MANIFEST"

	if [ "$removed" -eq 0 ]; then
		echo "Source tree is already clean - no stale files from older versions."
	else
		echo "Purged $removed stale file(s) left over from an older version."
	fi
fi

# ---------------------------------------------------------------------------
# Safety net: catch ANY remaining orphan that references a string resource
# which no longer exists. This costs a second and fails with a precise message
# instead of a three-minute Gradle run ending in "Unresolved reference".
# ---------------------------------------------------------------------------
STRINGS="app/src/main/res/values/strings.xml"
if [ -f "$STRINGS" ] && [ -d app/src/main/java ]; then
	sed -n 's/.*<string[[:space:]][^>]*name="\([^"]*\)".*/\1/p' "$STRINGS" | sort -u > /tmp/aether-defined-strings.txt
	# NOTE: only the app's OWN resources are checked. Framework resources
	# (android.R.string.cancel, ...) are never declared in strings.xml and must
	# not be reported as missing.
	grep -rn --include='*.kt' -oE '[A-Za-z0-9_.]*R\.string\.[A-Za-z0-9_]+' app/src/main/java \
		| grep -v ':android\.R\.string\.' \
		| sed -E 's/^([^:]+):([0-9]+):[A-Za-z0-9_.]*R\.string\.(.+)$/\1|\2|\3/' | sort -u > /tmp/aether-used-strings.txt

	missing=0
	while IFS='|' read -r file line id; do
		[ -n "${id:-}" ] || continue
		if ! grep -qx "$id" /tmp/aether-defined-strings.txt; then
			echo "::error file=${file},line=${line}::R.string.${id} does not exist (stale reference from an older version)."
			missing=$((missing + 1))
		fi
	done < /tmp/aether-used-strings.txt

	if [ "$missing" -gt 0 ]; then
		echo "::error::${missing} stale string reference(s). Add the offending file(s) to ${MANIFEST} or restore the strings."
		exit 1
	fi
	echo "String-resource reference check: OK ($(wc -l < /tmp/aether-used-strings.txt) references, all defined)."
fi
