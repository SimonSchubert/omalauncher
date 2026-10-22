# Apps

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

A file can also be an app instead of a widget: a screen of its own, listed in the app drawer.
The nodes at the top are the whole view, with the room and limits of a `screen { }`, and it takes
the same declarations as a widget (sources, state, lets, components, triggers, `requires`, `meta`).
There's no tile, so no `size`, `minSize`, `page`, `order`, `padding`, `background`, `onTap` or
`screen { }`; the properties are `title`, `icon`, `back` (with its `onBack`), `edge` and `statusBar`, and `close()` closes it. Apps need owl 8 and
run only while open.

```owl
app "notes" {
  meta { owl: 8 }
  title: "notes"                // the window's title; the drawer shows the name
  icon: """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">
    <path d="M11 5h18l9 9v29H11z"/>
    <path d="M29 5v9h9M17 24h15M17 32h10"/>
  </svg>"""
  state text = "" persist
  Column {
    gap: 8
    padding: [12, 0]           // the view runs to the screen's edges; this is the usual margin
    Input { text: text; placeholder: "write…"; focus: true; onSubmit: text = value }
    Text { text: text }
  }
}
```

**Edges.** An app fills the screen below its title, from edge to edge: there's no window border
and no margin, so the app decides where its content sits. For text, lists and forms, give the top
node `padding: [12, 0]`, which lines them up with the title. Leave it off (or pad only the parts
inside) for what should run to the edges, like a photo, a map, a canvas or a bar along the bottom.

**Under the navigation bar.** An app ends above Android's navigation bar (the gesture handle or the
three buttons). With `edge: true` it runs on under it, like a list that scrolls behind the bar:
`screenHeight` then reaches the screen's bottom, and `screenInset` is how much of it the bar covers,
in dp (0 without `edge`, and while the keyboard is up; only apps and pages have it, since a widget's screen ends above the bar). Taps there go to Android, so keep buttons
and the last row of a list above it: end a scrolling list with `Spacer { height: screenInset }`, and
pad a view with buttons along its bottom by `screenInset`. `edge` is `true` or `false`, not an expression.

```owl
app "photos" {
  meta { owl: 8 }
  edge: true
  Column {
    scroll: true
    padding: [12, 0]
    for p in range(40) { Text { text: "photo ${p}" } }
    Spacer { height: screenInset }   // the last row scrolls up clear of the bar
  }
}
```

**Going back.** An app with views inside it (a list, then one item) says when Back goes up a
level with `back`, and how with `onBack`. While `back` is true, the window shows a chevron before
the title, and the chevron, the title and Android's Back run `onBack`; otherwise Back leaves the app.
`title` is an expression too, so it can name the view that's open:

```owl
app "recipes" {
  meta { owl: 8 }
  state open = ""
  title: open == "" ? "recipes" : open
  back: open != ""
  onBack: open = ""
  Column {
    if open == "" {
      for r in ["soup", "bread"] { Text { text: r; onTap: open = r } }
    } else {
      Text { text: "how to make ${open}" }
    }
  }
}
```

**Its own title.** `title: ""` leaves the window's title row out, and the view starts at the top, under the
status bar: for a game or a view with a header of its own. Recent apps still shows the name. While `back` holds
there's no chevron to tap then, so put a way back in the view, or leave it to Android's Back, which still runs `onBack`.
`statusBar: false` (owl 11) hides the status bar too, as on a face, and the view takes its room: a swipe down from
the top brings it back for a moment. It can be any expression (`statusBar: !playing`). The view then runs up to the
screen's top edge, around the camera on a phone with a cutout: `screenTop` (owl 12) is how much of the top the
cutout covers, in dp (0 without one, and while the status bar shows). Draw the background there and start the content
below it, `padding: [0, screenTop, 0, 0]`, the way `screenInset` keeps buttons clear of the navigation bar.

An app is listed in the app drawer (under apps, and in search) by its name. Tapping it opens
it in its own window, with its own card in recent apps and its `title` (or name). Like any Android app,
Back leaves it and swiping its card away in recent apps closes it; `close()` closes it too.
Long-press it in the drawer to add it to home, like any app.

**Icon.** `icon` is the app's icon in the drawer, on home and in recent apps. It's fixed in the
file rather than an expression, since it's drawn before the app runs, and it's one of two things:

- an SVG in a string, `icon: """<svg ...>...</svg>"""` (under 16 KB). `currentColor` in it becomes
  the Omarchy theme's accent, so the icon follows theme changes; other colors stay as written.
- an image, `asset logo = base64"..."` and `icon: logo`: a PNG (or WebP) shown as it is, scaled to
  fit a square. For a logo or artwork that has its own colors.

Without one, the drawer shows the name's initials. Give every app one. The launcher draws icons
standalone, on the theme's background, with no plate or frame of its own, so:

- Draw just the symbol, transparent around it: a square `viewBox` (`0 0 48 48`) filled edge to edge
  by a line drawing in `currentColor`, strokes about 3.5 wide with round caps and joins, like the
  example above. That's how the store's own apps look, so they sit together in the drawer.
- A backdrop or frame is yours to add if the icon needs one (a rounded `<rect>` behind the symbol),
  but most don't.
- One idea, two or three shapes, readable at 40 dp: a page for notes, arrows for a converter.
  No text, and no other app's or brand's logo.
- With monochrome icons on, the launcher draws the icon's bright parts in one color, so the shape
  has to read by its outline alone.

owl-check doesn't draw the icon; to look at an SVG, render it yourself (for example
`rsvg-convert -w 192 icon.svg > icon.png`, from `librsvg`).

A widget and an app are separate files and don't share state; a widget that needs a bigger view
of itself has its own `screen { }`.
