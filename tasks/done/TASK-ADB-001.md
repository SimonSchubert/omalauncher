# TASK-ADB-001 — Approval-gated guest APK installation

Status: DONE
Owner: Codex
Role: Product Director / Technical Director / Implementer
Milestone: M-ADB-1
Created: 2026-10-22
Depends on: PR #188 security hardening
Files / areas owned: `app/src/main/kotlin/dev/omalauncher/packageinstall/`, `app/src/main/kotlin/dev/omalauncher/Runtime.kt`, `app/src/main/kotlin/dev/omalauncher/ui/`, managed guest scripts, Android manifest, tests, and coordination docs
Report: `docs/coordination/reports/TASK-ADB-001.md`

## Goal

Let a Linux-side coding agent request installation of a locally built APK while
keeping the APK path, package metadata, hash, and final approval in the Android
host process.

## Why / User Value

The user wants to code and build apps inside Omalauncher's Linux guest and then
install them on the phone without giving the guest ADB credentials or arbitrary
host access. Android's PackageInstaller is the phone-native installation seam.

## Inputs

- `docs/ARCHITECTURE.md`
- Existing guest runtime file bridge in `linux/OpenBridge.kt`
- Existing FileProvider in `AndroidManifest.xml` and `res/xml/file_paths.xml`
- User requirement: approval must be explicit and no malware or untrusted test payloads

## Deliverables

- [x] Validate a guest install request with strict size/path/metadata limits.
- [x] Show a host-owned approval screen with APK package, version, signing digest, SHA-256, and source path.
- [x] Require explicit user approval, then hand the APK to Android's PackageInstaller with system user action required.
- [x] Return a bounded result to the guest and retain no long-lived privilege in the guest.
- [x] Add unit tests for request parsing, malformed UTF-8, and rejection cases.
- [x] Build, install, and smoke-test the corrected APK on the connected Xiaomi device.
- [x] Complete the Linux bootstrap on-device and verify the guest helper and agent guidance.

## Acceptance Criteria

- [x] The guest cannot trigger installation merely by writing a malformed or unapproved request.
- [x] No ADB socket, ADB key, GitHub token, or signing key is exposed to Linux.
- [x] The approval UI is an Android component outside the guest/webview and rejects by default.
- [x] Only a guest-visible APK path can be requested, with a maximum size and canonical-path check.
- [x] Test and debug APKs are never downloaded from the network as part of validation.

## Test Instructions

Run the project unit tests and debug build with the isolated JDK/SDK, then
install the resulting APK only on the explicit Xiaomi serial and launch
`dev.omalauncher/.MainActivity`. Verify the Linux journal reaches `setup:true`
and that `/usr/local/bin/omalauncher-install`, `AGENTS.md`, and `CLAUDE.md`
are provisioned in the guest. The full guest-command approval interaction was
not triggered with a test APK; protocol and host-side behavior remain covered
by unit tests and the bridge is ready for a deliberate benign APK request.

## Dependencies / Risks

- A normal Android app cannot own USB ADB; PackageInstaller is the phone-native
  installation path. A future external-ADB adapter would be a separate task.
- Android may require the user to enable installation from this source; the
  system confirmation screen remains visible after the Omalauncher approval.
- Running an agent/build remains code execution inside the Linux guest; the
  bridge does not expose host secrets or signing credentials.
