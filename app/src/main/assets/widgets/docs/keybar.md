# Terminal key bar

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

While you type in a terminal, on home or in a Linux app's window, a key bar sits over the soft keyboard: esc,
tab, ctrl, alt, the arrows and the rest a phone keyboard lacks. It's the file `keybar.owl`, a `keybar "…" { }` file, and
it's yours to change: different keys, more rows, a key that types a command. `.stock-keybar.owl` is the bar as this
launcher ships it, which it keeps that way; it shows while `keybar.owl` is gone or doesn't compile (home's banner says
so), and comparing the two shows what changed in a newer launcher. The keys only show over a docked keyboard: a
hardware keyboard has these keys already.

```owl
keybar "my keys" {
  meta { owl: 12 }
  source kb = android.modifiers
  component K(label, send) {
    Box { weight: 1; height: 40; padding: 3
      Box { fill: true; align: center; radius: 8 * corners; background: surface; repeats: send == "up" || send == "down"
        onPress: { if repeat == 0 { haptic() }; pressKey(send) }
        Text { text: label; font: mono; align: center } } }
  }
  Row {
    gap: 0
    padding: [6, 0, 6, 6]
    K { label: "esc"; send: "esc" }
    K { label: "^C"; send: "ctrl+c" }
    Box { weight: 1; height: 40; padding: 3
      Box { fill: true; align: center; radius: 8 * corners; background: get(kb ?? {}, "ctrl", "off") == "off" ? surface : accent
        onTap: latch("ctrl"); onLongPress: latch("ctrl", "locked")
        Text { text: "ctrl"; font: mono; align: center } } }
    K { label: "↑"; send: "up" }
    K { label: "↓"; send: "down" }
    Box { weight: 1; height: 40; padding: 3
      Box { fill: true; align: center; radius: 8 * corners; background: surface; onPress: typeText("git status\n")
        Text { text: "gs"; font: mono; align: center } } }
  }
}
```

The file is the whole bar, as wide as the screen and as tall as its nodes; the launcher keeps it over the keyboard and
moves a terminal tile up by its height. It takes no properties or handlers of its own, and has no `screen { }`. Its
keys never take focus from a touch, so the keyboard stays up; a gamepad's D-pad reaches them, and its A presses one as
it goes down, again as it repeats, and lets it go as it lifts. A key acts as the finger goes down with `onPress`, not when it lifts
as `onTap` does, and `repeats: true` keeps an arrow going while it's held. `pressKey(...)`, `typeText(...)` and
`latch(...)` reach the Linux window being typed in, and work in any owl 12 file, as do `onPress`, `onRelease` and
`android.modifiers`.
`latch` is how the stock bar's ctrl and alt work: a tap holds one for the next key, from the bar or the keyboard, a
long press until it's tapped again, and `android.modifiers` tells the key how to look meanwhile. Closing the keyboard
lets every latch go. `owl-check keybar.owl` draws the bar at the bottom of a phone's screen.

The terminal key bar has its own gaps and border width in settings › style (`keybar.gaps-out` and `keybar.border-width`
in style.toml), apart from the widgets'. In a keybar file `gapsOut` and `borderWidth` read them, and `gapsIn` is the
widgets' as anywhere: the stock bar keeps `gapsOut` to the screen's sides and the keyboard and draws its frame
`borderWidth` thick, so a bar that follows them changes with the setting.
