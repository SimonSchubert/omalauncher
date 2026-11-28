# Contributing

Thanks for wanting to help. This is a small project, so the most useful thing
you can do is keep a change focused and test it.

Before changing the launcher, read [AGENTS.md](AGENTS.md). It is written for
coding agents, but its rules are the project's rules: existing installs must
keep working, UI text goes in the string resources with every translation, and
OWL changes update the widget docs and the language level. [README.md](README.md)
says what the app is; [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) says where to
change common things.

## Build and test

You need JDK 25, the Android SDK (compileSdk 37), the NDK that
`scripts/fetch-proot.sh` pins, and `bsdtar`:

```sh
./scripts/fetch-proot.sh      # once per checkout; again when the build asks
./gradlew :owl:test :app:testFossDebugUnitTest :owl:owlJar
./gradlew :app:assembleFossDebug :app:lintFossDebug :app:lintPlayDebug
```

These are what CI runs (`.github/workflows/ci.yml`); run both before you call a
change done. New behavior gets a test where the code allows one.

On a phone, `owl-check <file>` compiles a widget and renders a preview, and a
debug build runs guest commands from adb:
`adb shell am start -n dev.omalauncher/.MainActivity --es exec '<cmd>'`.

## Sending a change

- Branch from `main` and keep one change per pull request.
- Match `git log`: one subject line written as a sentence about what changed
  for the user and why, with `(#N)` at the end when there is an issue. No body
  is needed.
- Fill in the pull request template. Say which device or emulator you tried,
  and tick only what you actually did.
- Stage files by name, not with `git add -A`.
- New or changed user-visible text: add it to
  `app/src/main/res/values/strings_<area>.xml` and to every `values-*`
  translation (de, es, in, ja, pt, ru, zh). `HardcodedTextTest` fails otherwise.
- OWL changes: bump `Compiler.LEVEL` when the current level has shipped, and
  record the addition in `SINCE` and
  `app/src/main/assets/widgets/docs/versions.md`.
- Changes to saved data, the rootfs setup or files the launcher writes must
  migrate existing installs (see "Updates" in `docs/ARCHITECTURE.md`).

If a change is large or changes behavior, open an issue first so the direction
can be agreed before you write it.

## Reporting bugs and ideas

Use the issue templates. For a bug, include the phone, the Android version, the
Omalauncher version and versionCode from settings › about, where the app came
from (Google Play or the APK), whether Linux is installed, and the steps to
reproduce. For a security problem, follow [SECURITY.md](SECURITY.md) instead of
opening a public issue.
