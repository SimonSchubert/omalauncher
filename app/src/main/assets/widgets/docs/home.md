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

[[page]]                  # page 1, where home opens by default: the first page of tiles
tiles = [
  { widget = "50-memory.owl", x = 0, y = 0, w = 3, h = 4 },
  { app = "org.mozilla.firefox", x = 3, y = 0 },
  { folder = "Work", x = 4, y = 0, apps = ["com.slack", "linux:foot"] },
  { widget = "20-fetch.owl" },   # no x and y: the first free spot, at its size:
]
```

- Pages are `[[page]]` blocks in order: move a block to move the page. The
  first one with tiles is page 1; faces before it are left of it.
  Home opens on page 1 and returns to it when Home is pressed with nothing
  open; `home.home-page` in ../style/style.toml picks another (0 is the face
  left of page 1).
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

On a foldable, the cover screen has pages of its own, under `[cover]`: the
same tiles, placed for a screen a third as tall. The pages at the top say
what's on home; `[cover]` only says where it goes there. A tile left out of
`[cover]` keeps its place on the cover screen, and without the section the
cover screen starts from the pages at the top, each tile at its own size and
what doesn't fit on to the next page. The launcher writes the section the
first time it's shown on the cover screen; deleting it starts over.

```toml
[cover]
columns = 6

[[cover.page]]
tiles = [
  { widget = "10-today.owl", x = 0, y = 0, w = 3, h = 3 },
  { widget = "20-fetch.owl", x = 3, y = 0, w = 3, h = 3 },
]

[[cover.page]]
tiles = [ { widget = "45-terminal.owl", x = 0, y = 0, w = 6, h = 3 } ]
```

A Fold's unfolded inner screen shows home's pages two side by side. With
`home.unfolded-layout = true` in style.toml it has pages of its own instead,
under `[unfolded]`, the same way as `[cover]`: one wide page whose tiles can
span the hinge, on a grid of `home.unfolded-columns` (12 by default). The first
time it shows, it starts from the pages at the top as it showed them, pages 1
and 2 side by side as its page 1, 3 and 4 as page 2; after that the two go
their own ways.

```toml
[unfolded]
columns = 12

[[unfolded.page]]
tiles = [
  { widget = "10-today.owl", x = 0, y = 0, w = 6, h = 3 },
  { widget = "45-terminal.owl", x = 6, y = 0, w = 6, h = 6 },
]
```

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
