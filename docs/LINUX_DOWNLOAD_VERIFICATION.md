# Linux download verification

New Linux installs download the Arch Linux ARM archive and detached `.sig` over
HTTPS. The launcher verifies the exact archive before parsing it or executing
anything from it. The bundled build-system public key is pinned to fingerprint
`68B3537F39A313B3E574D06777193F152BDBE6A6`, independently published at
https://archlinuxarm.org/about/package-signing. Verification runs in Android using
Bouncy Castle, without invoking `gpg` from the unverified Linux image.

Missing, malformed, wrong-key, weak-hash and invalid signatures stop installation.
Downloads cannot redirect to HTTP. Interrupted downloads retain the existing
Range/If-Range resume behavior. Failed cached archives are discarded so retrying
can download a matching archive/signature pair if the upstream `latest` image
changes. A network failure fetching the small signature retains the completed
archive for retry. Images are authenticated, not certified free of vulnerabilities.

For an ADB-supplied image, put `ArchLinuxARM-aarch64-latest.tar.gz.sig` beside
`ArchLinuxARM-aarch64-latest.tar.gz` in the app's external files directory. The
archive is copied to internal cache before verification; extraction uses that
same copy. A missing or invalid signature leaves the supplied archive in place
and stops before touching the rootfs. Correct the supplied files, then retry.

Existing Linux installations and their data are unchanged. This change does not
retroactively authenticate an installed system, change the archive extractor,
add widget restrictions, or alter coding-agent behavior. Package installation
and third-party installers retain their upstream trust model.

## Validation

Run `./gradlew :owl:test :app:testDebugUnitTest :owl:owlJar :app:assembleDebug :app:lintDebug :app:lintRelease`.
The tests cover valid/altered archives, wrong keys, malformed/missing signatures,
weak hashes, the bundled key fingerprint, HTTPS downgrade rejection and redirects,
including resume headers being set before connecting and preserved on redirects.

To repeat the Android bootstrap checks in a separate ARM64 emulator:

1. Supply an invalid archive with a valid detached signature. Confirm setup fails
   signature verification before extraction and the rootfs is untouched.
2. Repeat with a missing signature, then with the authentic archive/signature.
3. Verify the authentic image extracts and Linux commands execute.
4. Test the normal HTTPS download path and retry behavior.

Local validation on October 17, 2026, after merging upstream `66e07c1`: all
234 unit tests pass (106 OWL and 128 app), the debug APK and OWL CLI jar build,
and full debug and release lint pass. Native PRoot was rebuilt with upstream's
pinned NDK 30.0.16248370. No lint suppressions or unrelated fixes are included.

On October 17, an isolated Android 16 / API 36 ARM64 emulator (system image
revision 2, emulator
37.2.12) rejected an altered archive and a missing signature while preserving a
rootfs sentinel. The official 829,367,415-byte image then verified and extracted;
PRoot successfully ran Bash, `uname -m` (`aarch64`) and `pacman -Q glibc`.
The live HTTPS downloader also resumed the final 64 KiB using Range/If-Range,
fetched the detached signature, verified the entire image and extracted it
(41.6 seconds). An earlier October 16 check with the emulator offline confirmed
that a failed signature fetch retained the completed cached image
and the rootfs sentinel. The verified bootstrap continued through pacman
configuration into package installation. These checks validate image handling
and native guest execution; they do not claim every optional package or agent
completed installation.
