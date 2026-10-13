# Third-party material

The MIT license in [LICENSE](LICENSE) covers Omalauncher's own code and artwork.
It does not cover the files below, which stay under their owners' terms.

## Coding agent logos

`art/icons/agent-*.svg`, and the PNGs rendered from them in
`app/src/main/assets/icons/agent-*.png` and `app/src/main/res/drawable-nodpi/agent_*.png`.

The marks are trademarks of their owners. They are used only to identify each
agent in the launcher, which is not affiliated with or endorsed by any of them.

| File | Mark | Owner | Source of the drawing |
|---|---|---|---|
| `agent-claude` | Claude | Anthropic | [simple-icons](https://github.com/simple-icons/simple-icons) (CC0-1.0) |
| `agent-codex` | OpenAI | OpenAI | simple-icons (CC0-1.0) |
| `agent-copilot` | GitHub Copilot | GitHub | simple-icons (CC0-1.0) |
| `agent-cursor-agent` | Cursor | Anysphere | simple-icons (CC0-1.0) |
| `agent-agy` | Antigravity | Google | [@lobehub/icons](https://github.com/lobehub/lobe-icons) (MIT) |
| `agent-grok` | Grok | xAI | @lobehub/icons (MIT) |
| `agent-opencode` | opencode | opencode | @lobehub/icons (MIT) |
| `agent-pi` | Pi | pi.dev | drawn for Omalauncher, a π in the colors of Pi's mark |
| `agent-crush` | Crush | Charm | redrawn from Charm's logo |

## Fonts

`app/src/main/res/font/nerd_symbols.ttf`: symbols from
[Nerd Fonts](https://www.nerdfonts.com) (MIT), [Weather Icons](https://erikflowers.github.io/weather-icons/)
(SIL OFL 1.1) and [Material Design Icons](https://pictogrammers.com/library/mdi/) (Apache-2.0).

## Omarchy themes

`app/src/main/assets/themes/*.toml` and `app/src/main/assets/theme-backgrounds.txt`
come from [Omarchy](https://github.com/omacom/omarchy), MIT, Copyright (c) David Heinemeier Hansson.
Omalauncher is not affiliated with Omarchy.

## Bundled at build time

`app/src/main/cpp/ando.c` is adapted from TAWC's ando client at commit
`e463fee718d04b2c7068b655963e1d3a20b5adb8` (MIT, Copyright (c) 2026 Sophie Winter).
The full notice is in `app/src/main/cpp/ANDO-LICENSE` and settings › licenses.
Omalauncher changes the socket location and diagnostics, fixes empty shell
arguments and closed standard descriptors, and supplies its own broker.

`scripts/fetch-proot.sh` downloads pinned versions of Termux's builds of PRoot (GPL-2.0),
talloc (LGPL-3.0) and libandroid-shmem (BSD-3-Clause); they are not in this
repository. The app lists every component it ships, with its license, under
settings › licenses (`ui/LicensesScreen.kt`, texts in `app/src/main/assets/licenses/`).
