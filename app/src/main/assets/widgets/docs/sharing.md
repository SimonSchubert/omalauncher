# Sharing

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

The **widget store** (long-press empty space on the home screen) installs reviewed widgets from
https://github.com/SimonSchubert/omarchy-widgets. To publish one you made, long-press it and tap
**publish**: the default agent opens a pull request there with the GitHub CLI (or an issue, if it
can't). By hand, open a pull request that adds it as `widgets/<github-username>/<name>/widget.owl`
(a face as `faces/<github-username>/<name>/face.owl`). A face can have several pages, installed side
by side: the others go beside it as `page-2.owl`, `page-3.owl`, ..., as the store's README says.
The store needs:

- `meta` with a `description` (10-200 characters), a `version` and a `license`, and ideally a `category`:
  `news`, `dev`, `productivity`, `time`, `world`, `sport`, `finance`, `media`, `games`, `pets`, `fun`, `home` or `system`
- every package in `requires`
- values in commands passed through `sh()` (see Values in commands in docs/declarations.md)
- nothing personal: no tokens, personal paths or hard-coded location
- a higher `version` for each update
- ideally a `preview.png` next to it: the one `owl-check` points to will do

The repo's CI checks the rules and summarizes every command for the reviewer. Store installs go on
the page you're on; `page:` and `order:` in the file only apply to widgets you add yourself.

To pass a widget on without the store, share it as a link or QR code: the whole file is in it,
after the `#` of `https://simonschubert.github.io/omarchy-widgets/w#owl1.<data>` (raw DEFLATE, then
unpadded base64url), so it never reaches a server. One code holds about 2.9 KB of that, which most
widgets under 6 KB fit. Scanning it opens the launcher, which lists every command the widget runs and
flags risky ones before it adds it as `shared-<name>.owl`: nobody reviewed it. On Omarchy, the Widgets
app's **Share…** shows the code, and `omarchy-widgets share` / `omarchy-widgets import` make and read
links.
