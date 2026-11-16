# The strip above the prompt bar

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

Above the prompt bar on home sits a strip of the apps opened lately; in landscape it stands beside the grid. It's the
file `strip.owl`, a `strip "…" { }` file (owl 24), and it's yours to change: apps kept in place like a dock, bigger
icons, a second row, some apps left out, no frame. `.stock-strip.owl` is the strip as this launcher ships it, which it
keeps that way; it shows while `strip.owl` is gone or doesn't compile (home's banner says so), and comparing the two
shows what changed in a newer launcher. Settings › style › recent apps hides the strip altogether.

```owl
strip "big icons" {
  meta { owl: 24 }
  source s = android.strip
  component App(a) {
    Box {
      size: 64
      align: center
      radius: 12 * corners
      onTap: open(a.key)
      if a.hasIcon {
        Image { src: a.icon; size: 56; tint: s.monochrome ? accent : null; label: a.label }
      } else {
        Text { text: a.initials; color: accent }
      }
    }
  }
  // Beside the grid in landscape: a column as wide as the icons, scrolling down.
  if s.vertical {
    Column {
      width: 72; fill: height; scroll: true; gap: 4; align: center; padding: [0, 6]
      background: alpha(background, tileOpacity); frost: tileFrost; border: borderWidth; borderColor: s.border; radius: 16 * corners
      for a in s.apps key a.key { App { a: a } }
    }
  } else {
    Row {
      fill: width; height: 72; scroll: true; gap: 4; padding: [6, 0]
      background: alpha(background, tileOpacity); frost: tileFrost; border: borderWidth; borderColor: s.border; radius: 16 * corners
      for a in s.apps key a.key { App { a: a } }
    }
  }
}
```

The file is the whole strip: above the bar it's as wide as home's tiles and as tall as its nodes, and stood up beside
the grid in landscape it's as tall as the screen and as wide as its nodes. Lay it out both ways, as above: a Row that
fills the width is the wrong shape beside the grid. Home's tiles make room for the strip, each row of tiles getting
shorter rather than any tile moving, up to a third of the screen's height above the bar and a quarter of its width
beside the grid; past that the strip is cut off. A strip with nothing to show can be 0 tall and give the tiles all
the room. It takes no properties or handlers of its own, and has no `screen { }`. Swiping up on it opens the drawer,
as it does on the bar, and a Row or Column with `scroll: true` scrolls the other way. Back on home the strip starts
again at its beginning.

`source s = android.strip` is what to show (see docs/android.md): `s.apps`, the recent apps, last opened first, each
like an app of `android.drawer`, with its icon, initials, whether it's paused, and the words of its long-press menu;
`s.vertical` while it stands beside the grid, so one file can lay itself out both ways; `s.monochrome`, `s.bare` (home's
apps drawn as icons only, without a tile) and `s.border` (the tiles' outline color), to look like home. `a.mask` is an
app's silhouette whatever the icon style, for a strip of its own look: `Image { src: a.mask; tint: accent }`. Tinting
`a.icon` instead colors the whole picture, a solid shape, unless the icons are monochrome already. The glass of
home's tiles is `background: alpha(background, tileOpacity); frost: tileFrost; border: borderWidth; borderColor: s.border`.
Anything else a file can read works too: `android.drawer` for every app, to keep some in place, or `state … persist`
for which ones.

`open(a.key)` opens an app as a tap in the drawer does, `onLongPress` with a `Popup` makes a menu, and
`appAction(word, a.key)` runs one of `a.actions`, as the stock strip and the drawer do; `shortcuts(a.key)` gives an
app's shortcuts for its menu. With a keyboard, the arrows move between the strip's apps, and on toward home's tiles from
its edge.

`strip.owl` is the stock strip: the recent apps, the long-press menu with shortcuts, and both directions.
`examples/strip-pinned` keeps apps in place first, sized to share the strip, with the recent ones after them; move it
up into this folder as `strip.owl` to use it. `owl-check strip.owl` draws the strip above where the bar would be.
