#!/usr/bin/env bash
# Builds libghostty-vt, the terminal emulator the built-in terminal runs on (app/src/main/cpp/terminal.c), from
# ghostty's source at a pinned release, with a pinned Zig. Leaves it in app/src/main/cpp/ghostty-vt (gitignored):
# include/ (its C headers), arm64-v8a/libghostty-vt.a for the app and host/libghostty-vt.a for the JVM tests
# (HostNative). Without it the app builds without the built-in terminal and its tests are skipped.
# Needs the Android NDK (NDK_VERSION in scripts/fetch-proot.sh) and git. Zig is downloaded into build/zig;
# Zig itself fetches the library's five dependencies, pinned by content hash in ghostty's build.zig.zon.
# HOST=0 skips the host build (release builds don't need it).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/cpp/ghostty-vt"

# libghostty-vt's release tag and the commit it points at; git checks the source against the commit hash.
# Update both together; the C API is stable within 1.x (include/ghostty/vt.h).
GHOSTTY_TAG=vt-1.0.2
GHOSTTY_COMMIT=573c4a21e02311ae87844af26b6a7a179f82858e

# ghostty's build.zig.zon names the Zig it needs (minimum_zig_version). SHA-256s from
# https://ziglang.org/download/index.json.
ZIG_VERSION=0.17.1
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) ZIG_HOST=aarch64-macos ZIG_SHA=75f1bc7a1e9852f17ec9a0c1895f767c4ec56d46bc8a834920eece1d0359dd8d ;;
  Darwin-x86_64) ZIG_HOST=x86_64-macos ZIG_SHA=9af07b20319fbd0c92ee46ad23e81d6a782b1aaea2cfb8dcbff35f1d2c7ddbb7 ;;
  Linux-x86_64) ZIG_HOST=x86_64-linux ZIG_SHA=3581863e3cbcfd0fce0efc682ce7e9e93da96668a977791d356a26d2a7e9b17a ;;
  Linux-aarch64) ZIG_HOST=aarch64-linux ZIG_SHA=a1546d5646eb184437918d6635ca6b762a0e889eac09cef720ee7a1ed3fe7795 ;;
  *) echo "no Zig $ZIG_VERSION pinned for $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

# The app's minSdk (app/build.gradle.kts): the API level the library links against.
API=29

NDK_VERSION="$(sed -n 's/^NDK_VERSION=//p' "$ROOT/scripts/fetch-proot.sh")"
SDK="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null)}"
# Not ANDROID_NDK_HOME from the environment: CI runners point it at an older default NDK.
NDK="$SDK/ndk/$NDK_VERSION"
[ -d "$NDK" ] || { echo "no NDK at $NDK; install ndk;$NDK_VERSION with sdkmanager" >&2; exit 1; }

ZIG_DIR="$ROOT/build/zig/zig-$ZIG_HOST-$ZIG_VERSION"
ZIG="$ZIG_DIR/zig"
if [ ! -x "$ZIG" ]; then
  echo "fetching zig $ZIG_VERSION"
  mkdir -p "$ROOT/build/zig"
  tarball="$ROOT/build/zig/zig-$ZIG_HOST-$ZIG_VERSION.tar.xz"
  curl -fsSL "https://ziglang.org/download/$ZIG_VERSION/zig-$ZIG_HOST-$ZIG_VERSION.tar.xz" -o "$tarball"
  echo "$ZIG_SHA  $tarball" | shasum -a 256 -c --status || { echo "zig $ZIG_VERSION: SHA256 mismatch" >&2; exit 1; }
  tar xf "$tarball" -C "$ROOT/build/zig"
  rm "$tarball"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC="$WORK/ghostty"
echo "fetching ghostty $GHOSTTY_TAG"
git init -q "$SRC"
git -C "$SRC" fetch -q --depth 1 https://github.com/ghostty-org/ghostty.git "$GHOSTTY_COMMIT"
git -C "$SRC" checkout -q FETCH_HEAD
[ "$(git -C "$SRC" rev-parse HEAD)" = "$GHOSTTY_COMMIT" ] || { echo "ghostty: not commit $GHOSTTY_COMMIT" >&2; exit 1; }

build() {
  local target=$1 prefix=$2
  echo "building libghostty-vt for $target"
  (cd "$SRC" && ANDROID_NDK_HOME="$NDK" "$ZIG" build -Demit-lib-vt=true -Doptimize=ReleaseFast \
    ${target:+-Dtarget=$target} --prefix "$prefix")
}

build "aarch64-linux-android.$API" "$WORK/android"
[ "${HOST:-1}" = 0 ] || build "" "$WORK/host"

rm -rf "$OUT"
mkdir -p "$OUT/arm64-v8a"
cp -R "$WORK/android/include" "$OUT/"
cp "$WORK/android/lib/libghostty-vt.a" "$OUT/arm64-v8a/"
if [ "${HOST:-1}" != 0 ]; then
  mkdir -p "$OUT/host"
  cp "$WORK/host/lib/libghostty-vt.a" "$OUT/host/"
fi
cp "$SRC/LICENSE" "$OUT/LICENSE"
# What it was built from, for app/build.gradle.kts to notice a newer script.
shasum -a 256 "$0" | cut -d' ' -f1 > "$OUT/.source"
echo "libghostty-vt $GHOSTTY_TAG in $OUT"
