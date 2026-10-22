# Home-screen widgets and apps

Each `*.owl` file in this folder is a widget on the Android home screen,
written in OWL, a small declarative language in the spirit of QML. Its first
word says what it is: `widget "name" { }` is a home-screen tile, `face "name" { }` a face, a whole home page
left of your first one (docs/faces.md), and `app "name" { }` an app in the app drawer that opens full screen
(docs/apps.md). `panel "name" { }` is a view a swipe on home opens (docs/panels.md), and `keybar "name" { }` the terminal key bar
over the keyboard while typing in a terminal (docs/keybar.md). Everything here and in docs/ is about widgets unless it says otherwise; the others are written
the same way. Save a file and it appears within a second. Then run `owl-check <file>`:

```
$ owl-check 50-memory.owl
50-memory.owl: compiles
  preview: ~/.config/omalauncher/widgets/.preview/50-memory.owl.png (3x4 cells, 528x712 px, with live data); look at it to check the layout
```

A widget with a `screen { }` (docs/screens.md) gets a second picture, of the screen as it opens. An
app has no tile: its picture is the `screen:` line, of the whole view at the phone's size.

It waits until the launcher has loaded the file as saved, prints compile errors
and problems found while running, and exits 1 if there are any. The preview is
the tile as it looks on the home screen, at its size there, after its sources
have loaded: open it to see whether text fits and the layout works, since you
can't see the phone. Terminal tiles aren't previewed. Errors also go to
`.errors/<file>.txt`, and the tile shows them.

Widgets sit on a grid of roughly square cells, 6 columns by default (a phone
has about 12 rows), bordered like Omarchy windows and colored by the current
Omarchy theme. Users move and resize them on the phone; where everything is
lives in ~/.config/omalauncher/home/layout.toml, which you can edit too (see
docs/home.md).

```owl
widget "memory" {
  page: 1                 // home page it starts on, 1-based; then layout.toml says
  order: 2                // placement order on the page when first placed (lower = first)
  size: 3x4               // size in grid cells when first placed: columns x rows
  minSize: 2x3            // users can't shrink it below this

  source mem = cmd("free -b | awk '/Mem:/ {print $2, $3}'") every 5s
  let used = num(words(mem)[1])
  let total = num(words(mem)[0])
  let pct = total ? used / total * 100 : null
  let hist = history(pct, 40)

  onTap: terminal("btop")

  Column {
    Text { text: "memory"; style: muted }
    Text { text: "${round(pct)}%"; style: title; color: pct > 85 ? red : foreground }
    Progress { value: pct; animate value: spring() }
    Sparkline { values: hist; min: 0; max: 100; area: true; fill: width; weight: 1 }
  }
}
```

Every property is a live expression: when a source updates, only the parts
that read it redraw. Properties are separated by newlines or `;`.

## The language in brief

A file holds declarations, then the nodes it shows:

- `source x = cmd("...") every 5s` runs a bash command in this Arch system, and `x` is its output (`json` parses
  it). `http("https://...")` fetches from Android, with no Linux needed; `stream`, `file` and `clock` are sources
  too, and `android.battery`, `android.media`, `android.notifications` and more read the phone.
- `state x = 0` is a value handlers change (`persist` keeps it across restarts), and `let x = ...` a value worked
  out from the others, again whenever they change.
- Nodes are `Name { prop: value; ...children }`, with `if c { } else { }`, `for x in list { }` and `let` among them.
  `Column`, `Row`, `Box`, `Grid` and `Flow` lay out; `Text`, `Button`, `Image`, `Progress`, `Ring`, `Sparkline`,
  `Slider`, `Input`, `Canvas`, `Shader`, `Terminal` and `Spacer` show things. Every node takes `width`, `height`,
  `weight`, `fill`, `padding`, `background`, `radius`, `visible` and more.
- Handlers, like `onTap: count = count + 1`, set state and run actions: `terminal("btop")`, `run("cmd")`,
  `url("https://...")`, `notify("title", "text")`, `post(url, body)`. An API key or a token is a
  `state key = null secret`, typed in on the phone.
- Text interpolates (`"${round(pct)}%"`). What's missing is `null`, never an error, and shows as `…`; `a ?? b` is b
  while a is null. Lists have `map`, `filter`, `sortBy` and the rest. Colors are the theme's names (`accent`,
  `foreground`, `muted`, `red`, ...), so widgets follow theme changes.
- `meta { owl: 12 }` says which version of the language the file needs. Declare the highest it uses; without it,
  it's 1. This launcher has 12.

## The docs

The rest is in `docs/`. Read the ones for what you're making before you write: a widget usually needs
declarations.md, nodes.md, expressions.md and handlers.md; a face, an app, a panel or a key bar their own file too.

| read | for |
|---|---|
| docs/declarations.md | sources (`cmd`, `http`, `stream`, `file`, `clock`), state, lets, triggers (`every`, `at`, `when`, `on`), putting values into commands and URLs, `requires` and working without Linux |
| docs/android.md | the phone's data: battery, media, notifications, volume, the next alarm, modes, apps, the drawer, agents |
| docs/expressions.md | operators and every function, dates and times, numbers, changing lists and maps, chance |
| docs/nodes.md | every node and its properties, the tile's size, text in pieces, fonts, gradients, frosted glass, images from the web, components, Terminal |
| docs/handlers.md | handlers, every action, sending to web APIs with `post`, tokens and passwords, sounds |
| docs/drawing.md | Canvas and Shader |
| docs/screens.md | a widget's full-screen view behind its tile |
| docs/faces.md | faces: a whole home page |
| docs/apps.md | apps in the app drawer, and their icons |
| docs/panels.md | panels a swipe opens, the app drawer among them |
| docs/keybar.md | the terminal key bar over the keyboard |
| docs/home.md | home's layout.toml, and reaching a running widget from scripts with `owl-send` |
| docs/sharing.md | the widget store, and sharing a file as a link or QR code |
| docs/versions.md | what each owl version added |

## Mistakes to avoid

OWL looks like QML and JavaScript but isn't either; these are what code written from habit gets wrong:

- No exponent literals: `1e9` is "unknown unit 'e'", since letters after a number are a unit (`5m`, `3x2`). Write `1000000000`.
- A `let` reads only the lets above it (triggers and handlers can read any). Order them so each comes after what it uses.
- A `let` inside a node is for its children, not for that node's own properties: `Column { let k = 3; width: k }` is "unknown name 'k'". Put it on the parent.
- An arrow function can't be kept in a `let` and called: `let f = x => x * 2` then `f(3)` is "unknown function 'f'". Write the expression out where it's used, or make a component for repeated nodes.
- Words like `start` and `title` stand alone, as in `align: start`; in an expression they're names, so `align: mine ? start : end` is "'start' isn't a value here". Quote them there: `align: mine ? "start" : "end"`, `style: big ? "title" : "body"`. Colors are values, so `color: hot ? red : foreground` works as it is.
- Handlers and `animate` go inside a component, not where it's used: `Chip { label: "a"; onTap: n = 1 }` is an error. Wrap the use instead, `Box { onTap: n = 1; Chip { label: "a" } }`.
- `on x { }` doesn't run for x's first value, so to save what a source fetched the first time, write `on x first { }` (owl 9).
- `Progress`, `Ring`, `Slider` and other nodes with a default size lose it once they have `width`, `height`, `size`, `fill` or `weight`: `Progress { fill: width }` is zero high. A Progress is full width already; leave `fill` off, or give it a `height`.
- In a handler each statement sees the writes before it: read what a later statement needs before changing it.
- Never write an API key, a token or a password into the file: keep it in `state key = null secret`, asked for with `Input { password: true }`, and write it out in each request's headers, `{Authorization: "Bearer ${key}"}`, or as `enc(key)` in its URL; a `let` can't hold it (owl 12, see Tokens and passwords in docs/handlers.md).
- `random()` and `shuffle(list)` only work in handlers and a state's first value; for values that look random but stay put, use `hash` (see Chance in docs/expressions.md).

## Examples

Read the one closest to what you're making before you start. Files at the top of this folder are in use
already (on home, or as the drawer and the terminal key bar); the rest are in `examples/`, off home until you move one up into this folder. Examples without `meta { owl: … }` are written for owl 1,
so they don't use what came later, like `if` in handlers or a Row's Texts as wide as their text; the
docs say what's current.

| to learn | read |
|---|---|
| a shell command shown as text | `examples/10-clock` (clock, theme name), `examples/30-disk` (df, a bar) |
| a number over time | `examples/20-system` (history, Sparkline, requires), `examples/93-latency` (stream: one long-running command) |
| JSON and lists from commands | `examples/80-system-info` (json), `examples/70-processes` (lines into maps, a bar per row), `examples/94-scripts`, `examples/96-ai-usage` (jq in a `"""` command), `examples/60-agents` (for, if, Button) |
| the web, no Linux needed | `examples/50-weather` (http), `examples/86-whats-new` (a screen behind the tile), `10-today` (weather, Input for a city, persist) |
| the phone's own data | `examples/40-battery` (Ring), `examples/85-quick` (alarm, modes, torch), `examples/97-now-playing` (media, album art), `examples/98-notifications`, `examples/99-volume` (Slider), `20-fetch` (with or without Linux) |
| state, buttons and timers | `examples/91-focus` (persist, `at`, notify), `examples/87-breathe` (a session paced by the clock), `examples/88-notes` (Input, a saved list) |
| drawing | `examples/90-analog-clock` (Canvas), `50-koi` (pixel art, taps), `60-owl` (sprites, a pet that keeps its state), `examples/92-aurora` and `examples/89-lava` (Shader) |
| faces, a whole page | `examples/00-start` (frost, Grid, spans), `examples/01-minimal`, `examples/02-pixel`, `examples/03-matrix` |
| panels and the prompt bar | `drawer.owl` (lazy lists, search with rank, Popup), `examples/65-ai-bar` (android.agents, ask, open) |
| the terminal key bar | `keybar.owl`, `examples/keybar-layers` (latch, layers), `examples/keybar-vim`, `examples/keybar-gamepad`, `examples/keybar-commands` (typeText) |
| a live terminal | `45-terminal` |
| an app that sends | `examples/ntfy` (two views with `back`, a source whose URL takes a state, `post` with a JSON body, and what it answers shown at once), `examples/github-inbox` (a token kept as a `secret`, `post` with `method: "PATCH"`) |

## Limits

A widget has at most 500 nodes (a page 1000), 2000 drawing calls per frame, lists of 1000
items, 32 images and 128 SVGs on show at once (a view with more draws only those, and says so
in `.errors/`), and a step budget per update. A widget that draws slowly animates at
half rate. Errors never take the home screen down: the failing value shows `…`
and the message goes to `.errors/`.

## Tips

- One small, focused widget per file; name files `NN-name.owl` to order them.
- Test commands in a terminal first. For several values, print JSON and use `json`.
- The phone is portrait: 6 columns, ~12 rows. A full-width widget is `6xN`,
  half-width `3xN`. Set `minSize` to what the layout still fits in. For the whole screen, write a face.
- Use `weight: 1` to fill leftover space and `fill: width` to stretch across.
- A `Text` without `width`, `weight` or `fill` takes the full width, except in a `Row` or `Flow` since owl 10,
  where it's as wide as its text, or in an unsized Column in one since owl 11. Before owl 10, give the Texts next to a `weight: 1` one a `width`, or the
  weighted one is squeezed to nothing.
- Look at the examples for patterns: Examples, above, says which one shows what.
