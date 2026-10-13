# Android tools and storage in Linux

The **Linux environment** section in setup and settings › linux offers Android
commands (`ando`) and storage binds. During setup this section comes before the
separate **Coding agents** choices. Neither integration requires an agent.
Both are optional and initially off/empty, including when
upgrading an existing installation. No extra download is needed for ando.

## Commands

Enable **Android commands (ando)** and open a new terminal:

```sh
ando id                         # the real Android app uid
ando getprop ro.build.version.release
ando /system/bin/sh -c 'echo "$ANDROID_ROOT"'
printf 'hello\n' | ando cat
ando -D ~/Documents pwd         # Android path of this guest directory
ando -e NAME=value command arg
ando --preserve-env=TERM,LANG command
ando -s                         # Android shell
```

`ando [options] command [arguments...]` runs a regular Android process with
Omalauncher's permissions. Arguments, stdin, stdout, stderr, cwd, and exit status
are preserved. The guest's fake root identity does not grant Android root.
Android itself restricts many `am`/`cmd` operations to shell/root callers.
`-r` or `-u USER` invokes Android's `su`, if installed and authorized by the user.

`-E` forwards the guest environment except `PATH`, `LD_PRELOAD`, and
`LD_LIBRARY_PATH`. `--preserve-env=LIST` selects specific variables; repeatable
`-e NAME=value` overrides them. The default is the Android app environment plus
the caller's `TERM`. Options stop at the command; use `--` to end options explicitly.

The original terminal descriptors are passed, so pipes and `isatty` work.
INT, TERM, HUP, and QUIT are forwarded to the Android command's process group.
Interactive shells have no controlling-terminal job control. Disconnecting the
client, disabling ando, removing Linux, or app-process death ends active command
groups. A command deliberately detaching its session, or a root process created
by a separate `su` daemon, is outside that process-group lifecycle.

Disabling ando closes its listener and terminates its commands. Enabling it
requires a new Linux process because existing PRoot processes retain their bind
tables. Failures use status 125 (protocol/usage), 126 (could not execute), or
127 (missing command/broker); otherwise the child's exit status is returned.

## Storage binds

Choose **manage storage binds**, enter an Android source directory and an empty
absolute Linux destination, and add the bind. `~/` expands to `/root/`.
For example, map Android's Download directory to `/root/Downloads`, or an
app-owned external directory to `/mnt/android-app`. The app-storage suggestion
needs no broad storage permission. Shared-storage folders require Android's
storage access grant, available through the settings link. Android still limits
access to other apps' private directories. SAF document URIs are not filesystem
paths and cannot be passed to PRoot as directory binds.

All configured binds are writable (PRoot provides no enforceable read-only
binds). Files are accessed in place, without copying. Removing a bind changes
new terminals/apps; close existing sessions to discard their old mappings.
Removing Linux deletes only the rootfs, not external source directories. Android
may delete app-owned external storage when Omalauncher itself is uninstalled.

Missing/inaccessible sources, malformed configuration, overlapping destinations,
system/launcher destinations, symlink mountpoints, and nonempty destinations
produce errors instead of silently writing into an unbound rootfs directory or
hiding existing files. Settings remain available when setup fails. Correct or
remove the bind, then retry setup or reopen the terminal. Binds are validated at
every process launch, including install and update commands.

## Implementation

`AndroidIntegration` stores versioned bind configuration outside the rootfs.
`StorageBinds` validates it and creates only empty guest mountpoints. `Proot`
uses the same mappings for command arguments and Android-side file translation.
The guest's Files app therefore sees bound directories too.

`ando.c` is the MIT-licensed TAWC client, pinned and attributed in
`THIRD_PARTY.md`. `ando_broker.c` is Omalauncher's supervisor, run as a separate,
untraced Android process rather than forking the JVM. The static NDK executable
is built by CMake and packaged as `libando.so`; managed provisioning installs it
at `/usr/local/bin/ando` on both fresh and existing Linux installations.

The private filesystem socket is bound at `/run/omalauncher-ando/ando.sock` only
when enabled. The broker also checks `SO_PEERCRED` against its real uid. It accepts
at most 16 concurrent requests, bounds headers/arguments/environment, and imposes
a five-second handshake deadline. Each request receives stdin/stdout/stderr and
cwd via SCM_RIGHTS, forks its own command process group, and retains its child's
pid with `waitid(WNOWAIT)` until cleanup. Closing the app-owned lifetime pipe
stops the supervisor and workers. PRoot and ando are not security sandboxes for
untrusted code running under the same Android app uid.

## Verification

```sh
./gradlew :owl:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python scripts/fetch-test-proot.py
./gradlew -Pomalauncher.testAbi=x86_64 :app:connectedDebugAndroidTest
python scripts/check-android-test-results.py
```

The instrumentation command uses `dev.omalauncher.integrationtest` on an emulator.
Instrumentation uses disposable cache directories and does not install a distro,
change storage grants, or edit user settings. It covers the real packaged broker
and client, streams/argv/env/cwd/uid/status, concurrent and malformed requests,
shutdown/disconnect/restart, managed-file repair, and bind validation. The PRoot
fixture also runs ando inside PRoot with an external directory bind and verifies
data survives deleting the guest rootfs. The small test runtime is pinned by SHA-256.
The ABI override is for testing; normal builds target arm64. Fetch PRoot with the existing
`scripts/fetch-proot.sh` before building a complete Linux-capable APK.

Debug builds install as **omalauncher_debug** (`dev.omalauncher.debug`), alongside
the release app with separate settings and Linux data. Emulator instrumentation
uses its own `dev.omalauncher.integrationtest` package.
