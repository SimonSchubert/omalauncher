#!/usr/bin/env bash
# Prepare the small ARM64 Android tool overlay used by a phone-local custom build.
# The packages are official Termux ARM64 packages; every archive is pinned by SHA256.
set -euo pipefail

if [ "$(uname -m)" != aarch64 ]; then
  echo "this helper is only for an ARM64 Linux guest" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
CACHE="${OMALAUNCHER_ARM64_BUILD_CACHE:-$HOME/.cache/omalauncher/android-arm64-build}"
PREFIX="$CACHE/prefix"
OVERLAY="$CACHE/sdk"
PACKAGES="$CACHE/packages"
EXTRACTED="$CACHE/extracted"
NATIVE_SOURCE="${OMALAUNCHER_NATIVE_LIB_DIR:-/run/omalauncher/native}"

TERMUX_BASE=https://packages.termux.dev/apt/termux-main

# name|relative archive path|sha256
readonly -a PACKAGE_SPECS=(
  'aapt2|pool/main/a/aapt2/aapt2_16.0.0.4-4_aarch64.deb|1b3e4d716670d78fed4a66e04a1ba92827a36fe3000e9bef98208392f3100050'
  'aidl|pool/main/a/aidl/aidl_16.0.0.4-4_aarch64.deb|8245ce326db12752a37a9be471a33f189e5f5d82d695f013f79315e944aeaa69'
  'abseil-cpp|pool/main/a/abseil-cpp/abseil-cpp_20260817.0_aarch64.deb|b698963bb7096494ad928ce89cd869a53e6adf0b05f5f407b40fda9c85c6e2b0'
  'libprotobuf|pool/main/libp/libprotobuf/libprotobuf_2:36.2-1_aarch64.deb|daa3b9169e094644ba95c35d40a5cc283473144decc7dae8396c49cc51440815'
  'fmt|pool/main/f/fmt/fmt_1:12.2.0_aarch64.deb|4a2cb43ab167b32daf0d7978f21c5391d17bce47dae6aa2a8b3a8f958e663ce7'
  'libcxx|pool/main/libc/libc++/libc++_30_aarch64.deb|53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca'
  'libexpat|pool/main/libe/libexpat/libexpat_2.9.0_aarch64.deb|f8a104e8227c9c181f6818658a3ee2fa4c107fac94219b068144fc6591086cdb'
  'libpng|pool/main/libp/libpng/libpng_1.6.59_aarch64.deb|32d61f0b1a60cf7283aa3a80e288737493df2c6b84c8c749606b77c94a10f2b2'
  'libzopfli|pool/main/libz/libzopfli/libzopfli_1.0.3-5_aarch64.deb|95cd7cb2209fbafb25825f5fcd4f86f021512175608e038b1c3b8d3fa0a4fe40'
  'zlib|pool/main/z/zlib/zlib_1.3.2_aarch64.deb|75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9'
  'googletest|pool/main/g/googletest/googletest_1.18.0_aarch64.deb|98f5b032d4c992d1d9b7bc864207a6eab1c343d0e8b56b588b3d5c4e6a7e7327'
)

log() { printf '%s\n' "$*" >&2; }
fail() { log "error: $*"; exit 1; }

mkdir -p "$PACKAGES" "$EXTRACTED" "$PREFIX" "$CACHE"
command -v curl >/dev/null || fail "curl is required"
command -v sha256sum >/dev/null || fail "sha256sum is required"

download_and_extract() {
  local spec="$1" name path sha archive package_dir source_dir
  IFS='|' read -r name path sha <<<"$spec"
  archive="$PACKAGES/$(basename "$path")"
  package_dir="$EXTRACTED/$name"

  if [ ! -f "$archive" ] || ! printf '%s  %s\n' "$sha" "$archive" | sha256sum -c --status; then
    log "downloading verified ARM64 package: $name"
    local temp="$archive.tmp.$$"
    curl --fail --location --silent --show-error "$TERMUX_BASE/$path" -o "$temp"
    printf '%s  %s\n' "$sha" "$temp" | sha256sum -c --status || { rm -f "$temp"; fail "$name: SHA256 mismatch"; }
    mv -f "$temp" "$archive"
  fi

  if [ ! -f "$package_dir/.done" ]; then
    rm -rf "$package_dir"
    mkdir -p "$package_dir"
    if command -v bsdtar >/dev/null; then
      (cd "$package_dir" && bsdtar -xf "$archive")
      (cd "$package_dir" && bsdtar -xf data.tar.*)
    else
      command -v ar >/dev/null || fail "bsdtar or ar is required to extract Termux packages"
      command -v tar >/dev/null || fail "bsdtar or tar is required to extract Termux packages"
      (cd "$package_dir" && ar x "$archive" && tar -xf data.tar.*)
    fi
    source_dir="$package_dir/data/data/com.termux/files/usr"
    [ -d "$source_dir" ] || fail "$name: package has no Termux prefix"
    cp -a "$source_dir/." "$PREFIX/"
    : > "$package_dir/.done"
  fi
}

for spec in "${PACKAGE_SPECS[@]}"; do
  download_and_extract "$spec"
done

for tool in aapt2 aidl; do
  real="$PREFIX/libexec/omalauncher-$tool"
  mkdir -p "$PREFIX/libexec"
  if [ ! -x "$real" ]; then
    cp -f "$PREFIX/bin/$tool" "$real"
  fi
  cat > "$PREFIX/bin/$tool" <<EOF
#!/system/bin/sh
set -eu
export LD_LIBRARY_PATH="$PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
exec "$real" "\$@"
EOF
  chmod 755 "$PREFIX/bin/$tool"
done

[ -d "$SDK/build-tools" ] || fail "Android SDK build-tools are missing under $SDK"
rm -rf "$OVERLAY/build-tools"
mkdir -p "$OVERLAY"
for top in cmdline-tools platform-tools platforms sources system-images tools licenses ndk cmake; do
  if [ -e "$SDK/$top" ]; then
    rm -rf "$OVERLAY/$top"
    ln -s "$SDK/$top" "$OVERLAY/$top"
  fi
done
mkdir -p "$OVERLAY/build-tools"
while IFS= read -r source_dir; do
  version="${source_dir##*/}"
  target_dir="$OVERLAY/build-tools/$version"
  mkdir -p "$target_dir"
  while IFS= read -r entry; do
    name="${entry##*/}"
    if [ "$name" = aapt2 ] || [ "$name" = aidl ]; then
      ln -s "$PREFIX/bin/$name" "$target_dir/$name"
    else
      ln -s "$entry" "$target_dir/$name"
    fi
  done < <(find "$source_dir" -mindepth 1 -maxdepth 1 -print)
done < <(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -print)

# Android Gradle Plugin reads sdk.dir from this ignored, checkout-local file.
# Preserve any non-sdk settings such as ndk.dir while replacing only the SDK path.
local_properties="$ROOT/local.properties"
local_properties_tmp="$local_properties.arm64.tmp.$$"
if [ -f "$local_properties" ]; then
  sed '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' "$local_properties" > "$local_properties_tmp"
else
  : > "$local_properties_tmp"
fi
printf 'sdk.dir=%s\n' "$OVERLAY" >> "$local_properties_tmp"
mv -f "$local_properties_tmp" "$local_properties"

# Reuse native libraries from the installed launcher. The app exposes only its
# own nativeLibraryDir at this path; no Android credentials or ADB transport is
# made visible to the guest.
native_out="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$native_out"
for name in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so libwlbridge.so; do
  source="$NATIVE_SOURCE/$name"
  [ -r "$source" ] || fail "installed launcher native library is missing: $source"
  cp -f "$source" "$native_out/$name"
done

for path in "$PREFIX/libexec/omalauncher-aapt2" "$PREFIX/libexec/omalauncher-aidl" "$native_out"/*.so; do
  file "$path" 2>/dev/null | grep -q 'ARM aarch64' || fail "not an ARM64 executable or library: $path"
done

log "ARM64 Android build environment is ready"
log "source native libraries: $NATIVE_SOURCE"
log "SDK overlay: $OVERLAY"
log "run: eval \"\$(./scripts/prepare-arm64-android-build.sh --shell-env)\""

if [ "${1:-}" = --shell-env ]; then
  printf 'export OMALAUNCHER_ARM64_BUILD_CACHE=%q\n' "$CACHE"
  printf 'export OMALAUNCHER_ARM64_BUILD_TOOLS=%q\n' "$PREFIX"
  printf 'export OMALAUNCHER_NATIVE_LIB_DIR=%q\n' "$NATIVE_SOURCE"
  printf 'export ANDROID_HOME=%q\n' "$OVERLAY"
  printf 'export ANDROID_SDK_ROOT=%q\n' "$OVERLAY"
  printf 'export PATH=%q:"$PATH"\n' "$PREFIX/bin"
  printf 'export OMALAUNCHER_PREBUILT_NATIVE=true\n'
fi
