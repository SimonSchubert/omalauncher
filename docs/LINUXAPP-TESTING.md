# LinuxApp device acceptance checks

These checks establish that a Wayland client renders inside a face and that
sessions, input and processes behave correctly on Android. Host tests cover
language syntax, session logic, protocol events and compilation. Record device
results separately; leave untested cases pending before marking a PR ready.

## Install the test bundle

Use an arm64 phone with Android 10+ and a full `app-embedded.apk` containing the
proot runtime. Record the commit from BUILD.txt and verify SHA256SUMS in the
`linux-app-test` artifact produced by the **LinuxApp test APK** workflow.

1. Enable USB debugging, connect the phone, accept the connection and check
   `adb devices`. With multiple devices, set `ANDROID_SERIAL` to the intended one.
2. Install the APK with `adb install -r app-embedded.apk`.
3. Open **Linux Face Prototype**, or run:

   ```sh
   adb shell am start -n dev.omalauncher.embedded/dev.omalauncher.MainActivity
   ```

4. Install Linux in this application's settings and wait for setup to finish.
   The embedded variant has its own data and Linux installation; it need not be
   the default launcher for this test.
5. Open the seeded `linux app demo` face and install its declared `python` and
   `sdl2` packages through the widget dependency mechanism.

If an update fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, record the error
and stop installation. CI debug signing keys can differ between builds; do not
automatically uninstall an existing app and erase its Linux data to resolve it.

## Acceptance matrix

For each case record PASS/FAIL, observed behaviour and a log/recording reference.
A PID alone is not evidence of rendering or successful process cleanup.

| ID | Action | Expected result |
| --- | --- | --- |
| start | Tap the demo and wait for the coloured bars. | The picture appears inside the face, without a separate Android task for the client. |
| pointer | Activate the window with another tap, then touch the bars. | Colours change; surrounding OWL UI remains accessible. |
| keyboard | Open ⌨ and press Space; also try a hardware keyboard if available. | Keys reach the active client and change colours. Escape exits. |
| controls | After starting, hold OWL Left + A together for at least five seconds, then release A, then Left. Repeat with a diagonal + A/B and Start/Select. | Corresponding bottom blocks light simultaneously and go dark individually; no home menu or tile editor opens, and touching outside the client does not deactivate other held controls. |
| control-cancel | Hold OWL controls and slide out, use Back, change pages, open the drawer, switch apps and lock/unlock. Hide/remove a control or change its keys/target while held. | Every cancelled key is released. Returning does not replay old presses; a fresh press works. |
| control-ownership | Use two controls bound to Z, two fingers on one control, and a physical Z key if available. Release one source at a time. | Z remains held until its final source releases. Other controls continue working. |
| control-target | Use two LinuxApp nodes with distinct inputIds; also try duplicate inputIds, a missing/hidden target and invalid dynamic keys. | Controls route only to the named session. Missing/ambiguous/invalid controls are disabled; stale events never reach another client. |
| focus | While touching or holding a key, use Back, an outside tap, a page change and an app switch. | The gesture ends, no input stays pressed, and the next window receives no stale events. |
| lock | Hold a touch or mouse button while locking the phone, then unlock. | New input works without a stuck gesture. |
| resize | Rotate the phone and show/hide the soft keyboard. | The client receives the new size, input coordinates match, and the original space is restored after keyboard dismissal. |
| stop | Stop with × and restart; repeat while the client is starting. | The previous process and its descendants end; restart creates an independent invocation. |
| instances | Create two auto-ID nodes, keyed loop items and two uses of a component; reorder the list. | Each has its own session; keyed reordering preserves ownership. |
| identity | Use duplicate explicit IDs and a client that publishes a mismatched ID. | The duplicate reports an error; a mismatched node shows its expected ID and remains stoppable. |
| lifecycle | Change command/appId during startup, remove the whole widget, then add it again. | Cancelled startup does not open a late ordinary task; removing the widget ends its owned processes. |
| errors | Run a missing executable, then retry with a valid command. | There is a readable error and a working retry; the launcher remains responsive. |
| regression | Run an ordinary Terminal, the foot LinuxApp example and a compatible client's popup. | Terminal behaviour is preserved; the popup renders and accepts input correctly. |

Leaving a page, hiding a conditional node or removing an item from a list retains
its session until the whole widget is removed/reloaded, or a changed command is
next rendered for its path. Stop it with × before hiding it when termination is
wanted. A process still alive after three seconds must show an incomplete-stop
diagnostic and retain its ID; that case is not a successful-stop result. Host
process tests do not establish proot's guest-tree cleanup on Android.

## Collect results

After reproducing a case while the application is still running, use the script
from the repository:

```sh
bash scripts/collect-linuxapp-logs.sh focus
```

The downloaded test bundle has the script at its root instead:

```sh
bash collect-linuxapp-logs.sh focus
```

Replace `focus` with an ID from the matrix. The script reads the phone model,
Android version, app version and the logcat records for the running
`dev.omalauncher.embedded` process. It does not clear log buffers or app data.
`ANDROID_SERIAL` selects the device; `LINUXAPP_DIAGNOSTICS_DIR` can change the
parent of the generated `diagnostics/linuxapp-…` directory.

Attach a system screen recording, expected/observed behaviour, the commit and
the APK checksum. If the application crashed and is no longer running, preserve
the script's message and report that fact: logs filtered to the current PID do
not include a previously terminated process.
