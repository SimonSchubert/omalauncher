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
| `agent-omp` | Oh My Pi | can1357 | [oh-my-pi](https://github.com/can1357/oh-my-pi)'s icon (MIT) |
| `agent-ori` | OpenRouter | OpenRouter | @lobehub/icons (MIT) |
| `agent-muse` | Meta | Meta | @lobehub/icons (MIT) |
| `agent-kimi` | Kimi | Moonshot AI | @lobehub/icons (MIT) |

## Fonts

`app/src/main/res/font/nerd_symbols.ttf`: symbols from
[Nerd Fonts](https://www.nerdfonts.com) (MIT), [Weather Icons](https://erikflowers.github.io/weather-icons/)
(SIL OFL 1.1) and [Material Design Icons](https://pictogrammers.com/library/mdi/) (Apache-2.0).

## Omarchy themes

`app/src/main/assets/themes/*.toml` and `app/src/main/assets/theme-backgrounds.txt`
come from [Omarchy](https://github.com/omacom/omarchy), MIT, Copyright (c) David Heinemeier Hansson.
Omalauncher is not affiliated with Omarchy.

## Mesa

`app/src/main/assets/mesa-zink/mesa-zink.tar.xz`: [Mesa](https://mesa3d.org) 26.2.4 with
`scripts/mesa/0001-egl-wayland-keep-zink-on-shm.patch`, built by `scripts/mesa/build-aarch64.sh`.
Mesa is mostly MIT, with other files under the permissive licenses in its `licenses/` directory.
The archive also carries [wayland-protocols](https://gitlab.freedesktop.org/wayland/wayland-protocols) (MIT).
It is installed into the Linux guest when GPU acceleration is turned on for an Adreno GPU.

## Bundled at build time

`scripts/fetch-proot.sh` downloads pinned versions of Termux's builds of PRoot (GPL-2.0),
talloc (LGPL-3.0) and libandroid-shmem (BSD-3-Clause); they are not in this
repository. The app lists every component it ships, with its license, under
settings › licenses (`ui/LicensesScreen.kt`, texts in `app/src/main/assets/licenses/`).

## Linux image verification

Bouncy Castle Java 1.86 (`bcpg-jdk18on`, `bcprov-jdk18on`, `bcutil-jdk18on`)
is used for OpenPGP verification under its MIT license. Copyright (c)
2000-2026 The Legion of the Bouncy Castle Inc.
The license is bundled in `app/src/main/assets/licenses/bouncycastle.txt`.
Source: https://github.com/bcgit/bc-java

`app/src/main/assets/security/archlinuxarm.asc` is the public Arch Linux ARM
build-system signing key. Its fingerprint is independently published at
https://archlinuxarm.org/about/package-signing and is checked by the verifier.
The armored public key was retrieved from keyserver.ubuntu.com using that
complete fingerprint; the keyserver is not the trust authority.
