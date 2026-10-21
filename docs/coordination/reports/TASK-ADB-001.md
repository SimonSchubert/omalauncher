# TASK-ADB-001 — Role handoff

Task: `tasks/done/TASK-ADB-001.md`
Role: Product Director / Technical Director / Implementer
Status: DONE
Date: 2026-10-22

## Outcome

Added an approval-gated guest APK installation bridge. An agent runs
`omalauncher-install <guest-apk> [reason]`; Android validates and snapshots the
guest-visible APK, shows package/version/hash/certificate/permissions, requires
explicit approval, and submits it to PackageInstaller with system user action
required. The guest receives only a bounded success/error response and never
gets ADB, signing keys, or host credentials.

## Files / Artifacts

- `app/src/main/kotlin/dev/omalauncher/packageinstall/`: protocol, snapshotting,
  approval activity, PackageInstaller receiver, and result persistence.
- `app/src/main/kotlin/dev/omalauncher/ui/PackageInstallScreen.kt`: approval UI.
- `app/src/main/kotlin/dev/omalauncher/Runtime.kt` and
  `app/src/main/AndroidManifest.xml`: host integration and non-exported Android
  components.
- `README.md` and `docs/ARCHITECTURE.md`: workflow and security boundary.
- `app/src/test/kotlin/dev/omalauncher/packageinstall/`: malformed-input tests.

## Evidence

- `./gradlew --no-daemon :owl:test :app:testDebugUnitTest`: passed.
- `./gradlew --no-daemon :app:assembleDebug`: passed.
- `./gradlew --no-daemon :app:lintDebug`: passed; reported only existing
  project warnings and removable API-check warnings, which were cleaned up.
- Final APK SHA-256: `d162bb80ccc527a01f23449c631b19974662e44fe500916fb92d48cd620bebc1`.
- Installed successfully on Xiaomi `Mi 9T Pro`, ADB serial `2d64b093`; launched
  `dev.omalauncher/.MainActivity`, process stayed alive, and no launcher fatal
  exception appeared in the smoke-test log window.

## Decisions

- PackageInstaller is the in-phone install seam; USB ADB remains a computer-side
  development transport. No ADB socket or key is made visible to the guest.
- The exact inspected APK is copied into app-private staging before approval,
  preventing a guest-side post-prompt file replacement from changing the bytes
  that reach PackageInstaller.

## Risks / Open Questions

- The connected Xiaomi did not have Linux/rootfs installed, so the complete
  guest-command-to-approval-screen flow was not exercised on-device. Doing that
  requires the user to intentionally install the large Linux image and then run
  a benign local APK request.
- Android/Xiaomi may require enabling “install unknown apps” for Omalauncher;
  the approval screen links to that system setting and still leaves the system
  installer confirmation in place.

## Handoff

After Linux is intentionally installed on the phone, run a benign local debug
APK build from the guest with `omalauncher-install`, verify the approval details,
reject once, then approve once and confirm the guest receives `ok`. Owner:
Codex/user during the next device-integration pass.
