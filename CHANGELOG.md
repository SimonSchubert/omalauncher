# Changelog

The most important changes in each release. The Play Store notes for a release
are in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
Dates are when the build was uploaded to Play.

## 0.7.2 (versionCode 14) – 2026-10-14

- Text files open in the launcher's own touch-first editor, in the Omarchy theme,
  which is also Linux's `$EDITOR`: Android's selection and keyboard, line numbers,
  find, word wrap and a key row above the keyboard. Each file gets its own Recents
  card, and `git commit` waits until you close the message.
- Files is an Omarchy-style panel with its own icon, and can create a new file.
- Settings open in their own window with a Recents card, and come back as you left
  them after going home or to a terminal mid-install. The launcher's own screens
  open and close without animations.
- Terminals no longer flicker when the soft keyboard opens or closes.
- New coding agents from Omarchy's list: Oh My Pi, Ori, Muse Code and Kimi.
- Linux no longer installs lazygit, and fastfetch stays out of the drawer.
- Checking for system updates in settings records the check time again.
- The widget store's refresh button spins while it reloads.

## 0.7.1 (versionCode 13) – 2026-10-13

- Websites installed from a browser (Install app, or Add to home screen) show up
  on the home screen and in the app drawer, like other apps' shortcuts to home.
  Browsers didn't offer to install them before.
- Long-pressing an Android app in the drawer, the recent apps or on home lists its
  shortcuts, such as Maps' "Navigate home", a chat or Chrome's incognito tab. Tap one
  to open it, or its [+] to put it on the home screen.
- Apps that change their own icon, like Duolingo or X, no longer leave a blank
  tile on the home screen that won't open; it shows the new icon and opens the app.
- The now playing widget's play button works again when it seemed dead: after a
  long pause (Spotify) it opens the app so playback resumes, and for a player that
  can't play in the background (YouTube without Premium) it opens the app. Tapping
  the tile opens the app again on Android 14 and later.
- After you change the phone's language, app names, home tiles and widget dates
  follow it.
- Widgets that only animate some of the time, like a backdrop drawn while music
  plays, stop redrawing while they're still.
- The koi in the koi pond widget turn in arcs instead of flipping round.
- Keeping the theme setup suggests (Tokyo Night) no longer loses it, and its wallpaper,
  the next time the launcher starts.

## 0.7.0 (versionCode 12) – 2026-10-13

- The style lives in `~/.config/omalauncher/style/style.toml`, which you or an agent can edit
  from Linux; changes show within a second, and settings it can't read show in a banner.
  `defaults.toml` next to it lists every setting. Your earlier style carries over.
- New style settings, like Hyprland's: gaps between widgets and to the edges, border widths
  and colors, the focused border, and screen panels' gaps and borders.
- The status bar and the gesture bar can sit on the same glass as the widgets
  (style → system bars); they're bare by default.
- Widgets can show the next alarm and the phone's modes (Do Not Disturb, battery saver,
  ringer, torch), turn the torch on, share text, buzz, and draw app icons (owl 7).
- yay in a terminal no longer asks for root's password, and AUR builds no longer print
  "exec: -v: invalid option".

## 0.6.0 (versionCode 11) – 2026-10-12

- Ask for local network access (target SDK 37): Linux and widgets can reach
  LAN hosts again (Cast, printers, `http://192.168.x`, …).
- Apps from a work profile show up next to your own, marked with a briefcase,
  and dimmed while work apps are paused.
- Cloned apps carry the clone mark instead of the briefcase, from Android 15 on.
- With a work profile, the drawer splits into personal and work tabs; swipe sideways to switch.
- The work tab pauses work apps and turns them back on.
- Widget screens can raise the keyboard as they open, with `Input { focus: true }`.
- Swipe up on home to open the app drawer, like stock Android; settings → gestures
  can turn it off.
- The app list starts at the top, Linux apps first; closing the drawer clears the search.
- Widgets can follow your corner style, from square to round (owl 6).
- The widget store only takes widgets that pass values into commands safely, with `sh()` (owl 6).

## 0.5.0 (versionCode 10) – 2026-10-11

- Add your own themes, like Omarchy's, and your own picture as the background.
- Themes became the style screen, which also sets the terminal's font and text size.
- The terminal shows and types Chinese, Japanese and Korean, and characters like æ or ö.
- Tablets and wide screens get larger text and use the extra room.
- Terminal tiles are in the widget store.
- Coding agents no longer update themselves.

## 0.4.0 (versionCode 9) – 2026-10-10

- Share widgets as a QR code or a link, and open shared widgets in the launcher.
- Before adding a shared widget, see what it installs, every command it runs and
  what looks risky, or hand it to your agent to review.
- Publish your own widgets to the widget store without a GitHub account.

## 0.3.0 (versionCode 8) – 2026-10-09

- Folders on the home screen.
- Two home pages side by side on foldables.
- Linux apps and Android share the clipboard.

## 0.2.0 (versionCode 7) – 2026-10-08

- New app icon.
- GPU acceleration for Linux apps on Mali GPUs, like the Pixel 9a (Settings › linux).
- Publish your own widgets to the widget store with a long press.

## 0.1.3 (versionCode 6) – 2026-10-08

- Fix installing coding agents on some phones, like the Pixel 10.

## 0.1.2 (versionCode 5) – 2026-10-08

- Home works in landscape.
- Linux windows show up in Recents under their own name and icon.

## 0.1.1 (versionCode 4) – 2026-10-07

- The app list moved into the bar, with recently used apps above it.
- Widgets can open full screen.
- Fixes for many crashes from widgets, Linux apps and uninstalled apps.

## 0.1.0 (versionCode 1–3) – 2026-10-01

- First release: Omarchy themes and wallpapers, home screen widgets and the
  widget store, the Android app drawer, and optional Arch Linux with Linux apps,
  terminal widgets and coding agents.
