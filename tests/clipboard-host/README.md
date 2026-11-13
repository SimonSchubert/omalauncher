# Clipboard host regression tests

These tests compile and execute the repository's actual `Clipboard.kt` and
`Wire.kt`. Android services/file descriptors and the surrounding Wayland
transport use test doubles; the bounded-read helper uses a host equivalent.
No running launcher, terminal, or real clipboard is touched.

Run from the repository root:

```sh
bash tests/clipboard-host/run.sh
```

To compare a saved source version against the same tests:

```sh
bash tests/clipboard-host/run.sh /path/to/Clipboard.kt.before build/clipboard-baseline
```

Prerequisites: Java 17+ and Kotlin 2.2.21+ on PATH. The tested versions are
Temurin 21 and Kotlin 2.2.21. `CLIPBOARD_TEST_JAVA` and
`CLIPBOARD_TEST_KOTLINC` can override the executable paths. For isolated local
toolchains, the runner also accepts `.test-tools/*-jre/bin/java` and
`.test-tools/kotlinc/bin/kotlinc`. It never downloads or installs dependencies.
The output JAR goes under `build/clipboard-host` by default.

The baseline comparison selects an alternate fake implementing the original
`SystemClipboard` interface; it does not modify the production source. The
test cases and simulated clipboard changes are the same in both runs.

The tests deliberately change the fake Android clipboard without invoking its
listener, retain old Wayland offers, and hold Linux mirror pipe writes until a
newer Android or Linux copy exists. They also check ordinary Linux copies,
Unicode and multiline text, nontext MIME data, cleared/unavailable Android
clipboard, cancelled/destroyed sources, and copies exceeding Android's mirror
limit. Additional cases exercise one-time insertions, Android clipboard
ownership metadata, image file reuse, and a new image arriving during paste.

These are source-level JVM regression tests. They do not validate Android
Binder, focus permissions, touch UI, native descriptor transfer, APK packaging,
or behavior of the installed application on the phone.
