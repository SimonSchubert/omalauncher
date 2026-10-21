# Panels

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

A panel is a file of its own that a swipe on home opens: swiping up opens the app drawer, above the prompt bar;
swiping down brings one from the top. The app drawer is a panel too, `drawer.owl`, and the launcher has no other:
edit it to change the drawer, or read it to see how a whole drawer is put together. Which panel each swipe opens
is picked in settings › gestures, or in style.toml as the panel's file name (see `defaults.toml`):

```toml
[gestures]
swipe-up = "my-drawer.owl"     # drawer.owl by default; "none" leaves the swipe to tapping the bar
swipe-down = "controls.owl"    # "none" nothing
```

The drawer is the panel picked for swiping up, else `drawer.owl`, else `.stock-drawer.owl`: the drawer as this
launcher ships it, which it keeps that way. So a drawer that's removed or stops compiling never locks anyone out of
their apps (a panel picked for swiping down that's broken opens nothing). Home's banner says which, and settings list it
with "(has errors)". Compare `drawer.owl` with `.stock-drawer.owl` to see what changed in the stock drawer since. With
nothing on swipe up, tapping the bar still opens the drawer.

```owl
panel "my drawer" {
  meta { owl: 11 }
  source d = android.drawer
  let shown = query == "" ? d.apps : rank(d.apps, query)
  // Enter in the prompt bar: open the best match when it names an app well, as the bar does; a sentence asks the agent.
  onSubmit: {
    if len(shown) > 0 && strongMatch(shown[0], value) {
      open(shown[0].key)
    } else if trim(value) != "" {
      ask(value)
    }
  }
  Grid {
    lazy: true
    weight: 1
    columns: 5
    for a in shown key a.key {
      Column { align: center; onTap: open(a.key); Image { src: a.icon; size: 52 }; Text { text: a.name; style: small } }
    }
  }
}
```

The launcher keeps what's around a panel: the backdrop, the panel following the finger as it opens, closing it by
pulling down past the top of its list (pushing up past the end, for one from the top), a tap on its empty space or
Back. The drawer sits above the prompt bar, which stays with its microphone and agent menu: `query` is the bar's text
as it's typed, and `onSubmit` runs on Enter with it as `value`; without `onSubmit`, Enter asks the agent. Only a
panel opened by swiping up has the bar: in one from the top, `query` stays "" and `onSubmit`, `enter` and `onKey` never run. `enter` is what the bar shows it does, like
`enter: opens ? "▸ open" : "⏎ ask"` ("" shows nothing). With a keyboard attached, the bar's Up, Down, Tab and
Shift+Tab go to `onKey` with `value` set to `up`, `down`, `tab` or `shift-tab`, so a panel can move its own
highlight (drawer.owl keeps a `pick`). Up and Down come only while what's typed is one line; in more, they move
between its lines. Without `onKey`, the keys stay with the bar and do what they do in any text field. `open(...)` and `appAction(...)` leave the
panel as opening an app from the drawer does, and `close()` slides it away. Besides `enter`, a panel has no
properties, title or icon; like an app's, its nodes are the screen's. Give a long list `lazy: true`.
