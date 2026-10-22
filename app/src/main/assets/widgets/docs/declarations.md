# Declarations

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

## Declarations (top of the file)

| declaration | meaning |
|---|---|
| `source x = cmd("...") every 5s` | runs a bash command in this Arch system; `x` is its output (stdout, trimmed). Add `json` to parse JSON output, `timeout 30s` to allow more than 10 s, `status` to get `{out, err, code, ok}` instead (see below), `optional` to let the widget work without Linux (see Without Linux). Default interval: 60 s |
| `source x = stream("...")` | a long-running command; every line it prints becomes the new value. Add `json` for JSON lines. At most 4 different streams run at once |
| `source x = http("https://...") every 10m` | an HTTP GET from Android, no curl or shell needed. Takes `json`, `timeout` and `status` like cmd (with `status`, `code` is the HTTP status and `ok` means 2xx), and optionally headers: `http(url, {Accept: "application/json"})`. A response over 1 MB fails (`the response is over 1 MB`), and the interval is 5 s or more. Prefer it over `cmd("curl ...")`. A URL can hold values, through `enc()` (see Values in URLs); it's fetched again as soon as it changes, and a `null` URL pauses the source, keeping its last value: `http(tab == "news" ? "https://..." : null)` fetches only while that tab shows. A failed fetch (offline, an error status, a rate limit) is tried again after 30 s |
| `source x = file("~/.cache/x.json")` | a file in this system, read again whenever it changes (no polling, no shell). Add `json` to parse it. A missing file reads as `""`; at most 1 MB is read. Prefer it over `cmd("cat ...") every 5s` (owl 4) |
| `source now = clock every 1s` | Unix time in seconds, with no shell involved. Use `fmtTime(now, "HH:mm")` |
| `source bat = android.battery` | the phone's own data, from Android: the battery, what's playing, notifications, volume, the next alarm, modes, apps, the drawer, coding agents. See docs/android.md |
| `state x = 0` | a value that handlers can change. Add `persist` to keep it across restarts: `state endsAt = 0 persist` |
| `state token = null secret` | a token or password: kept across restarts encrypted, never in the persisted values, and it only goes into `http(...)`'s and `post(...)`'s headers and `post`'s body, into their URL as `enc(token)`, or `token == null` (or `!= ""`) to see whether it's set, with the server written out in the URL; anything else that reads it is an error. Use it for every API key, token and password. See Tokens and passwords in docs/handlers.md (owl 12) |
| `let x = expr` | a derived value, recomputed only when what it reads changes. It can use sources, state and earlier lets |
| `let h = history(x, 60)` | the last 60 values of `x`, sampled whenever a source updates (for Sparkline). With `persist` it survives restarts, unless it's older than it would take to fill |
| `let p = prev(x)` | the previous value of `x`, for rates: `let rate = (bytes - p) / 5` |
| `component Name(a, b = 1) { ... }` | a reusable node; use it as `Name { a: 1 }` |
| `asset logo = base64"iVBOR..."` | an embedded image (under 2 MB) for `Image { src: logo }`, or a font for `font: logo` (owl 10, see Fonts in docs/nodes.md), so the widget is one self-contained file |
| `requires { pacman: ["jq", "curl"] }` | packages the widget's commands need; see Dependencies |
| `meta { description: "..."; version: "1.0"; license: "MIT"; category: "news" }` | needed to publish to the widget store (see docs/sharing.md) |
| `meta { owl: 12 }` | the language version the file needs (this launcher has 13; the default is 1). Older launchers say "update the launcher" instead of failing on what they don't know. Using what a later level added without declaring it is an error; docs/versions.md lists what came when |
| `onTap: ...` | runs when the tile (not a button inside it) is tapped |
| `screen { ... }` | a full-screen view of the widget, opened with `screen()`; see docs/screens.md (owl 5) |
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
```

Keep commands fast. A command
can depend on state (`cmd("curl wttr.in/${sh(city)}")`) and runs again as soon as the
value changes (at most once a second), without waiting for its interval or a `refresh()`.

### Persisting

Persisted values are kept by the launcher (not in the widget file), per file name, and
must be plain data: numbers, text, lists and maps (not colors). Store times as absolute
Unix seconds (`endsAt = now + 25 * 60`), not countdowns, so they're still right after a restart.

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
