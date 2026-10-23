# Home and scripts

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

## Home's layout

`~/.config/omalauncher/home/layout.toml` is home as it is: its pages left to
right, and where each tile sits. Moving things on the phone rewrites it, and
home follows your edits within a second. A widget's `page:`, `order:` and
`size:` only place it the first time; after that, move it here.

```toml
version = 1
columns = 6   # the grid the cells are on; home.columns in ../style/style.toml sets the grid

[[page]]                  # left of page 1: a face fills its page
face = "terminal.owl"

[[page]]                  # page 1, where home opens: the first page of tiles
tiles = [
  { widget = "50-memory.owl", x = 0, y = 0, w = 3, h = 4 },
  { app = "org.mozilla.firefox", x = 3, y = 0 },
  { folder = "Work", x = 4, y = 0, apps = ["com.slack", "linux:foot"] },
  { widget = "20-fetch.owl" },   # no x and y: the first free spot, at its size:
]
```

- Pages are `[[page]]` blocks in order: move a block to move the page. The
  first one with tiles is page 1; faces before it are left of it.
- `x` and `y` count cells from the top left; `w` and `h` are a widget's size.
  Apps and folders are one cell. A tile off the grid or on top of one before it
  takes the first free spot instead.
- `app` is an Android package (`"org.mozilla.firefox"`), an activity with its
  profile when there's more than one (`"com.android.chrome/.Main@10"`),
  `"linux:<desktop id>"`, or `"owl:<file>"` for an owl app. Adding one pins it;
  removing it takes it off home (it stays installed).
- A widget file left out of the file stays where it is, and a new file goes by
  its `page:`, `order:` and `size:`. To take a widget off home, move its file to
  `.removed/`.
- What couldn't be read is in `errors.txt` next to it, and on the phone's home.
  A file that doesn't parse changes nothing, and isn't saved over until it's fixed.

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
