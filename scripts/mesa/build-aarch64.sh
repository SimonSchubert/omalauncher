#!/bin/bash
# Reproduce the OpenGL bundle: Mesa 26.2.4 plus
# 0001-egl-wayland-keep-zink-on-shm.patch and
# 0002-zink-acquire-swapchain-resolve-before-use.patch, built for aarch64 under qemu-user.
#
# Usage:
#   scripts/mesa/build-aarch64.sh
#   MESA_SRC=/path/to/mesa scripts/mesa/build-aarch64.sh
#
# Writes $CACHE/mesa-zink.tar.xz and prints its sha256. The guest install
# pins that sha256. A fresh run that does not match has to be reviewed
# before the pin moves.
set -euo pipefail

CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/oma-mesa-build"
ROOT="$CACHE/root"
PATCH="$(cd "$(dirname "$0")" && pwd)/0001-egl-wayland-keep-zink-on-shm.patch"
PATCH_ZINK="$(cd "$(dirname "$0")" && pwd)/0002-zink-acquire-swapchain-resolve-before-use.patch"

MESA_URL="https://gitlab.freedesktop.org/mesa/mesa/-/archive/mesa-26.2.4/mesa-mesa-26.2.4.tar.gz"
MESA_SHA256="89d4c3bf09fac8968f4198d00a6328e5e0df02f18d4b17b8eedc6b1a5773ae6b"
UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
UBUNTU_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
WAYLAND_URL="https://gitlab.freedesktop.org/wayland/wayland/-/archive/1.24.0/wayland-1.24.0.tar.gz"
WAYLAND_SHA256="7800858844751fc7113d7df3678dc6b58b26a056176a65c49a059763045bffd5"
MESON_SPEC="meson==1.12.1"

mkdir -p "$CACHE"
exec > >(tee -a "$CACHE/build.log") 2>&1
echo "mesa build start $(date -Is)"

fetch() {
  local url="$1" dest="$2" sha="$3"
  if [ -f "$dest" ] && echo "$sha  $dest" | sha256sum -c -; then
    return 0
  fi
  curl -fL --retry 3 -o "$dest.partial" "$url"
  mv "$dest.partial" "$dest"
  echo "$sha  $dest" | sha256sum -c -
}

if [ -z "${MESA_SRC:-}" ]; then
  fetch "$MESA_URL" "$CACHE/mesa-src.tar.gz" "$MESA_SHA256"
  if [ ! -f "$CACHE/mesa-src/mesa-mesa-26.2.4/meson.build" ]; then
    rm -rf "$CACHE/mesa-src"
    mkdir -p "$CACHE/mesa-src"
    tar -C "$CACHE/mesa-src" -xzf "$CACHE/mesa-src.tar.gz"
  fi
  MESA_SRC="$CACHE/mesa-src/mesa-mesa-26.2.4"
fi
if [ ! -f "$MESA_SRC/meson.build" ]; then
  echo "mesa source missing at $MESA_SRC" >&2
  exit 1
fi

if ! grep -q 'disp->Options.ForceSoftware || disp->Options.Zink' \
  "$MESA_SRC/src/egl/drivers/dri2/platform_wayland.c"; then
  patch -p1 -d "$MESA_SRC" < "$PATCH"
fi
if ! grep -q 'must be acquired before the batch uses' \
  "$MESA_SRC/src/gallium/drivers/zink/zink_context.c"; then
  patch -p1 -d "$MESA_SRC" < "$PATCH_ZINK"
fi

if [ ! -x "$ROOT/bin/bash" ]; then
  fetch "$UBUNTU_URL" "$CACHE/ubuntu-base.tar.gz" "$UBUNTU_SHA256"
  mkdir -p "$ROOT"
  tar -C "$ROOT" -xzf "$CACHE/ubuntu-base.tar.gz"
fi
cp /etc/resolv.conf "$ROOT/etc/resolv.conf"
mkdir -p "$ROOT/usr/sbin" "$ROOT/proc" "$ROOT/sys" "$ROOT/dev" "$ROOT/mesa" \
  "$ROOT/dest" "$ROOT/etc/apt/apt.conf.d"
# This user namespace maps uid 0 only, so apt cannot drop to the _apt user.
printf 'APT::Sandbox::User "root";\n' > "$ROOT/etc/apt/apt.conf.d/99-no-sandbox"
printf '#!/bin/sh\nexit 101\n' > "$ROOT/usr/sbin/policy-rc.d"
chmod +x "$ROOT/usr/sbin/policy-rc.d"

cat > "$ROOT/build-inner.sh" << EOF
set -eux
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \\
  build-essential ninja-build pkg-config python3-mako python3-yaml python3-packaging python3-pip curl \\
  flex bison cmake \\
  libwayland-dev libdrm-dev \\
  libx11-dev libxext-dev libxfixes-dev libxrandr-dev libxrender-dev \\
  libxcb1-dev libx11-xcb-dev libxcb-glx0-dev libxcb-dri3-dev \\
  libxcb-present-dev libxcb-shm0-dev libxcb-xfixes0-dev libxshmfence-dev \\
  libelf-dev zlib1g-dev libexpat1-dev libffi-dev libglvnd-dev
cd /mesa
pip3 install --break-system-packages '$MESON_SPEC'
hash -r
meson --version
# Ubuntu 24.04 Wayland has no wayland-egl-backend.pc. Mesa 26.2 requires it.
if ! pkg-config --exists 'wayland-egl-backend >= 3'; then
  curl -fL --retry 3 -o /tmp/wayland.tar.gz '$WAYLAND_URL'
  echo '$WAYLAND_SHA256  /tmp/wayland.tar.gz' | sha256sum -c -
  rm -rf /tmp/wayland-src /tmp/wayland-build
  mkdir -p /tmp/wayland-src
  tar -C /tmp/wayland-src --strip-components=1 -xzf /tmp/wayland.tar.gz
  meson setup /tmp/wayland-build /tmp/wayland-src \\
    --prefix=/usr --libdir=lib/aarch64-linux-gnu \\
    -Ddocumentation=false -Ddtd_validation=false -Dtests=false
  ninja -C /tmp/wayland-build install
fi
rm -rf /build
meson setup /build \\
  --prefix=/usr/local/omalauncher-mesa \\
  --libdir=lib \\
  -Dbuildtype=release \\
  -Db_ndebug=true \\
  -Dplatforms=wayland,x11 \\
  -Dgallium-drivers=zink \\
  -Dvulkan-drivers=[] \\
  -Degl=enabled \\
  -Dglvnd=enabled \\
  -Dglx=disabled \\
  -Dllvm=disabled \\
  -Dgles1=disabled \\
  -Dgles2=enabled \\
  -Dshared-glapi=enabled \\
  -Dgbm=enabled \\
  -Dc_link_args='-Wl,-rpath,\$ORIGIN,-rpath,\$ORIGIN/..' \\
  -Dcpp_link_args='-Wl,-rpath,\$ORIGIN,-rpath,\$ORIGIN/..' \\
  -Dtools=[] \\
  -Dvideo-codecs=[] \\
  -Dxmlconfig=disabled
ninja -C /build -j4
rm -rf /dest
DESTDIR=/dest ninja -C /build install
find /dest -type f \\( -name '*.so' -o -name '*.so.*' \\) -exec strip --strip-unneeded {} +
tar -C /dest -cJf /mesa-zink.tar.xz usr/local/omalauncher-mesa
sha256sum /mesa-zink.tar.xz
sha256sum /dest/usr/local/omalauncher-mesa/lib/libEGL_mesa.so
EOF
chmod +x "$ROOT/build-inner.sh"

# A user namespace may mount proc only after it has a new pid namespace.
unshare -U -r -m -p -f --propagation private bash -c "
set -euo pipefail
mount --bind '$ROOT' '$ROOT'
mount -t proc proc '$ROOT/proc'
mount -t sysfs sysfs '$ROOT/sys' || true
mount --rbind /dev '$ROOT/dev'
mount --bind '$MESA_SRC' '$ROOT/mesa'
chroot '$ROOT' bash /build-inner.sh
"
cp "$ROOT/mesa-zink.tar.xz" "$CACHE/mesa-zink.tar.xz"
echo "mesa build done $(date -Is)"
sha256sum "$CACHE/mesa-zink.tar.xz"
