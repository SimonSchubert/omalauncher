# Nodes

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

`Name { prop: value; ...children }`. Structure: `if cond { } else { }`,
`for x in list { }`, `for x, i in list key x.id { }` (a key keeps rows stable
when lists reorder), and `let x = ...` inside any node.

| node | properties |
|---|---|
| `Column` | `gap` (dp, default 4), `align: start/center/end`, `justify: start/center/end/spaceBetween/spaceAround/spaceEvenly`, `scroll: true`, `lazy: true` (scrolls, and a `for` right inside builds its rows only as they come into view, so a list of hundreds opens as fast as a short one; give it a height to fill, such as `weight: 1`, since inside a scrolling Column it builds everything, owl 11), `reverse: true` with `lazy` (lists from the end: the first item at the bottom, as search results sit right above a bar; when its items change it scrolls back to the first, owl 11) |
| `Row` | `gap` (8), `align: top/center/bottom`, `justify`, `wrap: true` |
| `Flow` | a Row that wraps to new lines |
| `Box` | stacks children on top of each other; `align: center/top/bottom/start/end/topStart/topEnd/bottomStart/bottomEnd` |
| `Grid` | `columns` (2), `gap`, `lazy: true` (as Column's: scrolls, and builds the cells of a `for` right inside as they come into view, owl 11), `reverse` as Column's; children fill cells left to right |
| `Text` | `text`, `style: huge/title/body/small/muted/label`, `color`, `fontSize`, `bold`, `italic`, `font: mono/sans/serif/icon` (`icon` draws the Nerd Font weather glyphs U+E300–E3E3, plus 󰄬 U+F012C and 󰅖 U+F0156) or since owl 10 a font of the widget's own (see Fonts), `align: start/center/end`, `maxLines`, `minLines` (at least that many lines tall at any font size, so a row of labels keeps one height; a `height` in dp cuts text off when the phone's text is larger, owl 11); since owl 10 `fontWeight` (100 thin to 900 black; the font shows the nearest weight it has), `letterSpacing` (sp, negative to tighten) and `spans` (see Text in pieces) |
| `Button` | `text`, `color`, `onTap` |
| `Progress` | `value`, `max` (100), `color`, `track` |
| `Ring` | circular progress: `value`, `max` (1), `color`, `track`, `thickness` (6), `start`/`sweep` (degrees; `start: 225; sweep: 270` makes a gauge). 64 dp unless sized |
| `Sparkline` | `values` (a list, usually a `history`), `min` (0), `max`, `color`, `area: true` or a color, `thickness` |
| `Image` | `src`: an `asset`, a path in this system (`"~/pics/a.png"`), an `https://` URL (owl 8, see Images from the web) or an Android app's icon (`"app:org.pkg"`, as the drawer shows it, badge included; a silhouette in the foreground color with monochrome icons; `"app:org.pkg@work"` and `"@clone"` ask for the copy in a work profile or the clone, owl 7), or any app's icon by its `android.recent` key (`"icon:l:foot"`, Linux and owl apps and shortcuts too; `android.recent` and `android.agents` give it as `icon`), `fit: contain/cover/fill`, `tint`, `label` (what screen readers say for it, owl 11) |
| `Canvas` | free drawing, see docs/drawing.md. Fills the space unless sized |
| `Shader` | an AGSL shader, see docs/drawing.md. Fills the space unless sized |
| `Spacer` | empty space: `size`, `width`, `height` or `weight` |
| `Slider` | a horizontal slider: `value`, `min` (0), `max` (100), `step`, `color`, `track`, `live`. `onChange` gets the chosen `value` when the finger lifts, or at every step while dragging with `live: true`. With keys, Left and Right step it by `step`, else a twentieth of the range (a hundredth with Shift), each step sent to `onChange`. Full width and 28 dp tall unless sized (owl 4) |
| `Input` | a one-line text field: `text` (what it shows; replaces what's typed when it changes), `placeholder`, `color`, `fontSize`, `clear: true` (empty it after submitting), `underline: false` (no line under it, for a field inside a frame of its own, owl 10), `password: true` (dots for what's typed, and a keyboard that neither suggests nor learns it, owl 12), `focus: true` (focus it and raise the keyboard when the screen opens, or when `focus` turns true; only in a `screen { }`, owl 6). `onChange` gets the text as `value` as it's typed, `onSubmit` when the keyboard's send key is pressed (owl 4) |
| `Popup` | a menu over everything, the tile, the bar and other tiles, at the start of the node that holds it: `position: below` (the default) or `above`, kept on screen and on the other side when there's no room, `gap`, `align`. Show it with `visible`; a tap outside it or Back runs `onDismiss`, which it must have, so it can close: `Box { Text { text: "menu"; onTap: open = true }  Popup { visible: open; onDismiss: open = false; … } }`. Framed like the launcher's menus unless it has a `background` or `border`. It takes no room where it's written (owl 10) |
| `Terminal` | a live terminal (foot) in the tile: `command` (default: the login shell), `fontSize` (points; foot.ini has 8). Fills the space unless sized. See below |

Every node also takes:

- `width height size` (dp), `fill: true/width/height`, `weight` (share of the
  space left in a Row or Column), `padding` (`8`, `[h, v]` or `[l, t, r, b]`)
- `background` (a color, or since owl 10 a gradient, see Gradient backgrounds), `border` (dp), `borderColor`, `radius`, `clip: true`
- `frost` (owl 10): frosted glass, the wallpaper behind the node blurred by that many dp; see Frosted glass
  (without `radius`, bordered elements follow the launcher's corner style: 0 for edge, 4 for soft, 8 for round;
  anything else matches it with `radius: 8 * corners`)
- `opacity`, `rotation` (degrees), `scale`, `offset: [x, y]` (dp), `visible`
- handlers: `onTap` (with `x`, `y`) and `onDrag` (with `dx`, `dy`, `x`, `y`). Since owl 13, `drag: horizontal` (or
  `vertical`) has `onDrag` take only moves along it, so a list it's in still scrolls the other way, and `onDragEnd` runs as
  the finger lets go, with `vx` and `vy`, how fast it was moving in dp a second: a row that follows the finger with
  `offset: [dragX, 0]` can go when it's let go far out or flicked (see `controls.owl`); `onPress` as the finger goes down, before
  it's a tap (with `x`, `y` and `repeat`), and `onRelease` when it lifts or moves on to a scroll (owl 12). `onPress` runs for
  every touch, the start of a scroll too, so in a list or anything else that scrolls, `onTap` is the one for a tap. With
  `repeats: true`, `onPress` runs again while the finger stays down, after 400 ms and then every 50 ms, as a keyboard's
  keys repeat: `repeat` is 0 for the press and counts up from there; `Slider` and `Input` also take
  `onChange`, and `Input` `onSubmit`, both with `value`; `Popup` takes `onDismiss`. In pages, apps and a widget's `screen { }`,
  `onLongPress` (with `x`, `y`, owl 10); on a tile, long-press belongs to the home screen, which uses it
  to move and resize tiles. A node with `onTap` or `onLongPress` ripples from where it's pressed, within its `radius`, in apps, panels, faces and screens; on a widget's tile and in a key bar it stays still.
  `ripple: false` (owl 14) leaves one still anywhere, for a node that shows its press itself, as with `onPress`.
  With a keyboard or a gamepad, Tab and the arrows (the D-pad) move between the nodes that
  have `onTap` or `onLongPress`, ringed in `accent`; Enter, Space or A taps one at its middle, and holding it, or Menu
  (a gamepad's Select), long-presses it. What takes taps inside one that does, like a `[+]` in a tappable row or a
  widget's buttons on its tile, is reached with Right from it, and Left from the first comes back out; Enter on a tile
  whose widget has no `onTap` of its own goes inside too. Up and Down leave an `Input`. A Popup keeps the keys while
  it's open and gives them back to the node a key opened it from as it closes
- `animate prop: spring()` or `tween(300ms, "linear"/"in"/"out"/"ease")`, for `value`,
  `opacity`, `rotation`, `scale`, `color` or `background`, and `offset` (owl 13). For springs:
  `spring(damping, stiffness)`. While a node's own `onDrag` has the finger, its animated `offset` follows the finger
  as it is; let go, it eases to where `onDragEnd` puts it, so a row swiped too little springs back. Only its own: an `onDrag` on a
  parent or a child doesn't count, and the offset trails the finger on its spring

`opacity`, `rotation`, `scale`, `offset`, colors and the contents of Canvas and
Shader apply at draw time. Changing them, even every frame, costs no layout.

## The tile's size and corners

The tile's size is available as `cols` and `rows` (grid cells) and `tileWidth` and
`tileHeight` (dp, including the tile padding). They change when the user resizes the
tile, so a widget can pick a layout to fit (`if (tileHeight ?? 999) < 185 { ... } else { ... }`).
Decide by `tileWidth` and `tileHeight`, not `cols` and `rows`: a grid row is a different number
of dp on every phone, so a 3-row tile on a tall phone has room a check like `rows < 4` throws
away. They're `null` for a moment before the tile is laid out. A widget's own declaration named
`rows` or `cols` takes precedence.

`corners` is the user's corner style as a multiplier, not a radius: 0 (edge, square), 0.5
(soft) or 1 (round). Multiply the radius a shape would have on round by it: `radius: 8 *
corners` for cards and album art, `3 * corners` for small chips, and the same in Canvas
(`rect(x, y, w, h, radius: 8 * corners)`). The tile, buttons and bordered elements without a
`radius` follow it on their own; a fixed `radius`, a clipped Box, an Image or a Canvas shape
doesn't. It needs `meta { owl: 6 }`.

`gapsIn`, `gapsOut` and `borderWidth` are the user's widget gaps and border width in dp, from settings › style: a
widget with a frame of its own can space and outline it like home's tiles (`border: borderWidth`). In a keybar `gapsOut`
and `borderWidth` are the terminal key bar's own settings instead. They need `meta { owl: 12 }`.

`tileOpacity` and `tileFrost` are the glass of home's tiles, from settings › style: how much a widget covers the
wallpaper, 0 (clear) to 1 (solid), and how much it blurs it in dp, 0 with frosted glass off. A face's windows, or a
widget's cards, look like home's tiles with `background: alpha(background, tileOpacity); frost: tileFrost`, and follow
the settings as they change. They need `meta { owl: 13 }`.

## Text in a Row

Since owl 10 a Text in a Row or Flow is as wide as its text, like a Button: labels, chips and values sit side by
side without a `width`, and a `weight: 1` neighbour takes what's left. Before, it took the whole width unless
sized, and widgets that declare an older owl keep that. In a Column a Text still takes the whole width.

Since owl 11 that goes for a Column in a Row or Flow too: without `width`, `weight` or `fill` it's as wide as its
content, and its Texts as wide as their text, so a row of icon-over-label cells sits side by side. Give the cells
`weight: 1` to share the row evenly; something in one with `fill: width` still stretches it to what the row has left.

## Text in pieces

A Text's `spans` (owl 10) is its text in pieces, each styled on its own: a piece is text, or a map of its
`text` and what differs from the rest, `color`, `bold`, `italic`, `fontSize`, `fontWeight`, `letterSpacing`
or `font` (in a map it's a value, so the words are text there: `font: "serif"`). Spaces at a piece's ends stay. It takes the place of `text`; the Text's own properties are the rest's.

```owl
widget "clock" {
  meta { owl: 10 }
  source now = clock every 1m
  state cpu = 42
  Column {
    Text { spans: [fmtTime(now, "HH"), {text: ":", color: accent}, fmtTime(now, "mm")]; fontSize: 64; fontWeight: 200 }
    Text { spans: ["cpu ", {text: "${cpu}%", bold: true, color: cpu > 80 ? red : green}]; style: small }
  }
}
```

## Fonts

Since owl 10 `font` can also be a font of the widget's own, for a clock in a display typeface, a heading in a
serif with character or a pixel font for a game: a TTF or OTF from an `asset`, an `https://` URL or a file in
this system (`"~/.local/share/fonts/x.ttf"`, which needs Linux). It shows the launcher's font for the moment
it takes to load.

```owl
face "poster" {
  meta { owl: 10 }
  source now = clock every 1m
  asset pixel = base64"AAEAAA=="        // a small font's whole file in base64: the widget stays self-contained
  let fraunces = "https://raw.githubusercontent.com/google/fonts/main/ofl/fraunces/Fraunces%5BSOFT,WONK,opsz,wght%5D.ttf"
  Column {
    Text { spans: [fmtTime(now, "HH"), {text: ":", color: accent}, fmtTime(now, "mm")]; font: fraunces; fontSize: 96; fontWeight: 100 }
    Text { text: "LEVEL 3"; font: pixel; fontSize: 20 }
  }
}
```

- **Weights.** A variable font with a weight axis (most of Google Fonts' are, `[wght]` in the name) draws
  every `fontWeight` it has, 100 to 900. A font of one weight is drawn thicker for `bold` or 600 and up, and
  slanted for `italic`.
- **From the web.** Downloaded from Android and kept like images from the web, and listed for review the same
  way: `font GET https://…`. Google Fonts' own files are in https://github.com/google/fonts, under
  `ofl/<name>/`; take the link of the `.ttf` from `raw.githubusercontent.com`. WOFF and WOFF2 web fonts
  don't load: Android reads TTF and OTF only.
- **Embedded.** An asset font makes the file big (a whole font is 30 KB to 1 MB), too big for a QR code to
  share. Embed a small one, or link one.
- Up to 8 fonts per file, and 8 MB each. Mind the license: Google Fonts' are OFL, free to use and share.

## Gradient backgrounds

Since owl 10 a node's `background`, and a face's or tile's, can be a `linear(...)` or `radial(...)`. There its
points are fractions of the node, 0 to 1 across and down, since its size in dp isn't known as you write it:
`linear(0, 0, 0, 1, [...])` runs top to bottom, `linear(0, 0, 1, 1, [...])` corner to corner, and a radial
one's radius is a fraction of the longer side, `radial(0.5, 0.5, 0.7, [...])`. The colors are spread evenly.
A face's background runs under the status bar too, so a veil darker where the text is reads well on any
wallpaper:

```owl
face "dusk" {
  meta { owl: 10 }
  background: linear(0, 0, 0, 1, [alpha(background, 0.85), alpha(background, 0.35), alpha(background, 0.9)])
  Column {
    padding: 16
    background: radial(0.5, 0, 1, [alpha(accent, 0.3), alpha(surface, 0.6)])
    radius: 16 * corners
    Text { text: "evening" }
  }
}
```

An animated `background` is a color, as before.

## Frosted glass

`frost: 24` (owl 10) draws the wallpaper behind a node blurred, by about that many dp, within its `radius`.
The node's `background` goes over it, so a translucent one tints the glass and keeps text on it readable:

```owl
face "glass" {
  meta { owl: 10 }
  Column {
    padding: 16
    frost: 24
    background: alpha(background, 0.5)
    radius: 18 * corners
    Text { text: "on frosted glass" }
  }
}
```

It blurs the home screen's wallpaper only, not other nodes or a page's own background, and follows it as
the pages slide. Where no launcher wallpaper is behind, as in an app's window, a widget's screen or
owl-check's preview, there's nothing to blur and only the `background` shows, so give one that works alone.

## Images from the web

`Image` and Canvas `image(...)` take an `https://` URL as well as an asset or a file (owl 8). The launcher
downloads it from Android, so it works without Linux, and keeps it: it shows offline after the first
time, and is asked for again after a day. PNG, JPEG, WebP and SVG work; an SVG, from a URL, an asset or
a file, is drawn as shapes, so it's sharp at any size and costs almost nothing to keep.

```owl
app "flags" {
  meta { owl: 8 }
  state code = "se"
  Image { src: "https://flagcdn.com/${code}.svg"; height: 120; fill: width }
}
```

Like `http(...)`, the URLs are listed for whoever reviews the file: `image GET https://flagcdn.com/…` (and fonts, see Fonts).
Keep the start of the URL fixed, up to its first `/` after the server, so the server is known; a URL
built entirely from values is flagged. Plain `http://` isn't loaded.

## Components

```owl
component Stat(label, value, tint = accent) {
  Column { gap: 0
    Text { text: label; style: label }
    Text { text: value; style: title; color: tint }
  }
}
Row { Stat { label: "cpu"; value: "${cpu}%"; weight: 1 }  Stat { label: "mem"; value: mem; weight: 1 } }
```

Parameters are live expressions from the caller. Layout properties like
`weight` or `padding` at the use site wrap the component. Don't name a parameter after a
property value you use inside, like `icon` with `font: icon`: the parameter wins, and the
property gets its value instead.

## Terminal

A real terminal inside the tile, with a shell or any program (`btop`, `htop`,
a REPL) that you can type into:

```owl
widget "shell" {
  size: 6x4
  padding: 0
  meta { owl: 2 }
  Terminal { }
}
```

- The tile is inert until tapped, so swipes still turn pages. Tapping it gives it
  the keyboard and its touches (drag scrolls, hold-and-drag selects). A tap
  outside it, Back or another page lets go. The ⌨ button toggles the soft keyboard,
  and a tap on the line with the terminal's cursor (its prompt) brings it back.
- The terminal keeps running while its page is off screen. It ends when the widget
  is removed or its file changes. When `command` exits, the tile offers a restart;
  use `"btop; exec bash"` to fall back to a shell.
- `padding: 0` lets the terminal fill the tile's frame.
- Its background is see-through: the tile's glass (`widgets.opacity` in style.toml), or a `background`
  behind it, shows where the program leaves the terminal's own. Cells a program colors, like a status line, stay solid.
