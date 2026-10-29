# Expressions

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

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
| text | `sh(x)` (one shell word, see Values in commands in docs/declarations.md), `enc(x)` (one URL component, see Values in URLs in docs/declarations.md) `str len trim upper lower capitalize lines words split(s, sep) join(list, sep) replace(s, a, b) contains startsWith endsWith sub(s, from, to) pad(x, width, char) fixed(x, digits)` |
| format | `fmtBytes(n)` → `1.5G`, `fmtDuration(seconds)` → `2h 5m`, `fmtTime(seconds, "EEE HH:mm")`, `fmtAgo(t, now)` → `5m`, `fmtNum(n)` → `12,345`; see Dates and numbers |
| weather | `weatherIcon(code, night)`: omarchy's weather glyph for a wttr.in `weatherCode` (show it with `font: icon`) |
| parsing | `match(s, regex)` (groups of the first match), `matchAll(s, regex)`, `parseJson(s)`, `toJson(x)` (JSON text), `parseTime(s)` (a date in text as Unix seconds), `toBase64(s)` and `fromBase64(s)` (owl 12, see Sending in docs/handlers.md) |
| chance | `random()`, `random(n)`, `random(a, b)`, `shuffle(list)`, only in handlers and a state's first value; `hash(...)` and `shuffle(list, seed)` anywhere. See Chance |
| lists | `range(n)` or `range(a, b, step)`, `map filter find any all count sum avg sort sortBy reverse take drop first last indexOf unique`, `chunk(list, n)` (the list in lists of `n`, the last one shorter: rows for a grid with full-width headings, owl 11), `flatten(list, depth)` (depth 1 unless given), `keys values entries get(obj, key, default)`; see Changing lists and maps |
| search | `rank(list, query)`: what matches `query`, best first, as the prompt bar ranks apps: a whole name, then its start, the starts of its words, its initials, a part of it, and its letters in order, ignoring case and accents. A map matches by its `name` and `alias` and ranks higher with its `score`, so `rank(d.apps, q)` over `android.drawer` finds what the bar finds; a text matches as it is. `strongMatch(a, query)` is true when the bar would open `a` on Enter rather than ask the agent: what's typed names it well (its initials or better, by name or alias) and is one line of two words at most. It follows the bar as the launcher changes, so a drawer of your own keeps opening what the bar opens (owl 11) |
| colors | theme names `accent foreground background muted surface border red green yellow blue magenta cyan orange` plus `bright_*`, `color0`…`color15`; `#7aa2f7`; `rgb(r, g, b, a)`, `hsl(h, s, l, a)`, `alpha(c, 0.5)`, `mix(a, b, t)` |
| gradients | `linear(x0, y0, x1, y1, [colors])`, `radial(cx, cy, r, [colors])`: fills in Canvas, and backgrounds (owl 10, see docs/nodes.md) |

Prefer theme color names: widgets then follow theme changes.

## Dates and numbers

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

`lang()` is the phone's language as its code, `en`, `id`, `de`, for an API that takes one; text in more
languages goes in `strings` (owl 16, see Text in more languages in docs/declarations.md).

`fmtNum(x)` groups thousands the phone's way (`12,345` or `12.345`), rounded to whole numbers;
`fmtNum(x, 2)` keeps two digits after the point. A map sets more: `fmtNum(x, {compact: true})` shortens
to `950`, `1.5k`, `12k`, `3.4M`, `2.5B`, `1T` (one digit under 10, none above, unless `digits` says);
`sign: true` puts a `+` before numbers above zero, for changes: `fmtNum(delta, {digits: 1, sign: true})`.

## Changing lists and maps

Values never change in place; these give a changed copy, to assign to a state in a handler (owl 9):

- `set(list, i, v)` with item `i` replaced (counted from the end when negative; an index that isn't in
  the list changes nothing), and `set(map, key, v)` with that field set. `remove(list, i)` and
  `remove(map, key)` leave it out: `onTap: todos = remove(todos, i)`.
- `merge(a, b, ...)` is the maps' fields together, a later one's winning: `merge(item, {done: true})`.
- `reduce(list, (total, x, i) => ..., start)` works through a list to one value, and `scan` gives each
  step on the way, like a running total: `scan(days, (sum, d) => sum + d.spent, 0)`.
- `groupBy(list, x => x.day)` is a map from each key to the items that have it, in the order they came.

## Chance

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
