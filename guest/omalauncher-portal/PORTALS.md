# Guest portals — unfinished implementation draft

This draft depends on the shared session bus in PR #210. It includes that
commit while the bus change is unmerged; it does not replace the bus proposal.
Implementation is unfinished and the PR must remain a draft. Host checks do
not establish production readiness; Android lifecycle validation remains outstanding.

The launcher owns `org.freedesktop.portal.Desktop` directly. It is not an
`org.freedesktop.impl.portal.*` backend: the normal desktop frontend's
`/proc/<pid>/root` identification does not work inside this PRoot guest.

## Implemented paths

- **Settings:** appearance Read/ReadOne/ReadAll and live SettingChanged; light,
  dark and no-preference values, accent colour, higher contrast and reduced
  motion. Higher contrast follows Android's public UI contrast preference on
  API 34+; older releases report no contrast preference. Reduced motion follows
  the public animator-duration-scale setting: zero means disabled animations.
  Missing/invalid preferences report no preference, and two-line appearance
  snapshots from older launchers remain supported.
- **PowerProfileMonitor v1:** Android Battery Saver as `power-saver-enabled`,
  with live `PropertiesChanged` and invalidation when the state is unavailable.
- **NetworkMonitor v2:** Android default-network availability, metering and
  connectivity (local, limited, captive portal or validated internet), with
  live `changed` signals. Missing/unobserved status returns an error, not a
  fabricated offline or unmetered result. Version 3 `GetStatus`/`CanReach`
  are not advertised; this does not claim endpoint reachability probing.
  The GLib portal frontend supports the version-2 getters. Guest processes
  opt in using `GIO_USE_PORTALS=1` as well as GTK's portal setting; GTK's flag
  alone does not select GLib's network/power portal backends.
- **OpenURI / Email:** Android activity handoff. Files and attachments cross a
  same-UID Unix socket as actual SCM_RIGHTS descriptors, not host path strings.
  OpenFile opens a directory FD itself; OpenDirectory opens its containing
  directory. File shares are read-only: a writable hint is not document-portal
  export authority. Email combines the legacy address with the addresses array;
  To/CC/BCC arrays are bounded by the whole request, not silently cut at 64.
  Known Email options are type-checked before creating a request; a malformed
  recipient or attachment list returns `InvalidArgument`, never a successful
  compose with those fields silently omitted. `attachment_fds` contains message
  FD indices, not paths or plain integers. Empty options/attachment lists are
  supported; the bridge allows up to eight regular-file attachment capabilities.
  URI schemes are normalized for Android's case-sensitive intent matching;
  addresses, paths, query strings and fragments retain their original spelling.
  Email advertises version 3, not version 4 activation-token support.
  Rejected batches release partial FD copies and their request registration.
- **Inhibit:** suspend/idle leases, released on request Close, caller loss and
  service shutdown. Desktop logout/user-switch inhibition is not supported.
- **Notification:** portal notifications with application-owned IDs, typed
  action targets, replacement/withdrawal and Android notification channels.
  Existing `org.freedesktop.Notifications` FIFO routing remains available.
- **FileChooser:** existing Files picker routing remains. Requests are bounded,
  caller-owned and interruptible; terminal replies and Close are serialized on
  the GLib main context. Cancelling the request releases its waiter/FIFO; this
  does not assert that Android's already-open Files activity has been dismissed.
- **Print:** GTK/CUPS printer selection and a caller-owned, single-use preparation
  token. Submission uses the selected printer and actual document descriptor.
  The launcher's `Android` queue converts the document with CUPS-PDF and sends
  the completed PDF descriptor to Android print services.

This is an expansion of the interfaces above, not a claim to implement every
interface in xdg-desktop-portal.

Every accepted transport peer belongs to its listener epoch, including peers
still sending their first header or body. Stop shuts down those sockets;
late accepts are rejected and closed. UID authorization precedes admission to
the 64-peer budget. An old receive completion cannot release a restarted
listener's capacity, and delayed cleanup affects only old requests/document
grants, not the replacement session.

## Printing semantics

The guest queue represents **Android printing**, not a discovered physical
Android printer. Android still presents its print-service/printer dialog.
`PreparePrint` is real GTK printer/settings/page setup, not Android-only page
setup. A successful portal submission acknowledges CUPS acceptance; the CUPS
job has its own subsequent lifecycle. The backend waits for the Android
PrintJob terminal outcome: success, cancellation or failure. Conversion to PDF
alone is not success. CUPS-PDF ignores postprocessing exit codes, so the native
wrapper and private result pipe are required.

The daemon listens on an app-private Unix socket only, with separate config,
state, spool and output directories. It does not replace the system CUPS
configuration or open a network printer service. The backend is published with
owner-only mode 0700; the ordinary filter is executable. Saved printer state
and PPD defaults are preserved while owned filter rules are updated.

For `PreparePrint`, the caller formats its document using the returned settings
before `Print`; GTK's portal caller uses its file backend for this. The GTK
regression renders two copies of a selected page with an actual PrintOperation,
then submits that formatted PDF with the production helper and checks that
copies/ranges are not applied a second time. Ordinary CUPS clients such as `lp`
instead supply their document and CUPS options to the managed filter.

Copies and page selection must be represented in the completed PDF, rather
than silently depending on a physical PostScript printer or Android copy
settings. The managed PDF filter passes the CUPS copy-count argument as an explicit
option, applies copies/page selection once, and stages a plain PDF. A
conversion-only Ghostscript `ps2write` stage produces ASCII-safe PostScript;
only a completely successful conversion is fed to CUPS-PDF, whose input reader
cannot accept binary PDF. The queue supports 1–999 copies. The backend also
rejects empty/non-PostScript streams before invoking CUPS-PDF, rather than
allowing a failed filter to become a blank successful print. Both intermediate
files are private and cleaned; completed PDFs remain available after a failed
Android handoff.

## Reproducible host checks

No system installation, phone install, reset or privilege change is needed.
Use installed toolchains or isolated build dependencies.

```sh
ASAN_OPTIONS=detect_leaks=0 \
PORTAL_TEST_CFLAGS='-fsanitize=address,undefined -fno-omit-frame-pointer' \
TMPDIR=/tmp python3 scripts/test-guest-portals.py -f

JAVA_HOME=/path/to/jdk TMPDIR=/tmp python3 scripts/test-portal-fd.py

PORTAL_TEST_PROOT=/path/to/proot \
CUPS_PDF_TEST_SOURCE=/path/to/cups-pdf-3.0.3/src/cups-pdf.c \
TMPDIR=/tmp python3 scripts/test-cups-pdf-bridge.py -f

PORTAL_TEST_PROOT=/path/to/proot \
PORTAL_TEST_CUPSD=/path/to/isolated/cups/scheduler/cupsd \
CUPS_PDF_TEST_SOURCE=/path/to/cups-pdf-3.0.3/src/cups-pdf.c \
TMPDIR=/tmp python3 scripts/test-private-cups.py -f

./gradlew :owl:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The native protocol runner creates an activation-free private D-Bus. Android,
Files FIFO and GTK-selection endpoints are explicit test doubles; GTK settings
serialization itself is exercised against real GTK. The CUPS bridge uses real
CUPS-PDF, Ghostscript and descriptors, with a simulated Android endpoint. The
private-queue runner additionally starts real isolated cupsd and its filters;
`cups-exec` must be built adjacent to that scheduler. JNI FD-rights checks run
on host Linux, not Android. Sanitizer leak detection is disabled explicitly.

To exercise the shipped ARM64 portal rather than only a fresh host build, set
`PORTAL_TEST_PORTAL_COMMAND` to an explicit command such as
`qemu-aarch64 -L /path/to/sysroot /path/to/shipped/omalauncher-portal` and run
the same protocol suite. This retains the private bus and explicit endpoint
doubles; it is not Android activity coverage. The startup watch-order probes
use a test-only interposer with the freshly built host service, and the signal
capture regressions use host subprocess pipes, even with an ARM64 command set.

Build the guest binaries with `scripts/guest-portal/build.sh` inside an aarch64
Arch environment. Package all five guest-portal assets; a Kotlin-only successful
APK build does not establish that the guest executables are present/current.

## Device validation still required

Host tests cannot establish Android activity-launch permissions, document URI
access/grant/revocation, notification permissions/actions, wake/idle behaviour,
PrintJob lifecycle or physical printer output. Power/network callback ordering,
registration across launcher start/stop and live contrast/animation preference
changes also require Android validation; pure snapshot/lifecycle policy tests
and native D-Bus tests do not establish those callbacks. Verify on an authorized,
isolated test device before treating the feature as production-ready. A debug
APK is not an authorized upgrade of an existing signed installation.

Include rotation during PDF preparation and while the print dialog is open,
an unavailable print service, and closing a request while its Android chooser
is visible. Print startup now handles descriptor failures and a null PrintJob;
the transparent print host handles known configuration changes, including
grammatical gender and resource overlays, to retain its adapter. Unexpected
host destruction before adapter handoff fails the request instead of leaving
it attached to an obsolete Activity. Once the adapter has finished, host
replacement continues observing the existing job; it does not submit it again.
Pure host-policy and transport-ownership tests, including a real JVM TCP
partial-header read/close, do not establish Android Activity, LocalSocket or
SCM_RIGHTS lifecycle behavior. Those repairs are not yet device-validated. In particular, dismissal
of an already-visible chooser on request Close remains unverified.

Implementation and host-verification work were AI-assisted.
