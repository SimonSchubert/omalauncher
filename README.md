# Omalauncher

An Omarchy-inspired Linux environment for Android, with an optional Arch Linux ARM system running under proot, without root. Omalauncher is the system's Wayland compositor: every Linux window opens as its own Android task, with its own card in Recents. It is a regular application opened from your existing launcher; it does not replace your Android home screen.

<a href="https://play.google.com/store/apps/details?id=dev.omadroid"><img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"></a>

![The home screen in twelve Omarchy themes, each with different widgets](art/collage.png)

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.png" width="24%" alt="Screenshot">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.png" width="24%" alt="Screenshot">
</p>

- **Themes**: all 22 Omarchy themes, with their wallpapers, applied to the app and to the Linux apps, plus the community themes from [omarchy.org/themes](https://omarchy.org/themes/), or your own in `~/.config/omarchy/themes`
- **Widgets** written in OWL, a small declarative language you can edit on the
  phone, plus a [widget store](https://github.com/SimonSchubert/omarchy-widgets)
- **Linux, optional**: pacman and the AUR, Linux apps in their own windows,
  terminal widgets, a file manager, and opt-in GPU acceleration on Mali and Adreno 6xx+ GPUs
- **Coding agents**: the bar at the bottom sends a prompt to Claude Code, Codex,
  Copilot, opencode and others, which open in a terminal
- No account, no ads, no tracking

Needs an arm64 phone with Android 10 or newer. Without Linux, the app still has
the themes, wallpapers and the widgets that don't run commands.

## Using it

Open Omalauncher from your app drawer like any other app; your existing Android home screen is untouched. The dashboard has seven sections:

- **Overview**: the Linux environment state (not installed, stopped, starting, running, stopping, failed), start and stop controls, and quick actions.
- **Linux Applications**: installed `.desktop` applications, searchable, each launchable in its own window.
- **Terminal**: a `foot` terminal in the guest, in its own Android task.
- **Widgets**: your OWL widgets, with compile errors and Linux-needed flags shown.
- **Files**: the guest filesystem, in its own task.
- **Themes**: Omarchy themes and wallpapers, applied to the app and the guest.
- **Settings**: Linux install/removal, updates, agents, packages, GPU acceleration and diagnostics.

Start Linux from **Overview › start** (or **Settings › Linux**). The first start downloads and unpacks Arch Linux ARM (~830 MB download, ~3 GB space) and installs Omarchy's packages. Once running, Linux apps, terminal widgets and coding agents work. Stopping Linux keeps it installed; removing deletes the rootfs only, leaving themes, widgets and your home screen data intact.

Linux windows keep working as before: tap is a left click, long press a right click, drag scrolls; Super+W closes a window, Super+Esc leaves one. Touch, hardware keyboard and Back navigation behave as in a desktop.

## Building

```sh
./scripts/fetch-proot.sh          # Termux's proot -> app/src/main/jniLibs (needs bsdtar)
./gradlew :app:installDebug
# optional: skip the 830 MB download on the device
adb push ArchLinuxARM-aarch64-latest.tar.gz /sdcard/Android/data/dev.omadroid/files/
```

Tests: `./gradlew :owl:test :app:testDebugUnitTest`. `./gradlew :owl:owlJar`
builds `owl/build/cli/owl.jar`, the checker the widget store's CI runs.

In debug builds you can launch guest commands with
`adb shell am start -n dev.omadroid/.MainActivity --es exec foot`.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the compositor, rendering, GPU
  acceleration, and how updates reach existing installs
- [docs/RELEASING.md](docs/RELEASING.md): Play releases and store assets
- [app/src/main/assets/widgets/README.md](app/src/main/assets/widgets/README.md):
  the widget language

## Layout

- `app/`: the Android app (Kotlin, Jetpack Compose, and the JNI bridge in `src/main/cpp`)
- `owl/`: the widget language and the `owl` command line, plain Kotlin
- `art/`: SVG masters for the app, agent and store icons
- `store/`: Play listing sources (screenshot frames, privacy policy)
- `fastlane/`: the Play listing and the upload lanes
- `scripts/`: fetching proot and theme colors, rendering icons, screenshots and widget previews

## License

MIT, see [LICENSE](LICENSE), for Omalauncher's own code and artwork. The
coding agents' logos, the symbol font and Omarchy's theme colors are not
covered; [THIRD_PARTY.md](THIRD_PARTY.md) lists them with their sources and
licenses. The app also ships proot, talloc and libandroid-shmem (GPL-2.0,
LGPL-3.0 and BSD-3-Clause, prebuilt by Termux), listed in the app under
settings › licenses.

Omalauncher is an independent project and is not affiliated with or endorsed
by Omarchy, Arch Linux, or the makers of the coding agents it installs.
