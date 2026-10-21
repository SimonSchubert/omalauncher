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
- `app/build.gradle.kts`: debug and release builds now fail early when the
  required proot runtime is missing.

## Evidence

- `./gradlew --no-daemon :owl:test :app:testDebugUnitTest`: passed.
- `./gradlew --no-daemon :app:assembleDebug`: passed after the debug proot guard.
- `./gradlew --no-daemon :app:lintDebug`: passed; only existing project
  warnings remained.
- Final APK SHA-256: `3075f567599f0994a042b5450ecac3e08e508df933f3ad8d47536c27959bcfaf`.
- Installed APK hash matches the local artifact on Xiaomi `Mi 9T Pro`, ADB
  serial `2d64b093`; `dev.omalauncher/.MainActivity` stayed alive.
- On-device Linux journal reached `{"setup":true}` with `packages-1`,
  `keymap-1`, and `cliamp-v2.3.0` complete; the process tree showed the
  running proot guest.
- On-device inspection found `files/arch/usr/local/bin/omalauncher-install`,
  `files/arch/root/AGENTS.md`, and `files/arch/root/CLAUDE.md`.

## Decisions

- PackageInstaller is the in-phone install seam; USB ADB remains a computer-side
  development transport. No ADB socket or key is made visible to the guest.
- The exact inspected APK is copied into app-private staging before approval,
  preventing a guest-side post-prompt file replacement from changing the bytes
  that reach PackageInstaller.

## Risks / Open Questions

- The complete guest-command-to-approval-screen flow was not triggered with a
  test APK. The user should exercise it only with a benign APK they intended to
  install; the bridge still requires both Omalauncher approval and Android's
  system confirmation.
- Android/Xiaomi may require enabling “install unknown apps” for Omalauncher;
  the approval screen links to that system setting and still leaves the system
  installer confirmation in place.

## Handoff

Next action: from the guest, run a benign local debug APK build with
`omalauncher-install`, verify the approval details, and approve only if the
package, hash, signing certificate, and permissions are expected. Owner:
Codex/user during the next device-integration pass.
