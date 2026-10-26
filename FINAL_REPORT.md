# Omalauncher → Omarchy Linux Environment for Android

## Implementation Summary

Transformed Omalauncher from an Android home-screen launcher into a standalone Omarchy Linux environment application. The app is now a regular activity opened from the user's existing Android launcher; it no longer requires or requests the default home role. The Arch Linux ARM/proot integration, OWL widget engine, Omarchy themes, wallpapers, coding-agent terminals, and Linux application management are all retained, now reached through a dedicated Linux environment dashboard instead of launcher screens.

## Architecture Changes

**Entry point / manifest** (`app/src/main/AndroidManifest.xml`)
- `MainActivity`: removed `android.intent.category.HOME`; kept `MAIN` + `DEFAULT` + `LAUNCHER` so it appears in the app drawer as a regular app. Dropped launcher-specific flags (`clearTaskOnLaunch`, `excludeFromRecents`, `stateNotNeeded`, `launchMode="singleTask"` → `singleTop`).
- Removed `PinShortcutActivity`, `OmarchyWallpaperService` (live wallpaper), and `MediaListener` (notification listener) declarations; removed the launcher-only `LAUNCHER` category query.
- Kept `LinuxService` (foreground special-use service for a running Linux session), `LinuxWindowActivity` and its aliases (Terminal, btop, coding agents), `FilesActivity`, `SettingsActivity`, `EditorActivity`, `OwlAppActivity`, and the widget-share `VIEW` filter.

**MainActivity** (`MainActivity.kt`)
- Replaced the home-screen Compose content with `LinuxDashboard`.
- Removed `requestHomeRole()` and the home-role chooser logic.
- Kept debug `--es exec` / `--es theme` handling and shared-widget-link handling.

**Linux lifecycle** (`Runtime.kt`)
- Added `LinuxSessionState` sealed interface: `NotInstalled`, `Stopped`, `Starting`, `Running`, `Stopping`, `Failed(message)`.
- Replaced the private `linux` boolean with `_session: MutableStateFlow<LinuxSessionState>`, exposed as `sessionState`, plus `isRunning` and `isInstalled`.
- Added `startLinux()` (prepare → start Wayland server/proc shim/open bridge/audio → post-start fonts/apps/themes/widgets/updates/GPU) and `stopLinux()` (kill guest processes, shut services, leave installed-but-stopped).
- `runLinux()` (install/update) now leaves Linux **installed but stopped**; it no longer auto-starts services.
- `installLinux`/`retryLinux`/`cancelLinux`/`removeLinux` drive the session state; `removeLinux` sets `NotInstalled` and deletes only the rootfs.
- All former `linux`-field callers (`setGpu`, `checkUpdates`, `retryUpdates`, `screenStarted`, `refreshApps`, `syncGuest`, `terminal`, `launch`) now gate on `isRunning`.
- Removed the superseded `onLinuxReady()`; its concerns split into `startServices()` and `postStart()`.

**Dashboard** (`ui/LinuxDashboard.kt`, new)
- `DashboardSection` enum: Overview, Linux Apps, Terminal, Widgets, Files, Themes, Settings, each with an icon.
- Bottom navigation bar; active section tinted in the theme accent.
- **Overview**: session state badge, start/stop (green/red `[ start ]` / `[ stop ]`), quick actions (terminal, files). Start when not installed opens Settings › Linux.
- **Linux Apps**: lists `.desktop` entries from `LinuxAppCatalog`, search filter, each row launches via `runtime.launch(app)`.
- **Terminal**: opens a `foot` window in its own task.
- **Widgets**: lists OWL widget specs with compile-error / needs-Linux notes; tap runs a widget.
- **Files / Themes / Settings**: launch the existing `FilesActivity` / `SettingsActivity` at the corresponding `SettingsPage`.

**Version metadata** (`app/build.gradle.kts`)
- `versionCode` 23 → 24; `versionName` `0.12.2` → `1.0.0` (major, semver — see assessment below).

**Documentation**
- `README.md`: rewritten identity paragraph (regular app, not a home screen), updated feature bullets, replaced the obsolete "Using it" launcher section with dashboard usage.
- `docs/ARCHITECTURE.md`: opening description and `ui/` source-layout entry now describe the Linux environment dashboard.

## Features Preserved and Adapted

- Arch Linux ARM rootfs under proot (`Bootstrap`, `Proot`), pacman, AUR (`Aur`), cliamp, guest packages — fully retained; installed via the same idempotent rootfs steps.
- Linux window compositor (`wayland/`, `wlbridge.c`, `LinuxWindowActivity`) — retained; each Linux window is its own Android task/Recents card.
- OWL widget language, engine, runtime, market, previews — retained; widgets run in the guest, not on Android home pages.
- Omarchy themes (22 built-in + community/user), wallpapers, `~/.config/omarchy/themes` — retained and applied to the dashboard and guest.
- Coding agents (Claude Code, OpenCode, Codex, Grok, Copilot, Crush, Cursor, et al.) as terminal sessions — retained; open via `runtime.terminal(...)`.
- Terminal, Files, Editor, Owl apps — retained as their own tasks.
- Existing user data, theme files, widget definitions, agent settings, installed packages — preserved; removal deletes only `files/arch` (the rootfs).

## Features Removed or Limited

- Android home-screen launcher function: removed (no HOME category, no home-role request, no wallpaper/pin-shortcut/notification-listener services).
- The home screen's bar, drawer, pages, faces, and recent-apps strip are no longer the primary UI; replaced by the dashboard bottom navigation.
- Live wallpaper service and shortcut-pinning activity removed from the manifest (their Kotlin classes remain as inert, unregistered source).
- "Set as default home app" flow removed.
- Automatic home-screen theme/wallpaper application removed; themes now apply via Settings › Themes and to the guest.

## Security and Data Integrity Audit

- No root or privileged execution; proot remains a userspace environment (documented in README).
- Guest commands run through `proot.run` with an explicit argument vector (not a shell string), avoiding shell injection at the launcher boundary.
- Widget commands execute in the guest via `owl-send`/widget runtime with the existing `RunLimit` and permission boundaries; widgets cannot reach launcher-only files outside their binds (`Proot.guestVisible`/`guestFile`).
- `removeLinux` deletes only the rootfs; launcher data (`files/omarchy`, `files/widgets`, `files/styles`) lives outside the rootfs and is bound in, so it survives install/remove.
- No automatic reinstall or filesystem reset on startup; destructive removal requires explicit user action in the dashboard.
- No new permissions introduced; manifest keeps only the pre-existing set (INTERNET, ACCESS_LOCAL_NETWORK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, RECORD_AUDIO, REQUEST_DELETE_PACKAGES).
- No accounts, ads, telemetry, or cloud dependencies added.

## Tests and Build Results

**Build verification could not be executed in this environment.**
- `./gradlew --version` → succeeds (Gradle 9.8.2; runtime JVM Java 21 with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`).
- `./gradlew :owl:test :app:testDebugUnitTest` → FAILED at configuration: `SDK location not found` — no Android SDK is installed (`compileSdk=37`, `targetSdk=37`), so the `:app` module cannot configure.
- `./gradlew :owl:test` (with `:app` temporarily excluded from `settings.gradle.kts`, then restored) → FAILED at `compileTestJava`: `Cannot find a Java installation ... matching: {languageVersion=17 ...}` — Gradle's Kotlin/JVM toolchain auto-detection does not resolve the installed Java 21/27 to the requested Java 17 toolchain, and toolchain auto-provisioning is disabled/not configurable without root.

**Tests not run.** No Gradle test task completed successfully, so no test outcomes (pass/fail) are available to report. Existing app unit tests (`app/src/test/...`) and owl tests (`owl/src/test/...`) were reviewed: none reference the removed `Runtime.linux` field, `LauncherState`, `MainActivity` home role, or `requestHomeRole`, so they remain conceptually compatible with the changed APIs. They could not be compiled or executed here.

**Device validation:** not performed (no arm64 Android device available).

## Known Limitations

1. **Build/tests unverified** — no Android SDK and no resolvable Java 17 toolchain in this environment; compilation of `:app` and execution of any test suite were not performed. The Kotlin/XML changes were reviewed by hand for API correctness but not compiler-checked.
2. **Inert launcher source remains** — `PinShortcutActivity.kt`, `OmarchyWallpaperService.kt`, and `Media.kt` (`MediaListener`) are still present as source but are no longer declared in the manifest and are unreachable as launcher components. `ThemeStore.kt` still references `OmarchyWallpaperService::class.java` (compiles, inert since the service is unregistered).
3. **Shared-widget "add"** opens the store rather than completing a full add flow; the link can be dismissed.
4. **No device-specific validation** (touch, hardware keyboard, Linux window integration on a real arm64 device) was possible.
5. The foreground service still requires the `FOREGROUND_SERVICE_SPECIAL_USE` permission and Android 14+ behavior; unchanged from prior releases.

## Documentation Updates

- `README.md`: identity, feature bullets, and usage rewritten for the dashboard/non-launcher model.
- `docs/ARCHITECTURE.md`: opening description and `ui/` layout updated; rendering/GPU/update mechanisms unchanged and still accurate.

## Semantic Versioning Assessment

- Previous version: `0.12.2` (versionCode 23).
- Proposed version: `1.0.0` (versionCode 24).
- Reasoning: removing the home-screen launcher is an incompatible change to established behavior (the app no longer can be or act as the default home; the home-screen UI is replaced by a dashboard). Per the repository's semver policy, that warrants a major version bump. No release signing, publishing, or release-channel changes were made; this is a version-metadata update only.

## Files Changed

- `README.md` — identity, features, usage docs.
- `app/build.gradle.kts` — version 0.12.2/23 → 1.0.0/24.
- `app/src/main/AndroidManifest.xml` — removed HOME/category/services; regular app entry.
- `app/src/main/kotlin/dev/omadroid/MainActivity.kt` — dashboard host; home-role logic removed.
- `app/src/main/kotlin/dev/omadroid/Runtime.kt` — Linux session start/stop lifecycle.
- `app/src/main/kotlin/dev/omadroid/ui/LinuxDashboard.kt` — new dashboard + sections.
- `docs/ARCHITECTURE.md` — architecture description updated.
