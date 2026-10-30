#!/usr/bin/env bash
# Only the optional leaf-pinning core is built here; libxray.so and all six
# built-in engines are unchanged. Source is pinned and its archive verified.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/.native/xray-leaf-pin"
ARCHIVE_SHA=2cfd9f8ce7c02cb1a5d05c52787f373a0ac0668c392dce021dd9e330ac18197d
PATCH="$ROOT/patches/xray-leaf-pin-v25.12.8.patch"
PATCH_SHA="$(sha256sum "$PATCH" | cut -d' ' -f1)"
mkdir -p "$ROOT/.native"
if [ ! -f "$SRC/.aether-patch" ] || [ "$(cat "$SRC/.aether-patch")" != "$PATCH_SHA" ]; then
  TEMP="$(mktemp -d)"; trap 'rm -rf "$TEMP"' EXIT
  curl -fL --retry 3 --connect-timeout 30 \
    https://codeload.github.com/XTLS/Xray-core/zip/refs/tags/v25.12.8 -o "$TEMP/src.zip"
  echo "$ARCHIVE_SHA  $TEMP/src.zip" | sha256sum -c -
  unzip -q "$TEMP/src.zip" -d "$TEMP"
  rm -rf "$SRC"; mv "$TEMP/Xray-core-25.12.8" "$SRC"
  (cd "$SRC" && git apply "$PATCH")
  printf '%s\n' "$PATCH_SHA" > "$SRC/.aether-patch"
fi
cp "$ROOT/scripts/xray-leaf-pin_test.go" "$SRC/transport/internet/tls/aether_leaf_pin_test.go"
cp "$ROOT/scripts/xray-leaf-conf_test.go" "$SRC/infra/conf/aether_leaf_pin_test.go"
export GOTOOLCHAIN=auto
# 1.6.0 CI fix: a transient proxy.golang.org failure ("stream error ...
# INTERNAL_ERROR") failed the whole build. The pipe in GOPROXY makes Go fall
# back to the next source on ANY error, and go.sum still verifies every module,
# so a mirror cannot substitute different code.
export GOPROXY="${GOPROXY:-https://proxy.golang.org|https://goproxy.io|direct}"
retry() {
  local n=1 max=5
  until "$@"; do
    if [ "$n" -ge "$max" ]; then echo "ERROR: failed after $max attempts: $*" >&2; return 1; fi
    echo "    attempt $n/$max failed (network?) - retrying in $((n * 10))s: $*" >&2
    sleep $((n * 10)); n=$((n + 1))
  done
}
# Download all modules first (retried) so the tests/builds below never touch the network.
(cd "$SRC" && retry go mod download)
# Includes a negative pin test: a CA-valid/name-valid but unpinned certificate
# must NEVER be accepted. This test is mandatory in CI.
(cd "$SRC" && retry go test ./transport/internet/tls ./infra/conf -run '^TestAetherLeafPin' -count=1)
if [ "${1:-android}" = host ]; then
  (cd "$SRC" && CGO_ENABLED=0 go build -trimpath -ldflags '-s -w' -o "$ROOT/.native/xray-pinned-host" ./main)
  exit 0
fi
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
API="${ANDROID_API:-26}"
for spec in 'arm64-v8a arm64 aarch64-linux-android' 'armeabi-v7a arm armv7a-linux-androideabi' 'x86_64 amd64 x86_64-linux-android'; do
  read -r abi arch prefix <<< "$spec"
  dest="$ROOT/app/src/main/jniLibs/$abi"; mkdir -p "$dest"
  (cd "$SRC" && CGO_ENABLED=1 GOOS=android GOARCH="$arch" GOARM=7 \
    CC="$TOOLCHAIN/${prefix}${API}-clang" go build -trimpath -buildmode=pie \
    -ldflags '-s -w' -o "$dest/libxray-pinned.so" ./main)
  chmod 755 "$dest/libxray-pinned.so"
done
