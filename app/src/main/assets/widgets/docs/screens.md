# Screens

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

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
