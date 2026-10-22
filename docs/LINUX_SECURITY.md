# Linux security model

Omalauncher runs Arch Linux ARM inside the Android app sandbox and a PRoot guest. The rootfs archive is fetched over HTTPS and verified against the pinned Arch Linux ARM signing key before extraction. Pacman is configured to use HTTPS mirrors and to require trusted package signatures; system updates are full upgrades rather than partial upgrades.

The Arch User Repository is a different trust level. AUR entries are build scripts, not packages reviewed or signed by Arch. Official repository packages install only when pacman accepts a trusted signature. AUR packages never become signed just because their metadata is old: recent or undated entries require an explicit package-page review, and entries whose metadata is at least 14 days old still require a separate one-time opt-in to allow mature AUR installs. Terminal and agent calls to the AUR wrapper are rejected because the wrapper requires a read-only marker bound at `/dev/omalauncher-aur-approval` only for the app's isolated approval process; a guest-set environment variable is not sufficient.

Approved AUR jobs run with:

- an empty temporary home instead of `/root`, so Codex configuration, source checkouts, and tokens are not mounted;
- no Omalauncher runtime bind, Android device-action inbox, or Android SDK/native-library bind;
- a clean environment with only the build user's home, PATH, and locale;
- an unprivileged `builder` for `git`, `makepkg`, and build scripts; its sudo rule is limited to `/usr/bin/pacman`.

This is defense in depth, not a claim that an approved AUR package is safe. Its package install script can still modify the guest and run with package-manager privileges. Review `PKGBUILD`, `.install`, and source changes before approving; use an official repository package when one exists.

The coding-agent bootstrap does not execute the moving `mise.run` shell script. It downloads a source-pinned ARM64 `mise` binary, verifies its SHA-256, and installs fuzzy agent updates only after a seven-day release-age window. Updating that pinned version or checksum is an intentional source change.

The agent guidance injected into the guest repeats these rules so a phone-side agent does not accidentally bypass the package-screen approval path.
