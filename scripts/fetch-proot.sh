#!/usr/bin/env bash
# Downloads Termux's prebuilt aarch64 proot + runtime deps and places them in
# app/src/main/jniLibs/arm64-v8a as lib*.so so Android extracts them into
# nativeLibraryDir, the only place an app targeting API 29+ may exec from.
set -euo pipefail

REPO=https://packages.termux.dev/apt/termux-main
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Pinned, so a build ships exactly the versions whose source the licenses screen links to
# (ui/LicensesScreen.kt). Termux's pool only keeps current versions: when a download 404s, update
# the version and SHA256 here (from $REPO/dists/stable/main/binary-aarch64/Packages) and the source
# links in LicensesScreen.kt together.
PROOT=5.1.107.96
TALLOC=2.5.0
SHMEM=0.7

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

fetch proot pool/main/p/proot/proot_${PROOT}_aarch64.deb \
  8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0
fetch libtalloc pool/main/libt/libtalloc/libtalloc_${TALLOC}_aarch64.deb \
  556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
fetch libandroid-shmem pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM}_aarch64.deb \
  0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6

P=data/data/com.termux/files/usr
mkdir -p "$OUT"
cp "$WORK/proot/$P/bin/proot" "$OUT/libproot.so"
cp "$WORK/proot/$P/libexec/proot/loader" "$OUT/libproot-loader.so"
cp -L "$WORK/libtalloc/$P/lib/libtalloc.so.2" "$OUT/libtalloc.so"
cp "$WORK/libandroid-shmem/$P/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
# Android only extracts lib*.so from the APK, so rename the DT_NEEDED entry
# libtalloc.so.2 -> libtalloc.so (same length, NUL padded) instead of relying
# on a runtime symlink.
perl -pi -e 's/libtalloc\.so\.2\0/libtalloc.so\0\0\0/g' "$OUT/libproot.so" "$OUT/libtalloc.so"
chmod 755 "$OUT"/*.so
ls -la "$OUT"
