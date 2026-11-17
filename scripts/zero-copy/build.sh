#!/bin/sh
# Zero-copy: builds, for aarch64 Linux (glibc 2.35 and newer) in a container, the Vulkan layer
# (VK_LAYER_OMA_zero_copy) into app/src/main/assets/zero-copy, which the launcher installs in the guest
# (Gpu.managed). Commit the stripped library with its source. Needs docker with arm64 images (native on Apple silicon).
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$here/../.." && pwd)
out="$here/build"
docker build --platform linux/arm64 -q -t oma-zc-build "$here" >/dev/null
docker run --rm --platform linux/arm64 -v "$root:/src" -w /src/scripts/zero-copy oma-zc-build sh -euc '
  mkdir -p build
  wayland-scanner client-header oma-ahb-v1.xml build/oma-ahb-v1-client-protocol.h
  wayland-scanner private-code oma-ahb-v1.xml build/oma-ahb-v1-protocol.c
  gcc -O2 -g -std=gnu11 -Wall -Wextra -Wno-missing-field-initializers -Wno-unused-parameter -D_GNU_SOURCE \
      -shared -fPIC -fvisibility=hidden -Wl,--no-undefined -I build -o build/libVkLayer_oma_zero_copy.so \
      oma_zero_copy_layer.c build/oma-ahb-v1-protocol.c -lwayland-client -lpthread
  strip -o build/libVkLayer_oma_zero_copy.stripped.so build/libVkLayer_oma_zero_copy.so
  file build/libVkLayer_oma_zero_copy.so
  nm -D --defined-only build/libVkLayer_oma_zero_copy.so | grep -i " T "'
mkdir -p "$root/app/src/main/assets/zero-copy"
cp "$out/libVkLayer_oma_zero_copy.stripped.so" "$root/app/src/main/assets/zero-copy/libVkLayer_oma_zero_copy.so"
echo "copied the layer to app/src/main/assets/zero-copy/"
