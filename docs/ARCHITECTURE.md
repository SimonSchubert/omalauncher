# Architecture

How Omalauncher is put together, for anyone working on it.

An Android home-screen launcher that runs Arch Linux ARM under proot and acts
as its Wayland compositor. The home screen (widgets, pinned apps, the bar
and its app list) is Jetpack Compose; every Linux window opens as its own Android task,
so it gets a Recents card and behaves like a separate app.

Linux is optional. Without it the launcher still has Omarchy's themes and
wallpapers, Android apps from the bar and the widgets that don't run commands.
Everything else is offered as an install from settings › linux and wherever
it appears: Linux apps, terminal widgets, coding agents, pacman and Files.
Widgets that need Linux (`Program.needsLinux`: cmd/stream sources, `Terminal`,
run/terminal/app actions, `requires`) show as placeholders in their cells until
it's installed. Removing Linux deletes `files/arch` only.

Launcher data that the guest also uses lives outside the rootfs and is bound
into it by proot (`LauncherData.kt`), so installing or removing Linux never
touches it: `files/omarchy` is `~/.local/share/omarchy` and `files/widgets` is
`~/.config/omalauncher/widgets`.

## Source layout

- `app/src/main/cpp/wlbridge.c`: JNI for unix sockets with fd passing, shm
  mapping, per-surface pixel stores, and compositing them into a window's
  `ANativeWindow`
- `wayland/`: the Wayland server in Kotlin (wl_compositor, wl_shm,
  wl_subcompositor, wl_seat, wl_output, xdg_wm_base, xdg-decoration) and
  `WindowRenderer`, which draws one window into its activity's SurfaceView
  (or, for a widget's `Terminal`, into a TextureView in the tile)
- `linux/`: proot, the rootfs setup and its update steps, pacman, coding
  agents, and the bridges to Android (audio, URLs, /proc)
- `ui/`: the Compose home screen, the bar and its app list, settings and Files
- `widgets/`: the widget engine; the language itself is in the `owl` module
- `theme/`: Omarchy themes, applied to the launcher and the guest
- `upgrade/`: how installs follow app updates (see below)

## Rendering

A client's commit copies only the damaged rectangles of its shm buffer into
the surface's pixel store, outside the server lock, and hands the buffer back
right away. Each window's `WindowRenderer` runs on its own thread: at vsync it
snapshots the surface tree, redraws only the dirty region of the SurfaceView
(everything, if a surface moved or appeared), posts, and then sends the frame
callbacks. Hidden windows get no frame callbacks, so their clients stop
drawing.

## Glass

Home tiles, the prompt bar and the rails are glass over the wallpaper.
`widgets.opacity` (style.toml, default 0.88) is how much of that glass is
opaque. `widgets.blur` (default 0, up to 64 dp) blurs the wallpaper behind
them: one small blurred copy of the wallpaper (`ui/Frosted.kt`, an eighth of
the wallpaper's size), and each surface draws the part behind it, including
while pages swipe. Widget text is not blurred. With no blur, a live wallpaper,
or before the copy is ready, the surface is plain glass.

A long-press on a widget sets that widget's own opacity, in 5% steps, stored
with its layout (`glass` in the layout JSON). Tapping the percentage clears
it, and the widget uses `widgets.opacity` again.

`terminal.opacity` (default 1) is foot's background alpha. It is written into
foot's `[colors-dark]` and `[colors-light]`, and sent to terminals already
open (OSC 11, the same path as a theme change). The terminal window keeps
that alpha, and the view behind it is clear, so foot's alpha is the only
layer. Foot itself draws on the CPU.

## GPU acceleration

Opt-in, in settings › linux, on Mali GPUs (v10+: Pixel 7 and newer, recent
MediaTek). Turning it on installs Mesa and the Vulkan loader with pacman and
the pinned PanVK-kbase driver (a Mesa Vulkan driver for Android's
`/dev/mali0`, from zenithblue-oss/panvk-kbase-android, checked against its
sha256) into `/usr/local`, where pacman never touches it. OpenGL is Arch's
stock Mesa on top of it, via zink. Frames still arrive as wl_shm buffers
(`MESA_VK_WSI_DEBUG=sw`), so the compositor is the same either way.

`linux/Gpu.kt` only switches guest apps to the GPU (`Proot.graphicsEnv`)
after a check passes (vulkaninfo finds PanVK, eglinfo gets zink), and checks
again after every system update; if that fails, apps render in software
(llvmpipe) and settings offers a retry. One app can opt out with
`GALLIUM_DRIVER=llvmpipe app`. Terminal apps (foot) draw on the CPU and
don't change.

## Updates

The app writes into the rootfs and keeps state of its own, so an update must
reach existing installs without breaking them. There are four mechanisms:

- **Rootfs steps** (`linux/Bootstrap.kt`): an ordered list of idempotent steps
  (`/etc` configuration, packages, the keymap, cliamp).
  Each one's id is recorded once it succeeds (`files/rootfs-steps.json`). To
  change what a step does on existing installs, give it a new id, for example
  `packages-1` → `packages-2` after changing the package list. A fresh install
  runs every step in the background, with progress in settings › linux and
  the notification. After an update, new offline steps run before Linux
  starts, and steps that need the network run in the background. Their progress and failures show in settings, and they retry on
  the next start.
- **Launcher-provided files** (`upgrade/ManagedFiles.kt`): shims, desktop
  entries, default configs, default widgets. Every file written is recorded
  with its hash: the launcher's own (default widgets) in
  `files/launcher-managed.json`, the guest's in `files/managed.json`, which
  only syncs while Linux is installed and is forgotten with it.
  - `Owned` files (plumbing) always match the APK.
  - `Seeded` files are defaults the user may edit. They are updated only while
    unchanged since we wrote them, and never brought back once deleted.
  - Files dropped from the APK are deleted if they're unchanged.
  - The sync runs on every start and only hashes files when nothing changed.
- **Saved data** (`upgrade/Versioned.kt`): preferences and JSON files are
  stored as `{"v": N, "data": …}`. A format change bumps `N` and adds a
  migration step to the reader. Entries are parsed one at a time, so a bad
  one is skipped instead of losing the rest.
- **Widgets**: `Compiler.LEVEL` in the owl module is the language version.
  Widgets that need a newer one declare `meta { owl: N }`, and store entries
  carry it. An older launcher then shows "needs a newer launcher" instead of
  failing. Bump the level with every addition a widget could depend on. The
  store's `index.json` format number only changes for incompatible changes.

**System updates** (`linux/SystemUpdates.kt`) are the user's own: pacman
packages and the tools mise installed (the coding agents). The launcher
checks at most every 6 hours while it's on screen: pacman against a private
copy of the databases in /tmp, so the real ones and pacman's lock are
untouched, plus `mise outdated`. When updates are pending, a dot shows on the
app list's settings gear. "update all" in settings runs `pacman -Syyu` and then
`mise upgrade` as one job. Nothing updates automatically.

Pinned versions: `ThemeStore.OMARCHY_REF` (the Omarchy commit) and
`CLIAMP_VERSION` in `Bootstrap.kt`. Changing the first makes every install
download the new themes on its next start (`ThemeStore.ensureRelease`, with or
without Linux); rerun `scripts/fetch-theme-colors.sh` with it, since first run
fetches only the picked theme's colors and first background by the names that
script bundles. Changing the second updates Linux installs through the steps
above.

Guest processes die with the app (`--kill-on-exit`), so the Wayland protocol,
the socket and the guest environment carry no state across an update.
