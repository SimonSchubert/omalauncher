# Computer link: the Omarchy plugin and the phone

Status: first slice built on the `phone-link` branch (2026-11-22), with the plugin's side on the
plugin repo's `phone-link` branch. Tested on the emulator from the Mac over `adb forward`. Not yet
tested on a real phone and an Omarchy desktop on the same Wi-Fi.

Goal: the Omalauncher plugin on an Omarchy desktop (`omarchy-widgets`, in
SimonSchubert/omalauncher-omarchy-plugin) changes things on the phone. To start with, it lists, reads,
adds and removes the phone's widgets and switches its theme.

## Decisions

- **Only with Linux.** sshd runs in the guest. The launcher gets no network server of its own: no
  listening port in the APK and no hand-written authentication. While no computer is linked, nothing
  runs or listens.
- **sshd runs as a guest user, `omalink`, not as the fake root.** As root, privilege separation's
  `chroot("/var/empty")` hits a proot assertion (`path.c compare_paths2`) and `--kill-on-exit` takes
  everything down. As a non-root user, sshd doesn't chroot. Under proot that user sees every file as
  its own, so `StrictModes` is off: it would check nothing.
- **Keys are let in only for a forced command.** Each line in `authorized_keys` is
  `restrict,command="/usr/local/bin/omalauncher-link <id>"`, so a linked key gets no shell, no TTY
  and no forwarding. `omalauncher-link` (assets/omalauncher-link.sh) takes only these words:
  `hello`, `widgets`, `get`, `put`, `remove`, `themes` and `theme`. It passes each request to the
  launcher through the `omalauncher` inbox as `omalauncher link <verb> <id> …`. The launcher
  (`ComputerLink.handle`) checks that the id is still linked and validates names (only
  `name.owl`: no paths, no dot files), size (1 MB) and UTF-8. Then it writes the file whole.
- **Pairing: the desktop shows a QR code and the phone scans it.** The plugin can already draw QR
  codes, and the phone already opens `https://simonschubert.github.io/omarchy-widgets/w#…` App Links
  without a camera permission. The invite is `#link1.<base64url of {"n": name, "k": ssh-ed25519 key,
  "s": secret}>`. The phone shows who is asking, what it will be able to do, and a six-digit code
  derived from the key, which the desktop also prints. A link someone sent by chat looks the same,
  so the user is told to link only if the codes match.
- **The secret proves the phone to the desktop.** After it's linked, the desktop finds the phone
  (`_omalauncher._tcp` over mDNS, TXT `id=`), takes the host key the phone offers with
  `ssh-keyscan`, and connects with that key pinned. It then asks `hello`. The answer's `proof` is
  HMAC-SHA256(secret, host key), computed by `openssl dgst -hmac` on the desktop. Someone in between
  would have to present their own host key and could not produce the proof for it, because SSH
  authentication can't be relayed. The proof is given once, and the secret is deleted after that
  first `hello`.
- **Finding the phone again:** mDNS with the phone's id (it stays the same for as long as the
  launcher's data does) and a `HostKeyAlias`, so a new IP address doesn't break `known_hosts`.
  `OMALAUNCHER_PHONE=host:port` skips discovery, for Tailscale or the emulator.
- **What may be done is fixed for now:** widgets and theme. Before verbs that reach further are
  added (settings, wallpaper, notifications), the phone should show per-computer grants.

## Files

- `linux/ComputerLink.kt`: saved computers (`files/link/computers.json`, `Versioned` v1), the
  invite, proof and code, `authorized_keys`, supervising sshd, the NSD announcement, and the requests.
- `assets/omalauncher-link.sh`: the forced command, a `ManagedFiles` `Owned` file in
  `/usr/local/bin`.
- `assets/omalauncher.sh`: takes `link` (not in its usage, not in `COMMANDS`).
- `ui/settings/LinkComputer.kt`: the screen for a scanned invite (it comes through
  `LauncherState.shared`, like a widget link), and the linked-computers card on the Linux settings page.
- In the guest: user `omalink` (made the first time sshd starts), `/var/lib/omalauncher-link/` with
  `host_key`, `sshd_config` and `authorized_keys`. Port 8122 (Termux's sshd takes 8022).

## Open

- Real network test: a Pixel and an Omarchy laptop on the same Wi-Fi, with avahi discovery, NSD on
  Android 17 and `ACCESS_LOCAL_NETWORK`. Does an incoming connection need it?
- The plugin UI: "link a phone" and "send to phone" in the panel's menus, and following theme
  changes through `~/.config/omarchy/hooks/theme-set`.
- The `/w` fallback page in omarchy-widgets shows a `link1.` invite as a broken widget link.
- Play: the launcher now runs a network service when the user asks for it. The data safety form
  and the privacy policy should mention it.
- More verbs: themes the phone doesn't have (send the theme folder), wallpaper, and the widgets'
  layout on the pages.
