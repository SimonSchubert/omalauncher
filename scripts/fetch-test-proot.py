#!/usr/bin/env python3
"""Fetch the small, pinned x86_64 Termux runtime for disposable emulator fixtures.

These unmodified upstream PRoot binaries are test-only. Production arm64 uses
fetch-proot.sh and Omalauncher's patches. No distro or privileged setup needed.
"""
import hashlib
import io
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
REPO = "https://packages.termux.dev/apt/termux-main/"
PACKAGES = (
    ("pool/main/p/proot/proot_5.1.107.96_x86_64.deb",
     "77ea45540071ca543adda2b51aca2bc3761c52904d013fd0890682288eff9455",
     {"bin/proot": "libproot.so", "libexec/proot/loader": "libproot-loader.so"}),
    ("pool/main/libt/libtalloc/libtalloc_2.5.0_x86_64.deb",
     "b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530",
     {"lib/libtalloc.so.2.5.0": "libtalloc.so"}),
    ("pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb",
     "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797",
     {"lib/libandroid-shmem.so": "libandroid-shmem.so"}),
)


def payload(deb):
    if not deb.startswith(b"!<arch>\n"):
        raise ValueError("Invalid Debian archive")
    pos = 8
    while pos + 60 <= len(deb):
        header = deb[pos:pos + 60]
        size = int(header[48:58])
        pos += 60
        if header[:16].strip().rstrip(b"/").startswith(b"data.tar."):
            return deb[pos:pos + size]
        pos += size + size % 2
    raise ValueError("No data archive")


def main():
    output = ROOT / "app/src/testRuntime/jniLibs/x86_64"
    output.mkdir(parents=True, exist_ok=True)
    for filename, digest, members in PACKAGES:
        with urllib.request.urlopen(REPO + filename, timeout=30) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Checksum mismatch: {filename}")
        with tarfile.open(fileobj=io.BytesIO(payload(data))) as archive:
            for member, target in members.items():
                name = next(n for n in archive.getnames() if n.endswith("/usr/" + member))
                with archive.extractfile(name) as stream:
                    binary = stream.read()
                # Android extracts lib*.so only; keep DT_NEEDED/SONAME lengths unchanged.
                binary = binary.replace(b"libtalloc.so.2\0", b"libtalloc.so\0\0\0")
                (output / target).write_bytes(binary)
                print(target)


if __name__ == "__main__":
    main()
