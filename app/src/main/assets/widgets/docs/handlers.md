# Handlers and actions

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

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

## Actions

| action | does |
|---|---|
| `terminal("btop")` | opens the command in a terminal window; `terminal(cmd, "title")` |
| `app("gtk3-demo")` | starts a graphical Linux app |
| `run("cmd")` | runs in the background (on its own shell, never retried), then refreshes this widget |
| `x = run("cmd")` | the same, and puts the command's output into the state `x` when it finishes |
| `androidApp("org.pkg")` | opens an Android app by package name, or by an `android.apps` key to open that copy in a work profile or clone |
| `open(r.key)` | opens an app from `android.recent` or an agent from `android.agents` by its `key` (an `android.apps` key works too), as tapping it in the drawer would, so it counts as opened (owl 10) |
| `appAction("pin", a.key)` | the drawer's long-press menu for an app from `android.drawer`: `pin` (add to home, on the page showing), `info` (Android's app info), `uninstall` (Android asks first), `remove` (a shortcut, or an owl app, whose file goes to `.removed/`), `edit` (the default agent on an owl app's file) and `share` (an owl app's link or QR code). An entry the app's `actions` don't list does nothing. The store's reviewer sees which entries a widget uses (owl 11) |
| `s = shortcuts(a.key)` | an app's shortcuts, as its long-press menu offers them (New tab, a recent chat), into the state `s` once they've loaded: a list of `{key, name, icon}`, empty for an app with none. `open(s.key)` opens one and `appAction("pin", s.key)` puts it on home (owl 11) |
| `workApps("toggle")` | pauses the work profile's apps or turns them back on, as the drawer's switch does: `pause`, `resume` or `toggle`. Android may ask for the work PIN, and only lets the default launcher (owl 11) |
| `ask(text)` | opens a terminal with the default agent answering `text`, as sending it from the prompt bar does; `ask(text, "codex")` asks that installed agent. Without one installed it opens the agents settings. The store's reviewer sees what a widget asks (owl 10) |
| `copy(text)` | copies text to the Android clipboard |
| `notify("title", "text")` | posts an Android notification (if the launcher may post them) |
| `post("https://...", body, headers)` | sends an HTTP POST from Android: a map or list `body` goes as JSON, text as it is; `headers` is a map like `http(...)`'s. `x = post(...)` puts the answer into the state `x` when it arrives: `{code, ok, out, err}`, with `out` parsed when it's JSON and `err` why it failed (code -1 when no answer came: offline, a timeout, a redirect it didn't follow). `method: "PUT"`, `"PATCH"` or `"DELETE"` sends that instead (owl 12). Never retried; a null URL sends nothing. When it succeeds, the widget's sources run again. See Sending (owl 8) |
| `url("https://...")` | opens in the Android browser. Other links open in the app that takes them: `tel:` the dialer, `geo:` maps, `mailto:` email |
| `share(text)` | opens Android's share sheet with the text (owl 7) |
| `haptic()` | a short tap of haptic feedback, if the user has touch feedback on (owl 7) |
| `pressKey("ctrl+c")` | presses a key in the Linux window being typed in (a terminal tile, or a Linux app's window: a terminal or a graphical app), with any modifiers latched on the key bar held too: a name, `esc`, `tab`, `enter`, `backspace`, `delete`, `insert`, `space`, `home`, `end`, `pgup`, `pgdn`, `up`, `down`, `left`, `right`, `f1`…`f12`, or a character on a US keyboard (`/`, `A`, `:`), after any of `ctrl+`, `alt+`, `shift+` and `super+`; a letter after them is the key as written on it, so `ctrl+C` is Ctrl+C, and Ctrl+Shift+C is `ctrl+shift+c`. Nothing happens while no Linux window has the keyboard, and a tap on another tile takes it from a terminal tile first: a key on home works from the key bar, or from a widget's own tile next to its `Terminal`, not from another tile or an app. Only from a touch, not in a trigger; the store's reviewer sees which keys. See docs/keybar.md (owl 12) |
| `typeText("git status\n")` | types the text into the same window, as the keyboard would, latched modifiers and all; `\n` is Enter (owl 12) |
| `latch("ctrl")` | holds `ctrl`, `alt` or `shift` for the next key, the key bar's or the keyboard's, or lets it go if it's held; `latch("ctrl", "locked")` holds it until set `off`, and `"once"` or `"off"` set it so. `android.modifiers` says how each is held (owl 12) |
| `sound("https://…/a.mp3")` | plays a sound once, in place of the one playing; `sound("stop")` stops it. Takes a URL, an asset or a file, like `Image`; see Sounds (owl 8) |
| `torch(true)` | turns the torch on or off; also `torch("toggle")`. `torch(40)` turns it on at 40% of its brightness, which on and toggle keep using; 0 turns it off, and a phone whose torch can't be dimmed just turns it on (owl 13). Does nothing on a phone without a flash, or while the camera app holds it (owl 7) |
| `dnd(true)` | turns Do Not Disturb on or off, as its quick settings tile does, letting through what the user's settings let through; also `dnd("toggle")`. It takes the words `android.modes`' `dndAllows` reads: `dnd("priority")` turns it on for the user's exceptions, `dnd("alarms")` for alarms only, `dnd("none")` for total silence, alarms too, and `dnd("all")` turns it off; on and toggle then keep the one picked last, priority or alarms: total silence is never kept, so turning it on again doesn't mute alarms unasked. It needs notification access, as media widgets do: without it, opens the setting that grants it. Since Android 15 it can turn off only a Do Not Disturb it turned on: one the user switched on, or a schedule did, stays, and Android's Do Not Disturb settings open instead (owl 13) |
| `launcher("style")` | opens a launcher screen: `style` (theme, background, home screen, terminal; `themes` too), `agents`, `store`, `drawer`, `linux` (the sheet that installs Linux) or `settings` (owl 11 launchers; older ones ignore it) |
| `refresh()` | re-runs this widget's sources now |
| `screen()` / `close()` | opens the widget's `screen { }` full screen, or closes it (owl 5) |
| `media("toggle")` | controls what's playing: `toggle`, `play`, `pause`, `next`, `previous`, or `open` (brings up the player's own screen when the app provides one, like a web app's window or a podcast's episode, else the app) |
| `volume(50)` | sets the media volume in percent; or `volume("up")`, `"down"`, `"mute"`, `"unmute"`, `"toggle"` (owl 4). A second value picks another stream: `volume(30, "ring")`, `"notification"`, `"alarm"` or `"media"` (owl 13). Do Not Disturb can refuse ringer changes |
| `notification("open", key)` | opens a notification from `android.notifications` as tapping it in the shade would; `"dismiss"` clears it. Without notification access, opens the setting that grants it (owl 4). `notification("clear")` clears every one that can be, as the shade's Clear all does (owl 13). `notification("snooze", key, seconds)` hides one until the time is up, when Android shows it again, as snoozing in the shade does; `notification("snoozeAll", 3600)` every one that can be cleared, for an hour. From a minute to a week. `notification("unsnooze", key)` brings one back now, `notification("unsnoozeAll")` every snoozed one, those snoozed in the shade too (owl 13) |
| `notification("action", key, i)` | presses the `i`-th of a notification's `actions`, counted from 0, as tapping its button in the shade would. One whose `reply` is true takes text instead: `notification("reply", key, text)` answers through it, as replying in the shade does: `Input { onSubmit: notification("reply", item.key, value) }`. The store review lists both, since they act as you in other apps (owl 13) |
| `settings("wifi")` | opens Android's own panel, a sheet over home, or its settings screen where it has no panel: `internet`, `wifi`, `bluetooth`, `airplane`, `data`, `location`, `cast`, `nfc`, `volume`, `output`, `sound`, `dnd`, `display`, `dark`, `battery` or `notifications`. For what a launcher may show but not switch, like the radios in `android.modes`. `output` is Android's output switcher for what's playing, the sheet its media controls open, to move the sound to headphones or a speaker; Bluetooth settings on a phone without one (owl 13) |

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

A REST API changes and removes things with other methods than POST: `method: "PUT"` sends a PUT (to
replace what's at the URL), `method: "PATCH"` a PATCH (to change some of its fields) and
`method: "DELETE"` a DELETE (to remove it), each like a POST otherwise (owl 12). The method is written as
it is, never a value, and listed for review with the URL, as `http DELETE https://…`. A DELETE goes
without a body unless it's given one:

```owl
app "todo" {
  meta { owl: 12 }
  source items = http("https://api.example.com/items") json
  Column {
    for i in items ?? [] {
      Row {
        Text { text: i.title; weight: 1 }
        Text { text: i.done ? "☑" : "☐"; onTap: post("https://api.example.com/items/${enc(i.id)}", {title: i.title, done: !i.done}, method: "PUT") }
        Text { text: "✕"; onTap: post("https://api.example.com/items/${enc(i.id)}", null, method: "DELETE") }
      }
    }
  }
}
```

An API that signs in with a user name and a password (HTTP Basic auth) takes them base64-encoded in a
header: `{Authorization: "Basic ${toBase64(user + ":" + password)}"}` (owl 12). `fromBase64(s)` reads it
back, and base64url too, padded or not, so `parseJson(fromBase64(split(jwt, ".")[1]))` is what a JWT
says about itself (when it expires, `exp`); it's null when the text isn't base64. Both work on text,
not on binary data.

### Tokens and passwords

Whenever a widget needs an API key, a token or a password, keep it in a `secret` state, never in a plain
or `persist` one (owl 12). A secret is kept across restarts encrypted with a key that never leaves the
phone, in a file of its own beside the widget's `persist` values, so Linux, a backup or a shared copy of
the widget never holds it. Never write a token into the file itself either: ask for it with an `Input`
whose `password: true` shows dots and keeps the keyboard from suggesting or learning what's typed.

The compiler lets a secret go only into `http(...)`'s and `post(...)`'s headers and `post`'s body
(through anything there, like `toBase64(...)`), written out at each call, since a `let` can't hold it,
and into `token == null`, `token != null` or `token != ""` to see whether it's set. An API that takes its key in the URL gets it as `enc(key)` there, written just so:
`"https://api.example.com/data?appid=${enc(key)}"`. Showing it, putting it in a URL any other way, a
command or a notification, copying it, a trigger on it or assigning it to another state is a compile
error. A handler may set it: `token = value` from the Input, `token = null` to sign out; `owl-send`
can't, so owl-check's preview shows the widget signed out, and the user types the token in on the phone. The server a secret goes to is written out in the URL (`"https://api.example.com/${enc(id)}"`),
not taken from a value, and it's an `https://` one. Prefer a header when the API takes one: servers often log the URLs they're asked
for.

```owl
widget "nas" {
  meta { owl: 12 }
  state token = null secret
  source files = http(token == null ? null : "https://nas.example.com/api/files", {Authorization: "Bearer ${token}"}) json
  Column {
    if token == null {
      Input { placeholder: "API token"; password: true; onSubmit: token = value }
    } else {
      for f in files ?? [] { Text { text: f.name } }
      Text { text: "sign out"; style: muted; onTap: token = null }
    }
  }
}
```

Gate the source on the token with a `null` URL, as above: without it, the source asks right away with
`Bearer …` (null as text) and fails. A user name that isn't secret can sit in a plain `persist` state
next to a secret password. A secret is kept for the servers it was typed in for: when the file changes
to send it to another one, or no longer calls it `secret`, the widget starts without it and asks again.

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
