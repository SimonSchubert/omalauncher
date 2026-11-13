# Browser

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

A web page inside the tile, drawn by Android's own WebView, so it needs no Linux (owl 18):

```owl
widget "lobsters" {
  size: 6x4
  padding: 0
  meta { owl: 18 }
  state title = ""
  Column {
    Row {
      gap: 8
      Button { text: "‹"; onTap: web("back") }
      Text { text: title; style: muted; maxLines: 1; weight: 1 }
    }
    Browser { url: "https://lobste.rs/"; zoom: 0.9; weight: 1; onTitle: title = value }
  }
}
```

- The page loads over https, from a host written out in the file: `"https://example.com/post/${enc(id)}"`.
  `http://`, `file:`, `content:`, `javascript:` and `data:` don't compile, a value can't be the host, and
  values go through `enc()` as a `http(...)` URL's do. The whole URL cannot come from a state either: write
  out where it starts and put the part that changes through `enc()`. A link in the page that goes to one of
  those loads nothing; `tel:` and `mailto:` open the app for them, as `url(...)` does. The store lists the
  page as `browser GET https://…`, the way it lists an image.
- Script is off unless the node says `js: true`, and DOM storage comes with it. Many sites need it: one
  behind a bot check ("making sure you're not a bot") never gets past it without script. A page gets no JavaScript
  bridge, can't reach this phone's files or a `content://` provider, opens no second window, and plays no
  sound before a tap. An https page can't pull in `http://` either.
- `zoom` is the page's text size, 1 by default and kept between 0.5 and 3.
- The tile is inert until tapped, so swipes still turn pages and a long press still lifts it. Tapping
  it gives the page its touches (a tap, a scroll, a pinch); a tap outside it, another page or Back
  lets go, and so does a tab switched away from it. A text field in the page raises the keyboard,
  and the tile lifts above it as a terminal's does.
- `onNavigate` gets the `value` of each page it goes to, including a link's; `onTitle` its title, and
  `onProgress` how much has loaded, 0 to 1.
- `web("back")` steers it, and so do `"forward"`, `"reload"` and `"stop"`. A handler names no node, so
  `web(...)` steers every Browser in the file.
- Two Browsers at most in a file: each is a web view of its own, and a tile has room for two. A Browser
  goes on a widget's tile or a face, not in a `screen { }`, an app, a panel or the key bar, and not inside
  a `for` or a component: its page belongs to the node, so copies would close each other's. To show one of
  several pages, let a state choose the url: `url: tab == "news" ? "https://lobste.rs/" : "https://example.com/"`.
- `web(...)` in a file without a Browser doesn't compile.
- The page keeps its place while the tile is off screen. It starts again when its `url`, `js` or `zoom`
  changes, and ends with the widget. Out of sight its animations and media wait, and while the launcher
  is behind another app or the screen is off its script waits too.
- Browser tiles aren't previewed; `owl-check` says so in place of a picture.
- Every Browser in the launcher shares one WebView profile, so signing in in one signs in the rest, and
  what one page keeps in cookies the others can read.

A face can hold a terminal and a page, one at a time. A state says which tab shows, and the one out of
sight keeps its page where it was:

```owl
face "work" {
  meta { owl: 18 }
  state tab = "shell"
  Column {
    Row {
      gap: 12
      Text { text: "shell"; style: small; color: tab == "shell" ? accent : muted; onTap: tab = "shell" }
      Text { text: "web"; style: small; color: tab == "web" ? accent : muted; onTap: tab = "web" }
      Spacer { weight: 1 }
      Text { text: "‹"; style: small; onTap: web("back") }
    }
    if tab == "shell" {
      Terminal { weight: 1 }
    } else {
      Browser { url: "https://lobste.rs/"; weight: 1 }
    }
    Spacer { height: screenInset }
  }
}
```

`examples/46-browser.owl` is the first one with back, forward and reload, ready to move up into
`widgets/` and use.

A tile's page is deliberately closed to a developer: no `http://`, no debugging socket. Working on a
page served from this phone wants the opposite of both, and that is not this node's job.
