# Faces

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

A file can also be a face: a whole home page, the whole screen, as a watch face is a watch's. A launcher
screen of your own, a dashboard to swipe to, a start page with your day on it. Faces sit left of page 1, where a stock
launcher has its news feed, and you can have as many as you like there. A face is built like a widget,
with the same declarations, nodes, `onTap` and `screen { }`, and it has a screen's room: up to 1000
nodes. Faces need owl 10.

```owl
face "morning" {
  meta { owl: 10 }
  source now = clock every 1m
  source wx = http("https://wttr.in/?format=j1") every 30m json
  let cur = wx.current_condition[0]
  Column {
    gap: 16
    padding: [24, 32]
    Spacer { weight: 1 }
    Text { text: fmtTime(now, "HH:mm"); style: huge }
    Text { text: fmtTime(now, "EEEE d MMMM"); style: muted }
    Text { text: cur ? "${cur.temp_C}°  ${cur.weatherDesc[0].value}" : "" }
    Spacer { weight: 1 }
    Spacer { height: screenInset }
  }
}
```

- **Where it goes.** `page: 0` (the default) is the page just left of page 1; `-1`, `-2`, ... go further
  left. Home opens on page 1, and an empty page always waits left of the leftmost one, inviting a new
  face there: only faces go left of page 1, never tiles. One whose place is taken goes to that empty
  page instead. A face can also take a page right of page 1 (`page: 3`) if no tiles are there.
- **The screen.** It fills the whole screen: the recent apps strip and the bar slide away with the tile
  pages (swipe up for the app drawer, which brings the bar back). The status bar stays, over the face,
  and the nodes keep clear of it; `statusBar: false` hides it while the face shows (a swipe down from the
  top brings it back for a moment), and they keep clear of a camera cutout only. The background runs
  under both. `statusBar` can be any expression, and the bar comes and goes as it changes: `statusBar: !playing`
  keeps it while a game waits and hides it while one runs.
- **The navigation bar.** The face runs on under it, like an app with `edge: true`: `screenInset` is how
  much of its bottom the navigation bar covers, in dp, so end it with `Spacer { height: screenInset }` or
  pad buttons along the bottom by it. While one of its `Input`s has the keyboard, `screenInset` is what the keyboard
  covers instead, so the same Spacer lifts the field above it; a face can also leave things out then
  (`if screenInset < 120 { ... }`) to make room. The face itself stays put, under the status bar and the keyboard.
- **Looks.** There's no tile frame and no background: it draws straight on the wallpaper. Where text needs
  one to be readable, give the face `background: alpha(background, 0.7)` or a gradient (owl 10), or just the
  nodes that hold it, which can be frosted glass (`frost`, owl 10).
  `padding` (12 by default) is around everything, as on a tile.
- **Size.** `cols` and `rows` are the whole grid (6 or so by 12), and `tileWidth` and `tileHeight` the
  screen's size in dp: lay it out by those, since phones differ. It takes no `size` or `minSize`.
- **Foldables.** Unfolded, where home shows two pages of tiles side by side, a face still gets the whole
  screen, both halves, so `tileWidth` can be more than `tileHeight`. Lay it out for that too: the examples
  take `let wide = (tileWidth ?? 0) > (tileHeight ?? 1)` and put two columns side by side when it is. The
  same goes for a tablet and for landscape.
- **On the phone.** Long presses are the face's own (`onLongPress`); one that no node takes opens the home
  menu, as on empty space between tiles. Faces are edited, shared, moved and removed in settings › pages, which lists home's pages left to right, faces and pages of tiles: drag one by its handle to move it. Swiping up opens the app drawer and sideways turns the page, so
  a `scroll: true` Column scrolls up and down, and a face has nothing to swipe sideways.

owl-check's preview of a face is the face at the phone's size, as home last laid it out. `examples/00-start.owl`
is a whole face, a start page: the time, the weather, battery, the next alarm, notifications and your most used apps.
Three more show other ways to draw one: `01-minimal` (a thin clock and your apps as words, in a font of its own),
`02-pixel` (an 8-bit landscape that follows the day and the weather, drawn with sprites, over a game HUD) and
`03-matrix` (digital rain in two depths with the time carved out of it, worked out once and drawn every frame in a Canvas).
