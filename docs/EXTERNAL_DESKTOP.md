# Desktop integration and bridge (proposed protocol 1)

This is an initial implementation of [proposal #34](https://github.com/SimonSchubert/omalauncher/issues/34), refreshed on Omalauncher v0.9.0 for upstream review. The protocol is not yet an agreed public API. It exposes a provider-rendered home and existing OWL widgets to an individually approved Android host. Both apps keep their own Android sandbox. Omalauncher owns all widget execution and its existing Linux installation; no rootfs, credential or private-directory copy is involved.

Omalateral needs this provider-side entry point to show the user's actual Omalauncher home on an external display. A separate app cannot safely read Omalauncher's private layout, widget state, theme, or Linux files. The bridge renders those in Omalauncher's process and gives the approved host only a surface, bounded input, appearance metadata and app-launch requests. Omalateral can then add its own tiling, cursor and phone controls around the home. The same contract is usable by other desktop shells, and Android's native desktop uses the separate secondary-home Activity without binding the service.

## Android desktop and alternative shells

Omalauncher supplies the home, widget runtime, themes and app identities. The active desktop shell supplies window management. Two adapters use the same `DesktopHomeSession`:

- **Android desktop:** `NativeDesktopActivity` is a resizable, standard-launch-mode Activity registered for `android.intent.category.SECONDARY_HOME`. It uses Android's cursor, keyboard, IME, caption bars and task/window behavior. Safe drawing insets reserve system decorations. Wide windows show adjacent tile pages; face pages fill the window on their own. Narrow windows use a pager. Each display has an independent native profile. Resizing or moving the window updates its context and session without relocating the phone launcher.
- **Alternative shells:** an approved client embeds a surface through Binder. Omalateral is one such client and owns its window layout, phone input controls, cursor and app hosting. The provider requires no Omalateral-specific package, helper, hardware SDK or window-management API. Other launchers, desktop apps and OEM adapters can use the same contract.

Android's system desktop does **not** automatically bind arbitrary Binder services. The native Activity is the platform entry point. Android can choose its secondary home when Omalauncher is the selected launcher and the device supports secondary homes; OEMs may retain their own desktop home. It can also open as an ordinary desktop window with `dev.omalauncher.action.DESKTOP_HOME`, explicitly scoped to the provider package. This does not enable desktop mode, replace SystemUI or force freeform windowing. Android decides which displays can host tasks and how apps open there.

Native home registration follows [Android's secondary-home requirements](https://source.android.com/docs/core/display/multi_display/system-decorations#launcher), including permitting multiple Activity instances. Insets and dynamic sizing follow [desktop-windowing guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-desktop-windowing). Native profiles are currently keyed by display ID; a reconnected display receiving a new ID gets a fresh clone. Bridge clients can choose stable profile names.

## Capabilities and limits

Android 11 (API 30) or newer is required for `SurfaceControlViewHost`. Android 10 still runs the phone launcher; bridge negotiation returns `unsupported`.

Protocol 1 advertises `home_surface`, `widgets`, `android_launch`, `work_profiles`, `search`, `key`, `surface_input`, `pointer`, `home_pages`, `appearance`, `multi_display` and `system_launch`. Linux/agent windows, live terminal tiles, arbitrary commands, URLs, phone-side sharing/haptics, theme-changing actions and widget-definition export are unavailable through this API. Terminal placements remain visible as placeholders so their home pages are retained. Existing widget sources and actions still execute inside Omalauncher after consent, including existing guest commands; the host cannot supply a command to execute.

Each session has its own widget instances, open widget screen, search field, focus, visibility, persisted widget values, pinned apps and placement profile. Existing widget values seed a new desktop once; subsequent connections reuse that desktop's values. The initial copy includes the phone's page assignments and grid column count, including face pages. Widget definitions, themes, Android app catalog and underlying data services are shared. The external picker only changes visibility on that desktop, without removing widget source files. Disconnecting stops session widget subscriptions and releases its surface; it does not stop the phone home or shared Linux/agent workloads.

External widget actions that need an unnegotiated presentation capability report `unsupported_action` instead of redirecting a phone window. Widget `androidApp(package)` actions retain their existing personal-profile meaning. Drawer/pinned work apps carry the actual profile identity. Pinned Android app shortcuts that open a specific app screen remain unavailable through protocol 1; ordinary app tiles use the current launcher activity and label.

## Discovery and consent

The service action is `dev.omalauncher.action.EXTERNAL_DESKTOP`, implemented by `dev.omalauncher/.desktop.ExternalDesktopService`. Bind explicitly to the provider package with `BIND_AUTO_CREATE`, and declare package visibility:

```xml
<queries><package android:name="dev.omalauncher" /></queries>
```

Use the three AIDL interfaces in `app/src/main/aidl/dev/omalauncher/desktop`. `IDesktopBridge.negotiate(1)` returns a Bundle with `protocol`, `capabilities` and `status`. `ready` means approval exists; it is not a connected desktop. Neither binding, discovery nor requesting consent initializes the runtime or starts Linux.

`requestConsent()` returns `consent_required` and an immutable, one-shot `PendingIntent` in `consent`. Launch its `IntentSender` from a user action. The provider's approval screen shows the verified package and signing certificate. Re-negotiate when returning to the host. Denial, expiration and provider process death require a fresh request; a request expires after two minutes. If the provider is absent, unsupported, denied or failed, retain the host's standalone desktop and show that status.

Approval is bound to the actual Binder caller UID's sole package and current SHA-256 signing certificate(s). Package names/identity supplied by a client are never trusted. Shared UIDs are declined. Different host signing keys are supported; a signature permission is deliberately not required. Signing rotation requires fresh consent. Uninstall and explicit revocation remove approval; every session call and launch action revalidates identity and approval. The phone's settings → external desktops lists grants and revokes their sessions immediately. `revokeAccess()` lets a host revoke only its own approval.

## Opening and using a surface

After `ready`, create a `SurfaceView` in the host's external-display window. Wait for its surface and non-null `hostToken`. Call `openSession(1, metrics, surfaceView.hostToken, hostCallback)` with:

- `displayId`: an existing Android display, including display 0 for a desktop window on a tablet or primary screen.
- `width`, `height`: surface size in pixels (1–8192 on each axis, at most 16,777,216 pixels).
- `profile`: a persistent host-selected layout name matching `[a-zA-Z0-9_-]{1,40}`; default `default`.
- `pagesSideBySide`: optional Boolean, default false. Show all home pages in adjacent columns instead of paging through them.
- `inheritPhoneLayout`: optional Boolean, default false. Seed this profile once with the phone's page assignments, cell sizes, pinned apps and folders. Subsequent desktop edits persist independently. Widget definitions and the selected theme continue to follow the provider.
- `launchMode`: `host` (default) returns requests to the host; `system` uses Android's public LauncherApps/ActivityOptions path on the session display. Neither supplies embedding privileges or changes windowing mode.

Density/font scaling come from that Android display. Profiles are namespaced by the verified host package and certificate. A host cannot select the phone's layout or another host's profile. An approved host can use multiple displays or windows with distinct profiles. Four bridge sessions are allowed overall; opening the same profile twice under one UID returns `busy` to avoid conflicting placement writes.

Only `connected` returns `sessionId` and the `IDesktopSession` Binder in `session`. Call the typed `session.getSurface()` method to receive a `SurfaceControlViewHost.SurfacePackage`. Attach it with `SurfaceView.setChildSurfacePackage`; ownership transfers to the SurfaceView, which releases the package when replaced or detached. Release it yourself only if attachment fails. The typed AIDL return releases the provider's temporary parcel reference. The host owns its `SurfaceView`; the provider owns the view tree and view host. Use `resize`, `setVisible` and `close` for their respective lifecycle changes.

Android delivers physical input through the attached child surface. Events synthesized by a host's phone touchpad using `View.dispatchTouchEvent` do not automatically cross that process boundary: forward them with `pointer(MotionEvent)` in surface-local pixel coordinates. Touch, hover, button and scroll events are supported; the host should draw its cursor above the SurfaceView and must avoid forwarding physical events twice. Events are bounded to 16 pointers, 32 history samples and finite coordinates within ±16384 pixels. These methods are appended after the original session methods so their transaction IDs remain stable.

`describe()` returns only the approved session's `sessionId`, `displayId`, `launchMode`, ordered `pages`, `pagesSideBySide`, `overlay`, `query`, status `message`, drawer scroll position, and provider theme name/color `palette`. A host can use this to match its shell colors and panel state. Search text has a bounded `search(query, submit)` method; it only searches Android apps, including work aliases such as `settings work`, and submitting an empty query does not launch an app. `key(KeyEvent)` forwards hardware keys to this session. Bounded `action` values are `drawer`, `search`, `widgets`, `dismiss` and `focus`; other values report `unsupported_action` with the session ID. Text is limited to 2048 characters. Hosts that supply a phone-side IME can call `search` without touching the phone launcher's input state; a general cross-process InputConnection proxy is not part of version 1.

`IDesktopHost.onAndroidLaunch(sessionId, component, user, profileSerial)` asks the originating host to launch an app. This callback is used in `host` launch mode. In `system` mode, the provider launches through Android instead. Android display trust, foreground-launch rules and profile restrictions still apply. On Android 14+, a foreground client requesting provider-side launches must bind with `BIND_ALLOW_ACTIVITY_STARTS`; approval alone does not grant launch privileges. Unsupported launches report a session message instead of switching displays or profiles. Retain **both** `UserHandle` and its stable serial number. Use `LauncherApps.startMainActivity(component, user, ..., ActivityOptions with launchDisplayId)`, after validating that the profile remains available. Work-profile quiet mode and any platform permission or display restrictions still apply. Never convert this request to an owner-profile `startActivity(Intent)` fallback. If the host cannot launch the work app, report that failure. The bridge grants no task-embedding privileges.

`onStatus` always carries the originating session ID. Closed handles cannot be reused. Host callback Binder death, service destruction, display removal, uninstall, signing changes and revocation all release sessions. Provider death invalidates all Binder/surface references; drop them and rediscover after `onServiceDisconnected`/`onBindingDied`. A reconnection creates a new session ID and reuses its placement profile. The host must ignore late events for older IDs.

## Validation

On the v0.9.0 refresh, `:owl:test`, the focused desktop JVM tests, debug and minified release APK builds, debug lint, and instrumentation APK compilation passed. The Pixel 9 API 35 emulator passed all three provider instrumentation tests: keyboard dispatch, independent cloned placement/folders/profile identity (including grid columns), and rendered native desktop sessions with resize and display moves. Release APK signing and 16 KiB alignment were verified locally. A full Windows app JVM run had 12 failures outside the desktop package, including missing `sh`, Windows-reserved `nul`, managed-file replacement errors, and one OWL README sample assertion; it is not counted as passing. The current v0.9.0 build has not yet been exercised with an Omalateral host or physical OEM desktop mode.

Build and run the provider checks with the existing Gradle project:

```sh
./gradlew :owl:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

The native test uses a captured virtual display to check rendered home content, separate Activity instances, keyboard input, resizing and moving an existing window between displays. Layout tests check that inherited cells, pages, folders and profile identities persist independently from the source home. JVM policy tests cover caller identity, revocation, closed leases, layout names and allocation bounds.

Phone and Linux window keyboard handling uses the public `Window.Callback` API rather than overriding AndroidX's restricted `ComponentActivity.dispatchKeyEvent`. Unhandled keys continue through the original callback. The launcher input test checks normal typing, shortcut interception and AndroidX Back dispatch.

A separate local host harness has also checked cross-process consent/denial, rendering, resize/hide/show, host callbacks, revocation, provider death/reconnect, independent primary/external sessions and system launches on trusted displays against a minified APK. The harness and preview/build tooling are kept outside this upstream change. An optional managed-profile fixture was unavailable for that run.

Physical/OEM secondary-home selection, input/IME, simultaneous phone/external use, unplug/replug, signing rotation and work-profile lifecycle remain hardware acceptance scenarios. The bridge preserves the existing app catalog and validates stable profile serials; phone-side launch, app-info and uninstall hardening is proposed separately in [PR #76](https://github.com/SimonSchubert/omalauncher/pull/76).
