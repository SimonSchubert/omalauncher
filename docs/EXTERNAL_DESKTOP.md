# External desktop bridge (proposed protocol 1)

This is an initial implementation of [proposal #34](https://github.com/SimonSchubert/omalauncher/issues/34), for upstream review. The protocol is not yet an agreed public API. It exposes a provider-rendered home and existing OWL widgets to an individually approved Android host. Both apps keep their own Android sandbox. Omalauncher owns all widget execution and its existing Linux installation; no rootfs, credential or private-directory copy is involved.

## Capabilities and limits

Android 11 (API 30) or newer is required for `SurfaceControlViewHost`. Android 10 still runs the phone launcher; bridge negotiation returns `unsupported`.

Protocol 1 advertises `home_surface`, `widgets`, `android_launch`, `work_profiles`, `search`, `key` and `surface_input`. Linux/agent windows, terminal tiles, arbitrary commands, URLs, theme-changing actions and widget-definition export are unavailable through this API. Existing widget sources and actions still execute inside Omalauncher after consent, including existing guest commands; the host cannot supply a command to execute.

Each session has its own widget instances, open widget screen, search field, focus, visibility, persisted widget values, pinned apps and placement profile. Existing widget values seed a new desktop once; subsequent connections reuse that desktop's values. Widget definitions, themes, Android app catalog and underlying data services are shared. The external picker only changes visibility on that desktop, without removing widget source files. Disconnecting stops session widget subscriptions and releases its surface; it does not stop the phone home or shared Linux/agent workloads.

External widget actions that need an unnegotiated presentation capability report `unsupported_action` instead of redirecting a phone window. Widget `androidApp(package)` actions retain their existing personal-profile meaning. Drawer/pinned work apps carry the actual profile identity.

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

- `displayId`: an existing external Android display, not display 0.
- `width`, `height`: surface size in pixels (1–8192 on each axis, at most 16,777,216 pixels).
- `profile`: a persistent host-selected layout name matching `[a-zA-Z0-9_-]{1,40}`; default `default`.

Density/font scaling come from that Android display. Profiles are namespaced by the verified host package and certificate. A host cannot select the phone's layout or another host's profile. The provider currently allows one session per UID, and four sessions overall (`busy` if exceeded).

Only `connected` returns `sessionId`, the `IDesktopSession` Binder in `session`, and a `SurfaceControlViewHost.SurfacePackage` in `surface`. Set the Bundle's class loader for `SurfacePackage` before decoding it. Attach it with `SurfaceView.setChildSurfacePackage`, then release the received parcel's native reference. The host owns its `SurfaceView`; the provider owns the view tree and view host. Use `resize`, `setVisible` and `close` for their respective lifecycle changes.

Android forwards pointer, context-click, focus and IME input through the attached child surface. Search text also has a bounded `search(query, submit)` method; it only searches Android apps, including work aliases such as `settings work`, and submitting an empty query does not launch an app. `key(KeyEvent)` forwards hardware keys to this session. Bounded `action` values are `drawer`, `search`, `widgets`, `dismiss` and `focus`; other values report `unsupported_action` with the session ID. Text is limited to 2048 characters. Hosts that supply a phone-side IME can call `search` without touching the phone launcher's input state; a general cross-process InputConnection proxy is not part of version 1.

`IDesktopHost.onAndroidLaunch(sessionId, component, user, profileSerial)` asks the originating host to launch an app. Retain **both** `UserHandle` and its stable serial number. Use `LauncherApps.startMainActivity(component, user, ..., ActivityOptions with launchDisplayId)`, after validating that the profile remains available. Work-profile quiet mode and any platform permission or display restrictions still apply. Never convert this request to an owner-profile `startActivity(Intent)` fallback. If the host cannot launch the work app, report that failure. The bridge grants no task-embedding privileges.

`onStatus` always carries the originating session ID. Closed handles cannot be reused. Host callback Binder death, service destruction, display removal, uninstall, signing changes and revocation all release sessions. Provider death invalidates all Binder/surface references; drop them and rediscover after `onServiceDisconnected`/`onBindingDied`. A reconnection creates a new session ID and reuses its placement profile. The host must ignore late events for older IDs.

## Running the sample and tests

`samples/desktop-host` is a separate APK demonstrating discovery, consent, a surface, search, launch routing, and reconnect/revoke controls. It uses a real external display if available; otherwise it creates a virtual display for lifecycle tests. This virtual test desktop is off screen. The sample is not included in the launcher APK.

```sh
./gradlew :owl:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:installDebug :desktop-host:installDebug
./gradlew :desktop-host:connectedDebugAndroidTest
```

The cross-process instrumentation test covers version mismatch, unauthorized open, provider consent, surface creation/resize/visibility, Android launch identity, immediate revocation, stale handles, and reconnect. The pure policy tests cover UID/package/certificate mismatch and allocation/profile-name bounds. The existing work-profile support is upstream commit `6bef351`; this bridge preserves its keys, badged icons, work search aliases and stable serials.

The optional work-profile test runs with `-Pandroid.testInstrumentationRunnerArguments.workSerial=<serial>` and requires an unlocked managed profile containing `com.google.android.deskclock` (Clock), also installed in the owner profile. It asserts the work alias returns that profile's UserHandle and serial instead of the personal copy. Without this fixture, that test is skipped.

Work-app launches, app info and uninstall resolve their stable profile serial again at action time. A removed profile or reused user ID cannot silently target a replacement profile. The bridge also checks a catalog entry's serial before returning its launch request; hosts must revalidate the callback's serial at launch time.

Physical-display input/IME, simultaneous phone/external interaction, host/provider process death, unplug/replug, signing rotation and work-profile lifecycle remain explicit acceptance scenarios for hardware testing. The wire contract should be reviewed upstream before omalateral replaces its current embedded desktop with this adapter.
