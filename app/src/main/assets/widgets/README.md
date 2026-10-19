# Home-screen widgets and apps

Each `*.owl` file in this folder is a widget on the Android home screen,
written in OWL, a small declarative language in the spirit of QML. Its first
word says what it is: `widget "name" { }` is a home-screen tile, `face "name" { }` a face, a whole home page
left of your first one (see Faces), and `app "name" { }` an app in the app drawer that opens full screen
(see Apps). Everything below is about widgets unless it says otherwise; faces and apps are written
the same way. Save a file and it appears within a second. Then run `owl-check <file>`:

```
$ owl-check 50-memory.owl
50-memory.owl: compiles
  preview: ~/.config/omalauncher/widgets/.preview/50-memory.owl.png (3x4 cells, 528x712 px, with live data); look at it to check the layout
```

A widget with a `screen { }` (see Screens) gets a second picture, of the screen as it opens. An
app has no tile: its picture is the `screen:` line, of the whole view at the phone's size.

It waits until the launcher has loaded the file as saved, prints compile errors
and problems found while running, and exits 1 if there are any. The preview is
the tile as it looks on the home screen, at its size there, after its sources
have loaded: open it to see whether text fits and the layout works, since you
can't see the phone. Terminal tiles aren't previewed. Errors also go to
`.errors/<file>.txt`, and the tile shows them.

Widgets sit on a 6-column grid of roughly square cells (a phone has about 12
rows), bordered like Omarchy windows and colored by the current Omarchy
theme. Users move and resize them on the phone.

`examples/` holds more widgets (clocks, system stats, processes, shaders, ...).
They aren't on the home screen; read them for patterns, or move one up into
this folder to add it.

```owl
widget "memory" {
  page: 1                 // home page, 1-based
  order: 2                // placement order on the page (lower = placed first)
  size: 3x4               // preferred size in grid cells: columns x rows
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

## Declarations (top of the widget)

| declaration | meaning |
|---|---|
| `source x = cmd("...") every 5s` | runs a bash command in this Arch system; `x` is its output (stdout, trimmed). Add `json` to parse JSON output, `timeout 30s` to allow more than 10 s, `status` to get `{out, err, code, ok}` instead (see below), `optional` to let the widget work without Linux (see Without Linux). Default interval: 60 s |
| `source x = stream("...")` | a long-running command; every line it prints becomes the new value. Add `json` for JSON lines. At most 4 different streams run at once |
| `source x = http("https://...") every 10m` | an HTTP GET from Android, no curl or shell needed. Takes `json`, `timeout` and `status` like cmd (with `status`, `code` is the HTTP status and `ok` means 2xx), and optionally headers: `http(url, {Accept: "application/json"})`. The response is capped at 1 MB and the interval at 5 s or more. Prefer it over `cmd("curl ...")`. A URL can hold values, through `enc()` (see Values in URLs); it's fetched again as soon as it changes, and a `null` URL pauses the source, keeping its last value: `http(tab == "news" ? "https://..." : null)` fetches only while that tab shows. A failed fetch (offline, an error status, a rate limit) is tried again after 30 s |
| `source x = file("~/.cache/x.json")` | a file in this system, read again whenever it changes (no polling, no shell). Add `json` to parse it. A missing file reads as `""`; at most 1 MB is read. Prefer it over `cmd("cat ...") every 5s` (owl 4) |
| `source now = clock every 1s` | Unix time in seconds, with no shell involved. Use `fmtTime(now, "HH:mm")` |
| `source bat = android.battery` | `{level, charging, status, temp, voltage, health, plugged, technology, cycles}` from Android, updated as it changes (`temp` in °C, `voltage` in V; `health`, `plugged`, `technology` and `cycles` are null when the phone doesn't say) |
| `source dev = android.device` | the phone: `{model, maker, android, sdk, soc}` (the Linux side can't see these) |
| `source sys = android.system every 5s` | the phone's state, polled: `{kernel, uptime, memory, storage, cpu, display, build, codename, patch, locale, timezone, launcher, theme, apps}`. `uptime` is in seconds; `memory` is `{total, used, available}` and `storage` `{total, used, free}`, in bytes; `cpu` is `{soc, maker, hardware, cores, abi, maxMhz}`; `display` is `{width, height, hz, dpi}`; `patch` is the security patch date, `launcher` this launcher's version, `theme` the Omarchy theme's name and `apps` how many Android apps are installed. See `20-fetch.owl` (owl 5) |
| `source m = android.media` | what's playing in any app on the phone: `{available, playing, title, artist, album, app, package, position, duration, art}`, updated as it changes (`position` and `duration` in seconds; `art` goes in `Image { src: m.art }`). `available` is false until the user grants notification access; `media(...)` opens that setting then. See `examples/97-now-playing.owl` |
| `source n = android.notifications` | the phone's notifications, newest first, updated as they come and go: `{available, count, items}`, each item `{key, app, package, profile, icon, title, text, time, clearable}` (`time` in Unix seconds; `profile` is `work` or `clone` for a notification from a work profile or a cloned app, else null; `icon` is the app's icon for `Image { src: i.icon }`, from that profile, owl 7). Ongoing ones (music, downloads) are left out. Needs the same notification access as media. See `examples/98-notifications.owl` (owl 4) |
| `source v = android.volume` | the media volume: `{level, muted, steps}` (`level` in percent, `steps` how many the phone has), updated as it changes. Set it with `volume(...)` (owl 4) |
| `source a = android.alarm` | the next alarm set in any clock app: `{set, time, app}`, updated as it changes. `time` is in Unix seconds (`fmtTime(a.time, "EEE HH:mm")`, or `at a.time`), `app` the clock app's package (`androidApp(a.app)`); both null when no alarm is set (owl 7) |
| `source a = android.apps` | the phone's launchable apps, updated as they're installed, removed and opened: a list of `{key, name, package, component, profile, system, icon, score, last}`. `key` is unique even with the same app in a work profile or a clone, and `androidApp(a.key)` opens that copy; `profile` is `work` or `clone` for those, else null; `icon` goes in `Image { src: a.icon }`. `score` and `last` (Unix seconds, null if never opened) are what the search ranks by: opens fade by half every two weeks, and `androidApp(...)` counts like opening from the drawer (owl 8) |
| `source ag = android.agents` | the coding agents installed in Linux: `{default, installed}`, each agent `{bin, name, description, command, key, icon}`, updated as they change. `default` is the one the prompt bar asks and `ask(text)` sends to, null with none installed; `open(a.key)` starts one without a prompt, and `icon` is its logo (owl 10) |
| `source r = android.recent` | the apps in the recent strip above the prompt bar, last opened first: a list of `{key, kind, name, icon, score, last}`, Android and Linux apps, owl apps and shortcuts alike (`kind` is `android`, `linux`, `owl` or `shortcut`). `open(r.key)` opens one, `icon` goes in `Image { src: r.icon }`, and `score` and `last` are as in `android.apps`. Like the strip, it reorders once home is out of sight, not under the finger that tapped (owl 10) |
| `source m = android.modes` | the phone's modes: `{dnd, saver, ringer, torch}`, updated as they change. `dnd` is Do Not Disturb in any form, `saver` battery saver, `ringer` is `normal`, `vibrate` or `silent`, and `torch` is null on a phone without a flash. Switch the torch with `torch(...)` (owl 7) |
| `state x = 0` | a value that handlers can change. Add `persist` to keep it across restarts: `state endsAt = 0 persist` |
| `let x = expr` | a derived value, recomputed only when what it reads changes. It can use sources, state and earlier lets |
| `let h = history(x, 60)` | the last 60 values of `x`, sampled whenever a source updates (for Sparkline). With `persist` it survives restarts, unless it's older than it would take to fill |
| `let p = prev(x)` | the previous value of `x`, for rates: `let rate = (bytes - p) / 5` |
| `component Name(a, b = 1) { ... }` | a reusable node; use it as `Name { a: 1 }` |
| `asset logo = base64"iVBOR..."` | an embedded image (under 2 MB) for `Image { src: logo }`, or a font for `font: logo` (owl 10, see Fonts), so the widget is one self-contained file |
| `requires { pacman: ["jq", "curl"] }` | packages the widget's commands need; see Dependencies |
| `meta { description: "..."; version: "1.0"; license: "MIT"; category: "news" }` | needed to publish to the widget store (see Sharing) |
| `meta { owl: 10 }` | the language version the widget needs (this launcher has 10; the default is 1). Older launchers say "update the launcher" instead of failing on what they don't know. `Terminal` needs 2, `sprite` 3, triggers, `file`, `Slider`, `Input`, `android.notifications`, `android.volume`, `volume` and `notification` need 4, and `screen { }`, `screen()`, `close()`, `screenWidth` and `screenHeight` need 5, `Input`'s `focus`, `sh()` and `corners` 6, and `android.alarm`, `android.modes`, `torch`, `share` and `haptic` 7, and `app "…" { }` files, `android.apps`, `enc`, `post`, images from the web, `sound` and `screenInset` 8, and `parseTime`, `fmtAgo`, `fmtNum`, `random`, `shuffle`, `hash`, `fmtTime`'s zone and language, `set`, `remove`, `merge`, `reduce`, `scan`, `groupBy`, `toJson`, `capitalize`, `asin`, `acos`, `flatten`'s depth, `on … first`, and `if` and `let` in handlers 9, and `face "…" { }` files, `onLongPress`, Text's `spans`, `fontWeight` and `letterSpacing`, gradient backgrounds, `frost`, fonts of a widget's own and Canvas `text`'s `font`, `fontWeight`, `italic` and `letterSpacing`, `android.agents`, `android.recent`, `open`, `ask`, `Popup` and Input's `underline` 10; using them without declaring it is an error |
| `onTap: ...` | runs when the tile (not a button inside it) is tapped |
| `screen { ... }` | a full-screen view of the widget, opened with `screen()`; see Screens (owl 5) |
| `every 10s { ... }`, `at t { ... }`, `when c { ... }`, `on x { ... }` | statements that run without a tap; see Triggers |
| `padding: 12` / `background: ...` | the tile's padding (dp) and background |

Sources only run while their page (or a neighbour) is on screen, and
identical commands in several widgets run once. A few commands run at the same
time, so a slow one doesn't hold up other widgets.

stderr never mixes into the output, so a warning can't break `json`. A
timeout, or a non-zero exit that printed to stderr, goes to `.errors/`. A quiet
non-zero exit (grep finding nothing) doesn't. To handle failures in the widget,
add `status`: then `x.out` is the output (parsed with `json`), `x.err` is stderr,
`x.code` is the exit code (124 means it timed out), and `x.ok` is `code == 0`.

```owl
source w = cmd("curl -sf wttr.in/?format=j1") every 30m json timeout 20s status
Text { text: w == null ? "…" : w.ok ? "${w.out.current_condition[0].temp_C}°" : "offline" }
``` Keep commands fast. A command
can depend on state (`cmd("curl wttr.in/${sh(city)}")`) and runs again as soon as the
value changes (at most once a second), without waiting for its interval or a `refresh()`.

## Values in commands

Put a value into a command (`cmd`, `stream`, `run`, `terminal`, `Terminal`) with `sh(value)`,
on its own and outside any quotes. `sh()` single-quotes the value, so it stays one word and
can't run as code, whatever text it holds (owl 6):

```
onTap: run("notify-send ${sh(title)}")        // right
onTap: run("notify-send '${title}'")          // wrong: a ' in title ends the quote
onTap: run("notify-send \"${sh(title)}\"")    // wrong: sh() inside quotes doesn't hold
```

Work on the value inside `sh()`: `${sh(upper(title))}`. Numbers from `int`, `round`, `floor`,
`ceil`, `abs`, `fixed`, `len`, `count`, `sum`, `min`, `max` and `clamp` need no `sh()`. A command
with a heredoc (`<<`) can't take values. The widget store only takes commands built this way;
one shared as a link shows a warning instead.

## Values in URLs

The same holds for `http(...)`: put a value into its URL with `enc(value)`, which percent-encodes
it, so a `/`, `?`, `&` or `#` in it can't change the path or add parameters, and keep the scheme
and host fixed text (owl 8):

```
source coin = http("https://api.example.com/coins/${enc(id)}?days=${int(days)}") json   // right
source coin = http("https://api.example.com/coins/${id}") json                          // wrong: not through enc()
source coin = http("https://${enc(host)}/coins") json                                   // wrong: the host is a value
```

The store lists such a URL with `‹value›` where each value goes, and one built any other way as
fetching "a URL put together while the widget runs". `owl-check` warns about those, with the line and
the fix to make. A fixed base in a `let` counts as fixed text: `let api = "https://api.example.com"`, then
`"${api}/coins/${enc(id)}"`. A value that is a path with `/` in it can't go through `enc()` (it would
encode the slashes): write the fixed parts of the path out and `enc()` only what goes between them.

## Triggers

Statements that run on their own, written like handlers, at the top of the widget (owl 4):

| trigger | runs |
|---|---|
| `every 10s { ... }` | every 10 seconds while the page is live (on screen or next to it), like a source. At least 1 s |
| `at endsAt { ... }` | when the clock reaches `endsAt`, a Unix time in seconds, even while the page is off screen. Again for each new time; 0 or null means none. A time that passed while the launcher wasn't running runs at the next start, so set it to 0 in the statements. While the phone sleeps it can run a little late, when it wakes |
| `when cond { ... }` | each time `cond` turns true, including when it's already true as the widget starts |
| `on x { ... }` | each time `x` changes, with `value` (the new one) and `old`. Not for its first value, and null (loading) doesn't count as a change. `on x first { ... }` runs for the first value too, with `old` null: for saving what a source fetched, from the first fetch on (owl 9) |

```owl
widget "focus" {
  meta { owl: 4 }
  source now = clock every 1s
  source bat = android.battery
  state endsAt = 0 persist
  state slide = 0
  every 8s { slide = (slide + 1) % 3 }
  at endsAt { notify("focus", "time's up"); endsAt = 0 }
  when bat.level < 15 && !bat.charging { notify("battery", "${bat.level}% left") }
  on bat.charging { notify("battery", value ? "charging" : "unplugged") }
  Button { text: endsAt > now ? "${endsAt - now}s" : "start"; onTap: endsAt = now + 25 * 60 }
}
```

`when` and `on` react to what they read: a source only updates while its page is live, so
for a deadline use `at`, which doesn't need one. A trigger that runs more than 10 times a
second is held back and reported (one that changes what it watches would otherwise loop).

## Expressions

- Numbers `12`, `0.5`, `3x2`; durations `500ms`, `2s`, `5m`, `1h` (in seconds). Strings are
  `"hi ${name}"` (interpolated) or `'raw $text'` (handy for shell commands).
  Also `"""multi-line"""`, `true`/`false`/`null`, lists `[1, 2]` and maps `{a: 1}`.
- Operators: `+ - * / %`, `== != < <= > >=`, `&& || !`, `a ?? b` (b if a is null), `c ? a : b`.
- Paths: `info.list[0].name` or `info.list.0.name`. Anything missing is `null`, never an error.
- Command output is text, but numeric text acts like a number: `used > 80` works on `"81"`.
- `null` shows as `…` in text, meaning the value hasn't loaded yet. Math and formatting
  functions pass `null` through.
- Methods are functions: `x.map(f)` is `map(x, f)`. Functions take arrow functions:
  `procs.filter(p => p.cpu > 1).sortBy(p => -p.cpu).take(5)`.

| functions | |
|---|---|
| numbers | `min max clamp(x, lo, hi) lerp(a, b, t) round(x, digits) floor ceil abs sqrt pow sin cos tan asin acos atan2 rad deg sign num(x, default) int` |
| text | `sh(x)` (one shell word, see Values in commands), `enc(x)` (one URL component, see Values in URLs) `str len trim upper lower capitalize lines words split(s, sep) join(list, sep) replace(s, a, b) contains startsWith endsWith sub(s, from, to) pad(x, width, char) fixed(x, digits)` |
| format | `fmtBytes(n)` → `1.5G`, `fmtDuration(seconds)` → `2h 5m`, `fmtTime(seconds, "EEE HH:mm")`, `fmtAgo(t, now)` → `5m`, `fmtNum(n)` → `12,345`; see Dates and numbers |
| weather | `weatherIcon(code, night)`: omarchy's weather glyph for a wttr.in `weatherCode` (show it with `font: icon`) |
| parsing | `match(s, regex)` (groups of the first match), `matchAll(s, regex)`, `parseJson(s)`, `toJson(x)` (JSON text), `parseTime(s)` (a date in text as Unix seconds) |
| chance | `random()`, `random(n)`, `random(a, b)`, `shuffle(list)`, only in handlers and a state's first value; `hash(...)` and `shuffle(list, seed)` anywhere. See Chance |
| lists | `range(n)` or `range(a, b, step)`, `map filter find any all count sum avg sort sortBy reverse take drop first last indexOf unique`, `flatten(list, depth)` (depth 1 unless given), `keys values entries get(obj, key, default)`; see Changing lists and maps |
| colors | theme names `accent foreground background muted surface border red green yellow blue magenta cyan orange` plus `bright_*`, `color0`…`color15`; `#7aa2f7`; `rgb(r, g, b, a)`, `hsl(h, s, l, a)`, `alpha(c, 0.5)`, `mix(a, b, t)` |
| gradients | `linear(x0, y0, x1, y1, [colors])`, `radial(cx, cy, r, [colors])`: fills in Canvas, and backgrounds (owl 10, see Nodes) |

Prefer theme color names: widgets then follow theme changes.

### Dates and numbers

Times are Unix seconds, which `fmtTime(t, pattern)` shows in the phone's time zone and language.
Text from an API or a feed becomes one with `parseTime` (owl 9):

- `parseTime("2026-10-17T10:04:00Z")`: ISO 8601, with or without seconds, fractions and a zone (`Z`,
  `+02`, `+0200`, `+02:00`); also `2026-10-17` alone, a space for the `T`, iCalendar's `20261017T100400Z`,
  and the dates of RSS feeds and HTTP headers, `Sat, 17 Oct 2026 10:04:00 GMT`.
- `parseTime(s, pattern)` reads other forms, with the same letters as `fmtTime`:
  `parseTime("17.10.2026 18:30", "dd.MM.yyyy HH:mm")`. Month and day names are read in English, as APIs
  write them. Without a date it's today (`parseTime(d.time, "HH:mm")`), and without a time, midnight.
- A date without a zone is in the phone's time zone; a third value names another:
  `parseTime("2026-10-17 18:30", null, "Europe/Amsterdam")`.
- Text that isn't a date gives `null`.

`fmtTime` takes a zone and a language after the pattern, both optional (`null` is the phone's):
`fmtTime(t, "HH:mm", "America/New_York")` for a world clock, `fmtTime(t, "EEE d MMM", "UTC", "en")` for a
date that shouldn't move or change language. A zone is a name like `Europe/Berlin` or an offset like
`+05:30`. With `"UTC"` it shows a duration under a day as a clock: `fmtTime(187, "m:ss", "UTC")` → `3:07`.

`fmtAgo(t, now)` is how long ago `t` was, short: `now`, `5m`, `3h`, `2d`, `6w`, `1y`, or `in 10m` for a
time still to come. Take `now` from a clock source (`source now = clock every 1m`), so it stays current.

`fmtNum(x)` groups thousands the phone's way (`12,345` or `12.345`), rounded to whole numbers;
`fmtNum(x, 2)` keeps two digits after the point. A map sets more: `fmtNum(x, {compact: true})` shortens
to `950`, `1.5k`, `12k`, `3.4M`, `2.5B`, `1T` (one digit under 10, none above, unless `digits` says);
`sign: true` puts a `+` before numbers above zero, for changes: `fmtNum(delta, {digits: 1, sign: true})`.

### Changing lists and maps

Values never change in place; these give a changed copy, to assign to a state in a handler (owl 9):

- `set(list, i, v)` with item `i` replaced (counted from the end when negative; an index that isn't in
  the list changes nothing), and `set(map, key, v)` with that field set. `remove(list, i)` and
  `remove(map, key)` leave it out: `onTap: todos = remove(todos, i)`.
- `merge(a, b, ...)` is the maps' fields together, a later one's winning: `merge(item, {done: true})`.
- `reduce(list, (total, x, i) => ..., start)` works through a list to one value, and `scan` gives each
  step on the way, like a running total: `scan(days, (sum, d) => sum + d.spent, 0)`.
- `groupBy(list, x => x.day)` is a map from each key to the items that have it, in the order they came.

### Chance

`random()` is a number from 0 up to 1, `random(n)` a whole number from 0 to n - 1 (an index:
`list[random(len(list))]`), and `random(a, b)` one from a to b, both included (owl 9). `shuffle(list)`
is the list in a random order. They give something new each time, so they work only where a value
is taken once: in handlers and triggers, and as a state's first value. A `let` or a property is worked
out again whenever what it reads changes, and would jump:

```owl
meta { owl: 9 }
state roll = random(1, 6)
state deck = shuffle(range(52))
Button { text: "roll ${roll}"; onTap: roll = random(1, 6) }
```

For values that look random but stay put, like stars in a sky or a scattering of leaves, use
`hash(...)`: a number from 0 up to 1 that is always the same for the same values. `hash(i)` differs for
every `i`, and `hash(seed, i)` with a `seed` state that a tap sets to `random(1000000)` gives a new
pattern for each tap. `shuffle(list, seed)` likewise shuffles the same way for the same seed, in a let:
`let order = shuffle(cards, seed)`.

Persisted values are kept by the launcher (not in the widget file), per file name, and
must be plain data: numbers, text, lists and maps (not colors). Store times as absolute
Unix seconds (`endsAt = now + 25 * 60`), not countdowns, so they're still right after a restart.

The tile's size is available as `cols` and `rows` (grid cells) and `tileWidth` and
`tileHeight` (dp, including the tile padding). They change when the user resizes the
tile, so a widget can pick a layout to fit (`if cols < 3 { ... } else { ... }`). They're
`null` for a moment before the tile is laid out. A widget's own declaration named
`rows` or `cols` takes precedence.

`corners` is the user's corner style as a multiplier, not a radius: 0 (edge, square), 0.5
(soft) or 1 (round). Multiply the radius a shape would have on round by it: `radius: 8 *
corners` for cards and album art, `3 * corners` for small chips, and the same in Canvas
(`rect(x, y, w, h, radius: 8 * corners)`). The tile, buttons and bordered elements without a
`radius` follow it on their own; a fixed `radius`, a clipped Box, an Image or a Canvas shape
doesn't. It needs `meta { owl: 6 }`.

## Nodes

`Name { prop: value; ...children }`. Structure: `if cond { } else { }`,
`for x in list { }`, `for x, i in list key x.id { }` (a key keeps rows stable
when lists reorder), and `let x = ...` inside any node.

| node | properties |
|---|---|
| `Column` | `gap` (dp, default 4), `align: start/center/end`, `justify: start/center/end/spaceBetween/spaceAround/spaceEvenly`, `scroll: true` |
| `Row` | `gap` (8), `align: top/center/bottom`, `justify`, `wrap: true` |
| `Flow` | a Row that wraps to new lines |
| `Box` | stacks children on top of each other; `align: center/top/bottom/start/end/topStart/topEnd/bottomStart/bottomEnd` |
| `Grid` | `columns` (2), `gap`; children fill cells left to right |
| `Text` | `text`, `style: huge/title/body/small/muted/label`, `color`, `fontSize`, `bold`, `italic`, `font: mono/sans/serif/icon` (`icon` draws the Nerd Font weather glyphs U+E300–E3E3, plus 󰄬 U+F012C and 󰅖 U+F0156) or since owl 10 a font of the widget's own (see Fonts), `align: start/center/end`, `maxLines`; since owl 10 `fontWeight` (100 thin to 900 black; the font shows the nearest weight it has), `letterSpacing` (sp, negative to tighten) and `spans` (see Text in pieces) |
| `Button` | `text`, `color`, `onTap` |
| `Progress` | `value`, `max` (100), `color`, `track` |
| `Ring` | circular progress: `value`, `max` (1), `color`, `track`, `thickness` (6), `start`/`sweep` (degrees; `start: 225; sweep: 270` makes a gauge). 64 dp unless sized |
| `Sparkline` | `values` (a list, usually a `history`), `min` (0), `max`, `color`, `area: true` or a color, `thickness` |
| `Image` | `src`: an `asset`, a path in this system (`"~/pics/a.png"`), an `https://` URL (owl 8, see Images from the web) or an Android app's icon (`"app:org.pkg"`, as the drawer shows it, badge included; a silhouette in the foreground color with monochrome icons; `"app:org.pkg@work"` and `"@clone"` ask for the copy in a work profile or the clone, owl 7), or any app's icon by its `android.recent` key (`"icon:l:foot"`, Linux and owl apps and shortcuts too; `android.recent` and `android.agents` give it as `icon`), `fit: contain/cover/fill`, `tint` |
| `Canvas` | free drawing, below. Fills the space unless sized |
| `Shader` | an AGSL shader, below. Fills the space unless sized |
| `Spacer` | empty space: `size`, `width`, `height` or `weight` |
| `Slider` | a horizontal slider: `value`, `min` (0), `max` (100), `step`, `color`, `track`, `live`. `onChange` gets the chosen `value` when the finger lifts, or at every step while dragging with `live: true`. Full width and 28 dp tall unless sized (owl 4) |
| `Input` | a one-line text field: `text` (what it shows; replaces what's typed when it changes), `placeholder`, `color`, `fontSize`, `clear: true` (empty it after submitting), `underline: false` (no line under it, for a field inside a frame of its own, owl 10), `focus: true` (focus it and raise the keyboard when the screen opens, or when `focus` turns true; only in a `screen { }`, owl 6). `onChange` gets the text as `value` as it's typed, `onSubmit` when the keyboard's send key is pressed (owl 4) |
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
- handlers: `onTap` (with `x`, `y`) and `onDrag` (with `dx`, `dy`, `x`, `y`); `Slider` and `Input` also take
  `onChange`, and `Input` `onSubmit`, both with `value`; `Popup` takes `onDismiss`. In pages, apps and a widget's `screen { }`,
  `onLongPress` (with `x`, `y`, owl 10); on a tile, long-press belongs to the home screen, which uses it
  to move and resize tiles
- `animate prop: spring()` or `tween(300ms, "linear"/"in"/"out"/"ease")`, for `value`,
  `opacity`, `rotation`, `scale`, `color` or `background`. For springs:
  `spring(damping, stiffness)`

### Text in pieces

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

### Fonts

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

### Gradient backgrounds

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

### Frosted glass

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

### Text in a Row

Since owl 10 a Text in a Row or Flow is as wide as its text, like a Button: labels, chips and values sit side by
side without a `width`, and a `weight: 1` neighbour takes what's left. Before, it took the whole width unless
sized, and widgets that declare an older owl keep that. In a Column a Text still takes the whole width.

`opacity`, `rotation`, `scale`, `offset`, colors and the contents of Canvas and
Shader apply at draw time. Changing them, even every frame, costs no layout.

Handlers assign state and run actions, separated by `;` or newlines. An action whose first
value hasn't loaded yet (`…`) does nothing. After an action,
the widget that ran it refreshes its sources; other widgets keep their own intervals:
`onTap: { count = count + 1; run("notify-send hi") }`

Since owl 9 a handler can also choose with `if`, and name a value with `let` for the statements after it:

```owl
meta { owl: 9 }
state score = 0
state best = 0
state sound = true
onTap: {
  let gained = x > tileWidth / 2 ? 2 : 1
  score = score + gained
  if score > best {
    best = score
    if sound { sound("https://example.com/win.ogg") }
  } else if gained == 2 {
    haptic()
  }
}
Text { text: "${score} (best ${best})" }
```

Statements run in order and state writes take effect at once, so a `let` taken first keeps a value as it
was before the handler changed it: `let was = food; food = food + 1`. A `let` is seen by the statements
after it in its block (and blocks inside them), and needs a name not used for a state, source or let.

| action | does |
|---|---|
| `terminal("btop")` | opens the command in a terminal window; `terminal(cmd, "title")` |
| `app("gtk3-demo")` | starts a graphical Linux app |
| `run("cmd")` | runs in the background (on its own shell, never retried), then refreshes this widget |
| `x = run("cmd")` | the same, and puts the command's output into the state `x` when it finishes |
| `androidApp("org.pkg")` | opens an Android app by package name, or by an `android.apps` key to open that copy in a work profile or clone |
| `open(r.key)` | opens an app from `android.recent` or an agent from `android.agents` by its `key` (an `android.apps` key works too), as tapping it in the drawer would, so it counts as opened (owl 10) |
| `ask(text)` | opens a terminal with the default agent answering `text`, as sending it from the prompt bar does; `ask(text, "codex")` asks that installed agent. Without one installed it opens the agents settings. The store's reviewer sees what a widget asks (owl 10) |
| `copy(text)` | copies text to the Android clipboard |
| `notify("title", "text")` | posts an Android notification (if the launcher may post them) |
| `post("https://...", body, headers)` | sends an HTTP POST from Android: a map or list `body` goes as JSON, text as it is; `headers` is a map like `http(...)`'s. `x = post(...)` puts the answer into the state `x` when it arrives: `{code, ok, out}`, with `out` parsed when it's JSON. Never retried; a null URL sends nothing. When it succeeds, the widget's sources run again. See Sending (owl 8) |
| `url("https://...")` | opens in the Android browser. Other links open in the app that takes them: `tel:` the dialer, `geo:` maps, `mailto:` email |
| `share(text)` | opens Android's share sheet with the text (owl 7) |
| `haptic()` | a short tap of haptic feedback, if the user has touch feedback on (owl 7) |
| `sound("https://…/a.mp3")` | plays a sound once, in place of the one playing; `sound("stop")` stops it. Takes a URL, an asset or a file, like `Image`; see Sounds (owl 8) |
| `torch(true)` | turns the torch on or off; also `torch("toggle")`. Does nothing on a phone without a flash, or while the camera app holds it (owl 7) |
| `launcher("style")` | opens a launcher screen: `style` (theme, background, home screen, terminal; `themes` too), `agents`, `store`, `drawer` or `linux` (the sheet that installs Linux) |
| `refresh()` | re-runs this widget's sources now |
| `screen()` / `close()` | opens the widget's `screen { }` full screen, or closes it (owl 5) |
| `media("toggle")` | controls what's playing: `toggle`, `play`, `pause`, `next`, `previous`, or `open` (brings up the player app) |
| `volume(50)` | sets the media volume in percent; or `volume("up")`, `"down"`, `"mute"`, `"unmute"`, `"toggle"` (owl 4) |
| `notification("open", key)` | opens a notification from `android.notifications` as tapping it in the shade would; `"dismiss"` clears it. Without notification access, opens the setting that grants it (owl 4) |

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

## Sending

`http(...)` sources only read. For the rest of a web API (signing in, saving a change) a handler sends a
POST with `post(url, body, headers)` (owl 8). It goes out from Android, so it needs no Linux, once per
tap. When the server accepts it (a 2xx answer), the widget's sources run again, so what changed shows:

```owl
app "todo" {
  meta { owl: 8 }
  state added = null
  source items = http("https://api.example.com/items") json
  on added { notify("todo", value.ok ? "added" : "couldn't add: ${value.code}") }
  Column {
    Input { placeholder: "add…"; clear: true; onSubmit: added = post("https://api.example.com/items", {title: value}) }
    for i in items ?? [] { Text { text: i.title } }
  }
}
```

A `post` that only sometimes goes out goes in an `if` (owl 9):
`every 5s { if code != "" { tok = post("https://…/token", {code: code}) } }` polls only while there is a
code. Before owl 9 the same took a null URL, which sends nothing. The URLs are listed for review like `http(...)`'s, as `http POST https://…`, under the same rule:
keep the server fixed and put values into the URL with `enc(...)`.

## Sounds

`sound(...)` plays a short clip: a chime when a timer ends, a voice reading the next step (owl 8). It takes
an `https://` URL (downloaded and kept like an image from the web, and listed for review the same way),
an `asset` or a file in this system; MP3, OGG, WAV and M4A work. It plays at the media volume, and
music from other apps quietens while it does.

One sound plays at a time per file: a new one replaces it. It stops when the app's window or the screen
it played in closes. On the home screen only a tap plays one; a trigger (`every`, `at`, `when`, `on`)
plays sounds only while the app or the widget's screen is open.

```owl
app "kitchen timer" {
  meta { owl: 8 }
  source now = clock every 1s
  state endsAt = 0
  at endsAt { sound("https://actions.google.com/sounds/v1/alarms/beep_short.ogg"); endsAt = 0 }
  Button { text: endsAt > now ? "${endsAt - now}s" : "3 minutes"; onTap: endsAt = now + 180 }
}
```

## Screens

A tile is small. For more, a widget can have one full-screen view, opened by a tap: a forecast
behind a weather tile, all processes behind a CPU tile, a log behind a build status (owl 5):

```owl
widget "weather" {
  meta { owl: 5 }
  source wx = http("https://wttr.in/?format=j1") every 30m json
  let days = wx.weather ?? []
  onTap: screen()

  Text { text: "${wx.current_condition[0].temp_C}°"; style: title }

  screen {
    title: "weather"
    Column {
      scroll: true
      for d in days key d.date {
        Row { Text { text: d.date; weight: 1 }  Text { text: "${d.maxtempC}°"; width: 48 } }
      }
      Button { text: "close"; onTap: close() }
    }
  }
}
```

- It opens in the launcher's window frame, titled with `title` (default: the widget's name),
  with a ✕. Back, Home and the ✕ close it; so does `close()`.
- It's the same widget: it reads the same sources, state, lets and components as the tile,
  and a tap that changes state there changes the tile too. While it's open its sources keep
  running, whichever page the tile is on.
- It holds the same nodes as a tile. It doesn't scroll by itself: put the content in a
  `Column { scroll: true }`. `screenWidth` and `screenHeight` are its size in dp, known from its
  first frame. The tile can read them too, but there they may be `null`: use `screenWidth ?? 0`.
- One screen per widget, with up to 1000 nodes (the tile still has its own 500). For views
  within it, use a state and `if`: `state tab = "hours"`, buttons that set it, and
  `if tab == "hours" { ... } else { ... }`.

Screens are for showing more, not for building apps: there's no navigation between screens, no
lists that load as they scroll, and only the nodes above (no maps, web pages or video).

## Faces

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

## Apps

A file can also be an app instead of a widget: a screen of its own, listed in the app drawer.
The nodes at the top are the whole view, with the room and limits of a `screen { }`, and it takes
the same declarations as a widget (sources, state, lets, components, triggers, `requires`, `meta`).
There's no tile, so no `size`, `minSize`, `page`, `order`, `padding`, `background`, `onTap` or
`screen { }`; the properties are `title`, `icon`, `back` (with its `onBack`) and `edge`, and `close()` closes it. Apps need owl 8 and
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

## Canvas

Drawing calls in dp, with `width`, `height` and `time` available. Angles are
degrees, with 0 at 12 o'clock, running clockwise.

```owl
Canvas {
  let r = min(width, height) / 2 - 4
  circle(width / 2, height / 2, r, stroke: border, width: 2)
  arc(width / 2, height / 2, r, 0, load * 3.6, stroke: accent, width: 6, cap: round)
  for i in range(12) {
    rotate(i * 30, width / 2, height / 2) { line(width / 2, 6, width / 2, 12, stroke: muted) }
  }
  text("${load}%", width / 2, height / 2, size: 18, align: center, color: foreground)
}
```

| call | |
|---|---|
| `rect(x, y, w, h)` | `radius:` |
| `circle(cx, cy, r)` | |
| `arc(cx, cy, r, start, sweep)` | filled arcs are pie slices |
| `line(x1, y1, x2, y2)` | |
| `path(points)` | points are `[x, y]` or `{x, y}`; `closed: true` |
| `text(s, x, y)` | `size:`, `color:`, `align: start/center/end`, `bold:`; y is the text's middle. Since owl 10 also `font:`, `fontWeight:`, `italic:` and `letterSpacing:` as on a Text, and `s` can be a list of spans |
| `image(src, x, y, w, h)` | `src` as for `Image`; `color:` paints an SVG in one color |
| `sprite(rows, x, y, pixel)` | pixel art (owl 3): `rows` is a list of strings, or one `"""` string of lines, with one letter per art pixel and `.` for empty. `palette: {b: accent, e: background}` colors each letter; letters it doesn't name aren't drawn, so one art can be drawn in parts that move separately. Without a palette every letter takes `fill`/`color`. `pixel` is the size of an art pixel in dp; edges land on whole screen pixels, so no seams show |
| `translate(x, y) { }`, `rotate(deg, cx, cy) { }`, `scale(sx, sy, cx, cy) { }`, `clip(x, y, w, h) { }`, `layer(alpha) { }` | apply to the calls inside |

Styles: `fill:` (a color or gradient), `stroke:`, `width:` (stroke width),
`cap: round/butt/square`. Without either, a shape is filled with `color:` or
the foreground. A widget that reads `time` animates every frame while it's on
screen, and only while it reads it: a `time` under an `if` that's false, like a
backdrop drawn only while music plays, costs nothing (older launchers
animate whenever `time` appears at all).

## Shader

A GPU fragment shader in AGSL (Android 13+; older phones show `fallback`).
Write `half4 main(float2 p)`. `size` (pixels) and `time` (seconds) are
declared for you. Declare your own uniforms and pass them in `uniforms`:
numbers become `float`, lists become `float2`–`float4`, colors become `half4`.

```owl
Shader {
  fallback: surface
  uniforms: { tint: accent, speed: 0.5 }
  code: """
    uniform half4 tint;
    uniform float speed;
    half4 main(float2 p) {
      float w = sin(p.x / size.x * 6.0 + time * speed) * 0.5 + 0.5;
      return half4(tint.rgb * w, 1.0);
    }
  """
}
```

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
  outside it, Back or another page lets go. The ⌨ button toggles the soft keyboard.
- The terminal keeps running while its page is off screen. It ends when the widget
  is removed or its file changes. When `command` exits, the tile offers a restart;
  use `"btop; exec bash"` to fall back to a shell.
- `padding: 0` lets the terminal fill the tile's frame.

## From scripts

Anything in this system (a script, a cron job, a pacman hook, an agent) can reach a running widget:

```
$ owl-send build.owl set status passing     # sets the state `status`; a value that parses as JSON is JSON
$ owl-send build.owl set info - < info.json   # the value from stdin
$ owl-send build.owl refresh                  # runs its sources now
```

It waits for the launcher's answer and exits 1 if the widget isn't running or has no such
state. Setting a state works like a tap setting it: what reads it redraws, and triggers run.
For data a script writes anyway, a `file(...)` source is the other way: it updates the moment
the file changes.

```owl
widget "build" {
  meta { owl: 4 }
  state status = "unknown" persist
  source log = file("~/.cache/build.log")
  Column {
    Text { text: "build ${status}"; color: status == "passing" ? green : status == "failing" ? red : muted }
    Text { text: last(lines(log)) ?? "no log yet"; style: small; maxLines: 3 }
  }
}
```

## Without Linux

Linux (this Arch system) is an optional download in the launcher. A widget that runs commands,
declares packages or has a `Terminal` needs it: without Linux its tile offers to install Linux
instead of running. Widgets with only `http`, `clock` and `android.*` sources work on any phone.
Tapping `terminal(...)` or `app(...)` without Linux opens the install sheet, so those don't
make a widget need it.

A widget can also work both ways (owl 5). Mark its Linux sources `optional`: they stay `null`
until Linux is installed and running, and `linux` says whether it is:

```owl
widget "kernel" {
  meta { owl: 5 }
  source sys = android.system every 1m
  source pkgs = cmd("pacman -Qq | wc -l") every 10m optional
  Column {
    Text { text: "kernel ${sys.kernel}" }
    if linux {
      Text { text: "${pkgs} packages" }
    } else {
      Button { text: "install linux"; onTap: launcher("linux") }
    }
  }
}
```

`optional` works on `cmd`, `stream` and `file`. Keep such a widget's commands to base tools
(coreutils, grep, sed, awk, bash, pacman): `requires` makes a widget need Linux again.

## Dependencies

Declare every package your commands need, by package manager:

```owl
widget "grok" {
  requires { pacman: ["jq", "curl"] }
  source info = cmd('jq -c . ~/.cache/usage.json') every 5m json
  Text { text: "${info.use}%" }
}
```

- When a declared package is missing, the tile says `needs jq` with an **install** button
  instead of running the widget. That runs `pacman -Syu --needed`, the same way the system was
  set up. If it fails, the tile offers **fix with agent**.
- Names are package names, not program names (`dig` comes from `bind`). Find the owner of a
  program with `pacman -F dig` or `pacman -Qo $(command -v dig)`.
- Base tools (coreutils, grep, sed, bash) are always there; declaring them is optional.
- The launcher also reads the programs your fixed commands run. If one isn't installed and
  isn't covered by `requires`, `.errors/<file>.txt` gets a warning.
- While writing a widget, install what you declare with `pacman -S --needed <packages>`.

## Sharing

The **widget store** (long-press empty space on the home screen) installs reviewed widgets from
https://github.com/SimonSchubert/omarchy-widgets. To publish one you made, long-press it and tap
**publish**: the default agent opens a pull request there with the GitHub CLI (or an issue, if it
can't). By hand, open a pull request that adds it as `widgets/<github-username>/<name>/widget.owl`.
The store needs:

- `meta` with a `description` (10-200 characters), a `version` and a `license`, and ideally a `category`:
  `news`, `dev`, `productivity`, `time`, `world`, `sport`, `finance`, `media`, `games`, `pets`, `fun`, `home` or `system`
- every package in `requires`
- values in commands passed through `sh()` (see Values in commands)
- nothing personal: no tokens, personal paths or hard-coded location
- a higher `version` for each update
- ideally a `preview.png` next to it: the one `owl-check` points to will do

The repo's CI checks the rules and summarizes every command for the reviewer. Store installs go on
the page you're on; `page:` and `order:` in the file only apply to widgets you add yourself.

To pass a widget on without the store, share it as a link or QR code: the whole file is in it,
after the `#` of `https://simonschubert.github.io/omarchy-widgets/w#owl1.<data>` (raw DEFLATE, then
unpadded base64url), so it never reaches a server. One code holds about 2.9 KB of that, which most
widgets under 6 KB fit. Scanning it opens the launcher, which lists every command the widget runs and
flags risky ones before it adds it as `shared-<name>.owl`: nobody reviewed it. On Omarchy, the Widgets
app's **Share…** shows the code, and `omarchy-widgets share` / `omarchy-widgets import` make and read
links.

## Limits

A widget has at most 500 nodes (a page 1000), 2000 drawing calls per frame, lists of 1000
items, 32 images and 128 SVGs on show at once (a view with more keeps loading them
again and shows none for long), and a step budget per update. A widget that draws slowly animates at
half rate. Errors never take the home screen down: the failing value shows `…`
and the message goes to `.errors/`.

## Tips

- One small, focused widget per file; name files `NN-name.owl` to order them.
- Test commands in a terminal first. For several values, print JSON and use `json`.
- The phone is portrait: 6 columns, ~12 rows. A full-width widget is `6xN`,
  half-width `3xN`. Set `minSize` to what the layout still fits in. For the whole screen, write a page.
- Use `weight: 1` to fill leftover space and `fill: width` to stretch across.
- A `Text` without `width`, `weight` or `fill` takes the full width, except in a `Row` or `Flow` since owl 10,
  where it's as wide as its text. Before owl 10, give the Texts next to a `weight: 1` one a `width`, or the
  weighted one is squeezed to nothing.
- Look at the example widgets in this folder for patterns: `90-analog-clock` (Canvas),
  `91-focus` (state, buttons and `at`), `92-aurora` (Shader), `93-latency` (stream),
  `88-notes` (Input), `98-notifications` (android.notifications), `99-volume` (Slider),
  `65-ai-bar` (the prompt bar and recent strip: android.agents, android.recent, open and ask),
  and next to it `10-today` (a weather screen behind the tile), `20-fetch` (fastfetch as a dashboard), `45-terminal` (a live terminal) and
  `60-owl` (sprites).
