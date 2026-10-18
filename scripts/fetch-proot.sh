#!/usr/bin/env bash
# Builds proot from Termux's source with our patches (patches/proot) and downloads Termux's prebuilt
# aarch64 runtime deps, placing them in app/src/main/jniLibs/arm64-v8a as lib*.so so Android
# extracts them into nativeLibraryDir, the only place an app targeting API 29+ may exec from.
# Needs the Android NDK (NDK_VERSION below, in the SDK at ANDROID_HOME or local.properties) and bsdtar.
set -euo pipefail

REPO=https://packages.termux.dev/apt/termux-main
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Pinned, so a build ships exactly the versions whose source the licenses screen links to
# (ui/LicensesScreen.kt). Termux's pool only keeps current versions: when a download 404s, update
# the version and SHA256 here (from $REPO/dists/stable/main/binary-aarch64/Packages) and the source
# links in LicensesScreen.kt together. PROOT's SHA256 is the one termux-packages pins in
# packages/proot/build.sh.
PROOT=5.1.107.96
TALLOC=2.5.0
SHMEM=0.7
# The NDK Termux builds proot with (its clang version string), and one GitHub's Ubuntu runners have.
# app/build.gradle.kts builds wlbridge with it too, and CI installs it from here.
NDK_VERSION=30.0.16248370

fetch() {
  local pkg=$1 file=$2 sha=$3
  echo "fetching $file"
  mkdir -p "$WORK/$pkg"
  curl -fsSL "$REPO/$file" -o "$WORK/$pkg/pkg.deb" || {
    echo "$file is gone from Termux's repo; update the pins in $0" >&2
    exit 1
  }
  echo "$sha  $WORK/$pkg/pkg.deb" | shasum -a 256 -c --status || { echo "$file: SHA256 mismatch" >&2; exit 1; }
  # bsdtar reads .deb (ar) archives, unlike macOS ar and GNU tar
  (cd "$WORK/$pkg" && bsdtar xf pkg.deb && bsdtar xf data.tar.*)
}

fetch libtalloc pool/main/libt/libtalloc/libtalloc_${TALLOC}_aarch64.deb \
  556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
fetch libandroid-shmem pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM}_aarch64.deb \
  0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6

echo "fetching proot $PROOT source"
curl -fsSL "https://github.com/termux/proot/archive/v$PROOT.zip" -o "$WORK/proot.zip"
echo "75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa  $WORK/proot.zip" |
  shasum -a 256 -c --status || { echo "proot $PROOT source: SHA256 mismatch" >&2; exit 1; }
(cd "$WORK" && bsdtar xf proot.zip)
SRC="$WORK/proot-$PROOT"
for p in "$ROOT"/patches/proot/*.patch; do
  echo "applying $(basename "$p")"
  patch -d "$SRC" -p1 --quiet < "$p"
done

SDK="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null)}"
# Not ANDROID_NDK_HOME: CI runners point it at an older default NDK.
NDK="$SDK/ndk/$NDK_VERSION"
[ -d "$NDK" ] || { echo "no NDK at $NDK; install ndk;$NDK_VERSION with sdkmanager" >&2; exit 1; }
TC="$(echo "$NDK"/toolchains/llvm/prebuilt/*/bin)"

# Link against the Termux talloc and shmem we ship, so proot finds the same ABI at runtime.
P=data/data/com.termux/files/usr
DEPS="$WORK/deps"
mkdir -p "$DEPS/lib"
cp -R "$WORK/libtalloc/$P/include" "$WORK/libandroid-shmem/$P/include" "$DEPS/"
cp -L "$WORK/libtalloc/$P/lib/libtalloc.so.$TALLOC" "$DEPS/lib/libtalloc.so"
cp "$WORK/libandroid-shmem/$P/lib/libandroid-shmem.so" "$DEPS/lib/"

echo "building proot"
# Same flags as termux-packages (packages/proot/build.sh and the hardening in
# scripts/build/toolchain/termux_setup_toolchain_30.sh). The loader path compiled in is never used:
# the app always sets PROOT_LOADER (linux/Proot.kt). Newer clang makes implicit declarations an
# error, and ashmem_memfd.c leans on them.
build() {
  CFLAGS="-fstack-protector-strong -Oz -Wno-error=implicit-function-declaration" \
    CPPFLAGS="-I$DEPS/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT\\\"" \
    LDFLAGS="-L$DEPS/lib -Wl,--as-needed -Wl,-z,relro,-z,now" \
    PROOT_UNBUNDLE_LOADER=/unused \
    make -C "$SRC/src" -j"$(getconf _NPROCESSORS_ONLN)" PROOT_WITH_LIBANDROID_SHMEM=true \
    CC="$TC/aarch64-linux-android29-clang" LD="$TC/aarch64-linux-android29-clang" \
    STRIP="$TC/llvm-strip" OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump" \
    "$@" >>"$WORK/build.log" 2>&1 || { tail -30 "$WORK/build.log" >&2; exit 1; }
}
build loader/loader
# proot jumps into the loader at pokedata_workaround on kernels whose PTRACE_POKEDATA fails. The
# makefile finds the offset with the host's readelf and GNU awk (strtonum); without them it
# silently writes 0, so compute it with the NDK's readelf instead.
sym() { "$TC/llvm-readelf" -s "$SRC/src/loader/loader" | awk -v name="$1" '$8 == name { print $2 }'; }
OFFSET=$(( 0x$(sym pokedata_workaround) - 0x$(sym _start) ))
[ "$OFFSET" -gt 0 ] || { echo "can't find pokedata_workaround in the loader" >&2; exit 1; }
printf '#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=%d;\n' "$OFFSET" >"$SRC/src/loader/loader-info.c"
build proot

mkdir -p "$OUT"
cp "$SRC/src/proot" "$OUT/libproot.so"
cp "$SRC/src/loader/loader" "$OUT/libproot-loader.so"
"$TC/llvm-strip" "$OUT/libproot.so"
cp -L "$WORK/libtalloc/$P/lib/libtalloc.so.2" "$OUT/libtalloc.so"
cp "$WORK/libandroid-shmem/$P/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
# Android only extracts lib*.so from the APK, so rename the DT_NEEDED entry
# libtalloc.so.2 -> libtalloc.so (same length, NUL padded) instead of relying
# on a runtime symlink.
perl -pi -e 's/libtalloc\.so\.2\0/libtalloc.so\0\0\0/g' "$OUT/libproot.so" "$OUT/libtalloc.so"
chmod 755 "$OUT"/*.so
ls -la "$OUT"
