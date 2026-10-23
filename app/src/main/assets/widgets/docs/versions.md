# Versions

Part of the widget docs: README.md, one folder up, comes first, and paths here are from the widgets folder.

`meta { owl: N }` is the language version a file needs; this launcher has 13, and without `meta` it's 1. Declare
the highest level of what the file uses: using something from a later level than the file declares is an error,
and a launcher older than the level says "update the launcher" instead of failing on what it doesn't know. For a
file only this phone runs, `owl: 12` is fine; for the store, a lower level reaches older launchers too.

- **2**: `Terminal`
- **3**: `sprite`
- **4**: triggers, `file`, `Slider`, `Input`, `android.notifications`, `android.volume`, `volume` and `notification`
- **5**: `screen { }`, `screen()`, `close()`, `screenWidth` and `screenHeight`
- **6**: Input's `focus`, `sh()` and `corners`
- **7**: `android.alarm`, `android.modes`, `torch`, `share` and `haptic`
- **8**: `app "…" { }` files, `android.apps`, `enc`, `post`, images from the web, `sound` and `screenInset`
- **9**: `parseTime`, `fmtAgo`, `fmtNum`, `random`, `shuffle`, `hash`, fmtTime's zone and language, `set`, `remove`,
  `merge`, `reduce`, `scan`, `groupBy`, `toJson`, `capitalize`, `asin`, `acos`, flatten's depth, `on … first`, and
  `if` and `let` in handlers
- **10**: `face "…" { }` files, `onLongPress`, Text's `spans`, `fontWeight` and `letterSpacing`, gradient backgrounds,
  `frost`, fonts of a widget's own and Canvas `text`'s `font`, `fontWeight`, `italic` and `letterSpacing`,
  `android.agents`, `android.recent`, `open`, `ask`, `Popup` and Input's `underline`. A Text in a Row or Flow is as
  wide as its text (see Text in a Row in docs/nodes.md)
- **11**: `android.drawer`, `appAction`, `workApps`, `rank`, `strongMatch`, `chunk`, Column's and Grid's `lazy` and
  `reverse`, `panel "…" { }` files with `query`, `enter`, `onSubmit` and `onKey`, `shortcuts`, Image's `label`, Text's
  `minLines` and an app's `statusBar`. A Column in a Row is as wide as its content
- **12**: `screenTop`, `gapsIn`, `gapsOut`, `borderWidth`, `keybar "…" { }` files, `pressKey`, `typeText`, `latch`, `android.modifiers`, `onPress`,
  `onRelease`, `repeats`, post's `method:`, `toBase64`, `fromBase64`, `secret` states and Input's `password`
- **13**: `settings`, `notification("clear")`, `"action"` and `"reply"`, `volume`'s stream, `drag`, `onDragEnd`, `android.network`, `android.volume`'s
  `output`, a panel's `opacity`, `android.sim`, `dnd`, `torch(level)`,
  `notification("snooze")`, `"snoozeAll"`, `"unsnooze"` and `"unsnoozeAll"`, `animate offset`, `tileOpacity`, `tileFrost`, and in `android.modes` the radios, location, rotation, dark theme, brightness, `dndAllows` and `torchLevel`.
  Only a brightness written out, `torch(40)`, asks for owl 13; `torch(value)` from a Slider can't be told from on and off when the file is
  checked, so a file that dims the torch should say `owl: 13` itself, as older launchers do nothing with a number
