# LinuxApp software-rendering example

`linux-app.owl` is a self-contained face that embeds one SDL2 Wayland window.
It draws coloured bars, changes colours on touch or keyboard input, and resizes
with its node. It declares the Arch packages `python` and `sdl2`; there are no
Python package dependencies. `demo.py` is the same embedded script, kept
separately for inspection and host tests.

For the API, client requirements and advice for coding agents, read the
[OWL LinuxApp guide](../../app/src/main/assets/widgets/README.md#linuxapp).
The launcher also ships a smaller
[foot example](../../app/src/main/assets/widgets/examples/46-linux-app.owl),
available to agents in the guest's `widgets/examples/` directory. Examples in
that directory become active only when copied into the parent widgets directory.

## Run on the phone

Copy `linux-app.owl` into `~/.config/omalauncher/widgets/` in the launcher's Linux
installation. Install its declared dependencies using the launcher's normal
`requires` mechanism. Tap the node to start; tap the window again to activate
input. Touch or a key changes the colours, Escape exits, ⌨ opens the keyboard,
and × stops the program. A stopped node offers a tap to restart.

The SDL command uses the automatic Wayland application ID supplied by LinuxApp
and a software renderer. Other clients must support the same compositor and
publish the expected ID; see the OWL guide before replacing the command.

## Build an isolated test APK

The `embedded` variant uses application ID `dev.omalauncher.embedded` and seeds
this demo face. It has its own data and Linux installation and can coexist with
the normal launcher. Debug and release builds expose LinuxApp and the optional
foot example without automatically adding this SDL demo face.

From the repository root, with JDK 25, JDK 17 for OWL, Android SDK 37 and the
pinned NDK available:

```sh
./scripts/fetch-proot.sh
./gradlew :owl:test :app:testDebugUnitTest :owl:owlJar :app:assembleEmbedded :app:lintDebug
java -jar owl/build/cli/owl.jar check examples/linux-app/linux-app.owl
python3 examples/linux-app/test_demo.py
```

`fetch-proot.sh` needs bsdtar and builds/downloads the verified Linux runtime.
An APK assembled without those libraries can compile but cannot run Linux.
The **LinuxApp test APK** GitHub Actions workflow runs the build and rejects
artifacts missing any required arm64 runtime library. Its `linux-app-test` ZIP
contains `app-embedded.apk`, a build manifest, SHA256SUMS, the test guide, a log
collector and a copy of the demo.

The host SDL test uses its dummy video driver when SDL2 is installed and reports
a skip otherwise. It exercises the demo's software renderer, input and exit,
but does not exercise the Wayland or Android integration. The Gradle tests cover
OWL, session identity, input cancellation and process lifecycle. Compilation and
host tests must be followed by the
[device acceptance checks](../../docs/LINUXAPP-TESTING.md).
