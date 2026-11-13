# Browser node: implementation plan

Status: implemented and merged in the fork (#6), sent upstream in this pull request. M6 is partly
done, and the device checks below are still open.
Target: `SimonSchubert/omalauncher`, in owl 18.
Goal: make `Browser` a first-class OWL node, the sibling of `Terminal`, so a face can
hold a terminal and an embedded web view in tabs on one page.

This document was the handoff for the implementation. It records the decisions, the
public API, the files and integration points, the security rules, and the tests. The plan
is kept as it was written; where the build decided otherwise, see "What landed".

## What landed

M1 to M5 are in, and the app builds on 0.9.0 with 138 owl tests and 161 app tests green.
Where the code and this plan disagree, the code won:

- `web(...)` drives every `Browser` in the file, not the most recently tapped one. With two
  pages at most, one action for all of them is simpler than tracking an active node.
- The two-Browser limit is enforced twice: the compiler counts the nodes in a file, and
  `WidgetInstance` keeps two live pages, closing the one asked for longest ago when a loop
  or a reused component asks for a third. A page evicted that way draws blank until its
  node asks for it again.
- Focus and lift are `TerminalTiles`' own path: a page tile is inert until tapped, so the
  swipe that turns the page and the long press that lifts a tile still work. The flag is
  set in `TileWebView.dispatchTouchEvent`, the only place it can be: an `AndroidView`
  reaches its view before Compose's pointer input does.
- Previews are skipped, the store no longer badges a Browser widget as needing Linux, and
  `assets/widgets/README.md` documents the node.

## Decisions

The engine is Android System WebView (`android.webkit.WebView`). It is already on the phone,
adds no APK size, is hardware accelerated, and gives Android cookies and the system IME. It is
not full Chrome, but it is the right first version.

GeckoView is out for v1. It brings a second browser engine, tens of MB, and a much larger build
and API surface. The node contract is kept small enough to swap the engine later.

A Linux browser over the existing Wayland embedding is also out. `TerminalTiles` can already
claim any Wayland toplevel, so `epiphany`, `surf` or `netsurf` in the guest would work, but
startup, RAM and CPU under proot make it a poor daily browser. It remains a possible later
`EmbeddedWindow` node.

There is no Linux dependency. Browser-only widgets work without Linux, like widgets that only
use `http` and `android.*`, and `Program.needsLinux` stays false for `Browser`.

Security defaults are set for a page on a home screen. JavaScript is off unless the file says
`js: true`, only `https://` URLs load, `file://`, `content://`, `javascript:`, `data:` and
cleartext `http://` are blocked, and no JavaScript bridge is ever installed.

A file may hold at most two `Browser` nodes. WebViews are expensive, a face needs one, and two
leaves room for a spare tab. The compiler enforces it.

`Browser` and its actions require owl 18, so older launchers reject such files cleanly instead
of failing on an unknown node.

## Public API

Target use case:

```owl
face "work" {
  meta { owl: 18 }
  page: -2
  requires { pacman: ["tmux"] }

  state tab = "term"
  state url = "https://example.com"
  state title = ""

  let h = (tileHeight ?? 800) * 0.6

  Column {
    gap: 8
    padding: [12, 8]

    Row {
      gap: 6
      Button { text: "terminal"; color: tab == "term" ? accent : surface; onTap: tab = "term" }
      Button { text: "browser"; color: tab == "web" ? accent : surface; onTap: tab = "web" }
      Text { text: title; style: muted; weight: 1; maxLines: 1 }
    }

    Box {
      fill: width
      height: h
      clip: true
      radius: 8 * corners
      background: alpha(background, 0.55)

      Terminal {
        command: "tmux new -A -s face"
        fill: true
        visible: tab == "term"
      }

      Browser {
        url: url
        js: true
        fill: true
        visible: tab == "web"
        onNavigate: url = value
        onTitle: title = value
      }
    }

    Spacer { weight: 1 }
    Spacer { height: screenInset }
  }
}
```

### Properties

| property | type | default | meaning |
|---|---|---|---|
| `url` | text or null | required | The page to load. `https://` only. `null` shows a blank, themed page. A change loads the new URL. |
| `js` | bool | `false` | Enable JavaScript and DOM storage. Explicit opt-in. |
| `zoom` | number | `1` | Initial page scale, clamped to `0.5`–`2`. Pinch-zoom remains available. |

`url` is a normal live expression. Values inside it must use `enc()` and the scheme and
host must be fixed text, exactly as for `http(...)` and `Image` URLs:

```owl
Browser { url: "https://example.com/search?q=${enc(query)}" }   // right
Browser { url: "https://${enc(host)}/search" }                  // wrong: the host is a value
```

### Events

Optional handlers on the node. Each receives `value`.

| handler | `value` | when |
|---|---|---|
| `onNavigate` | the main-frame URL | A main-frame navigation starts (including redirects). |
| `onTitle` | the page title | The page title changes. |
| `onProgress` | 0–100 | Loading progress changes. |

Feedback-loop rule: when `onNavigate` writes the URL back into the state that feeds
`url`, the implementation must compare and ignore a load request for the URL that is
already showing. Redirects then update the state without reloading in a loop.

### Actions

| action | does |
|---|---|
| `web("back")` | Goes back in every Browser's history. |
| `web("forward")` | Goes forward. |
| `web("reload")` | Reloads. |
| `web("stop")` | Stops loading. |

Every `Browser` in the file gets the action, since two pages are the most a file holds.
`WidgetInstance` dispatches it, so one widget's action can never drive another widget's page.

## How it fits next to Terminal

`Terminal` is currently wired through four places:

1. `Schema.kt`: the `KINDS` entry and its `SINCE` level.
2. `Compiler.kt`: property validation, `program.terminal`, guest/command bookkeeping.
3. `Render.kt`: one `when` arm that calls `TerminalView`.
4. `WidgetInstance` / `TerminalTiles`: a per-node handle that survives recomposition
   and page changes, plus the home screen's keyboard/lift handling.

`Browser` uses the same shape, but the handle owns a `WebView` instead of a Wayland
toplevel. It has no `cpu`/`guest` side, and it does not need a package declaration.
`WidgetEngine` must skip its preview for the same reason Terminal's preview is skipped.

## Implementation

### 1. Language level and schema

`owl/src/main/kotlin/dev/omalauncher/widgets/lang/Schema.kt`

- Add the node:

  ```kotlin
  "Browser" to Kind(setOf("url", "js", "zoom"), Children.NONE),
  ```

- Add `Browser` to `SINCE` at level 11.
- Add the action to `SINCE` at level 11 (`web`).
- Add handler names to `HANDLER_PARAMS`:

  ```kotlin
  "onNavigate" to listOf("value"),
  "onTitle" to listOf("value"),
  "onProgress" to listOf("value"),
  ```

- Add the matching `HANDLER_KINDS` entries restricted to `Browser`.
- Add `ACTION_WORDS["web"]` with `back`, `forward`, `reload`, `stop`.

`owl/src/main/kotlin/dev/omalauncher/widgets/lang/Builtins.kt`

- Add `"web" to 1..1` to `Builtins.actions`.

`owl/src/main/kotlin/dev/omalauncher/widgets/lang/Compiler.kt`

- Bump `LEVEL` to `11`.
- Add a `browser` flag to the compiler state and a `MAX_BROWSERS = 2` counter.
- In node validation, for `Browser`:
  - require `url`;
  - validate it with a browser-specific version of `webFile`/`listUrl`:
    - reject anything whose known start is not `https://`;
    - reject `http://` with the existing "load over https only" message;
    - require fixed host text when the URL is interpolated;
    - append `browser GET <url shape>` to `commands` for the store reviewer and
      `owl-check`;
  - increment the Browser count and fail with a clear message above two;
  - validate `js` and `zoom` by allowing them through `prop(...)` like other node
    properties.
- For the `web` action:
  - check the words (`back`, `forward`, `reload`, `stop`);
  - `since("web", call.pos)`.

`owl/src/main/kotlin/dev/omalauncher/widgets/lang/Program.kt`

- Add `val browser: Boolean = false` next to `val terminal`.
- If a Browser count is useful to the UI, add `val browserCount: Int = 0`.

### 2. Runtime handle

Add `app/src/main/kotlin/dev/omalauncher/ui/BrowserView.kt` (or
`ui/BrowserTiles.kt` if a separate registry is preferred). Keep the handle
UI-lite, like `EmbeddedTerminal`: it is created before a `WebView` exists and survives
tab switches.

```kotlin
class EmbeddedBrowser(
    val url: String?,
    val js: Boolean,
    val zoom: Double,
) {
    var webView: WebView? = null       // main thread, set by BrowserView
    var title by mutableStateOf("")
    var progress by mutableStateOf(0)
    var currentUrl by mutableStateOf<String?>(null)

    var onNavigate: ((String) -> Unit)? = null
    var onTitle: ((String) -> Unit)? = null
    var onProgress: ((Int) -> Unit)? = null

    fun attach(view: WebView) { ... }
    fun detach(view: WebView) { ... }  // do not destroy: tab switching reuses it
    fun load(url: String?) { ... }
    fun back() / forward() / reload() / stop() { ... }
    fun close() { webView?.destroy(); webView = null }
}
```

`app/src/main/kotlin/dev/omalauncher/widgets/runtime/WidgetInstance.kt`

- Extend `Services` with:

  ```kotlin
  fun browser(url: String?, js: Boolean, zoom: Double): EmbeddedBrowser
  ```

  mirroring `terminal(...)`.
- Add `private val browsers = HashMap<Any, EmbeddedBrowser>()`.
- Add:

  ```kotlin
  fun browser(node: Any, url: String?, js: Boolean, zoom: Double): EmbeddedBrowser
  ```

  Reuse the handle when the node is the same. Update `url`/`js`/`zoom` on the handle
  when they change; do not create a new handle for every URL.
- In `stop()`, close every browser and clear the map.
- In `act(...)`, intercept `web` before delegating to `services.act(...)`:

  ```kotlin
  if (name == "web") {
      val b = browsers.values.lastOrNull()
          ?: return report("web(...) needs a Browser node")
      when (args.firstOrNull()?.toString()) {
          "back" -> b.back()
          "forward" -> b.forward()
          "reload" -> b.reload()
          "stop" -> b.stop()
      }
      return
  }
  ```

  `lastOrNull()` is the most recently created node; if there can be two, track the
  active one (see input below) and prefer it.

`app/src/main/kotlin/dev/omalauncher/widgets/WidgetServices.kt`

- Implement `browser(...)` by constructing `EmbeddedBrowser(...)`. No `Runtime`
  registry is required if handles are owned by `WidgetInstance`.
- If a registry is used, add `val browsers = BrowserTiles(...)` to `Runtime.kt`
  instead and delegate there.

### 3. Rendering

`app/src/main/kotlin/dev/omalauncher/widgets/render/Render.kt`

- Import `BrowserView` and `EmbeddedBrowser`.
- Add the arm next to `Terminal`:

  ```kotlin
  "Browser" -> {
      val url = p.observe("url")?.takeIf { it != Null }?.toString()
      val js = p.observe("js")?.truthy == true
      val zoom = p.observe("zoom")?.num() ?: 1.0
      val browser = remember(el, url, js, zoom) { inst.browser(el, url, js, zoom) }
      BrowserView(browser, p, mod.then(if (sized) Modifier else Modifier.fillMaxSize()))
  }
  ```

- `BrowserView` owns the `AndroidView`:

  ```kotlin
  @Composable
  fun BrowserView(browser: EmbeddedBrowser, p: Props, modifier: Modifier) {
      val context = LocalContext.current
      val tiles = LocalHomeTiles.current
      val onNavigate by rememberUpdatedState(p.el.handlers["onNavigate"])
      // ... onTitle, onProgress

      AndroidView(
          factory = {
              browser.webView ?: WebView(it).also { view ->
                  configureWebView(view, browser, context)
                  browser.attach(view)
              }
          },
          update = { view ->
              applySettings(view, p)
              browser.load(p.observe("url")?.takeIf { it != Null }?.toString())
          },
          onRelease = { view -> browser.detach(view) }, // not destroy
          modifier = modifier
      )
  }
  ```

- `configureWebView`:
  - `settings.javaScriptEnabled = browser.js`
  - `settings.domStorageEnabled = browser.js`
  - `settings.allowFileAccess = false`
  - `settings.allowContentAccess = false`
  - `settings.setGeolocationEnabled(false)`
  - `settings.setSupportMultipleWindows(false)`
  - `settings.mediaPlaybackRequiresUserGesture = true`
  - `settings.builtInZoomControls = true`
  - `settings.displayZoomControls = false`
  - `settings.setSupportZoom(true)`
  - `settings.loadWithOverviewMode = true`
  - `settings.useWideViewPort = true`
  - `setBackgroundColor(Palette.background.toArgb())`
  - `webContentsDebuggingEnabled` only in debug builds
  - `WebViewClient`:
    - `shouldOverrideUrlLoading`: allow `https`; for `http`, `file`, `content`,
      `javascript`, `data`, return true and do not load; for other schemes, hand to
      `context.openUrl(...)` and return true.
    - `onPageStarted`: update `browser.currentUrl`, call `onNavigate`.
    - `onPageFinished`: update title/url.
    - `onReceivedError`: report a small error page or leave the previous page; log
      through the widget error channel if convenient.
  - `WebChromeClient`:
    - `onProgressChanged`: update `browser.progress`, call `onProgress`.
    - `onReceivedTitle`: update `browser.title`, call `onTitle`.

- Compose integration:
  - Use `rememberUpdatedState` for the node handlers so a recomposition doesn't
    capture an old `Env`.
  - If `Browser` is hidden with `visible: false`, `Node` returns before composing;
    `BrowserView` is disposed, `onRelease` detaches the WebView but does not destroy
    it. When the tab returns, `factory` returns the cached view. This is what keeps
    the page alive across tabs.
  - Apply `clip: true`/`radius` with the existing node modifier. WebView is a real
    Android view, so clipping may need `Modifier.clip(RoundedCornerShape(...))` plus
    `clipToOutline`; verify on device.

### 4. Input, keyboard and Back

The terminal's model is the right one: a home-screen node is inert until tapped, so
page swipes and drawer gestures still work.

- Extend the existing `TerminalTiles` home-input owner rather than adding a second
  focus system:
  - add `var browser: EmbeddedBrowser?` and `var browserBounds: Rect`;
  - include it in `typing` (`active != null || field != null || browser != null`);
  - `focused(browser)` clears the terminal/field and sets the browser;
  - `deactivate()` clears the browser focus (`webView.clearFocus()`), the terminal and
    the field;
  - `pressAt(...)` deactivates when the tap is outside the browser's bounds;
  - `liftFor(...)` uses the browser's bounds when it has focus, so its field and the
    page's bottom stay above the soft keyboard.
- `BrowserView`:
  - register bounds with `onGloballyPositioned`, translating by `tiles.lift` as
    `TerminalView` does;
  - when inactive, draw a transparent full-size overlay with
    `detectTapGestures { tiles.focused(browser); view.requestFocus() }`;
  - when active, let the `WebView` receive touches;
  - add a small "done"/keyboard button only if needed; the terminal's ⌨ button is a
    good pattern.
- Back handling: in `MainActivity`/`Home`, if `tiles.deactivate()` was true, consume
  Back; otherwise fall through to the existing behavior. A Browser's own history is
  not Android Back in v1; the `web("back")` action or an on-screen control is the way
  back. Optionally, when the WebView can go back, Back could go back before releasing;
  do not do that silently in v1.
- Tab switching with `visible` must not reset focus to the hidden browser. When the
  visible tab changes, `TerminalTiles` should deactivate the old browser/terminal.

### 5. Actions and events

- The compiler's `web(...)` is a normal action. `WidgetInstance.act` handles it.
- `Browser` handlers are invoked from `WebViewClient`/`WebChromeClient` callbacks,
  always on the main thread.
- Handler invocation uses the same `Action.run(env, listOf(Str(value)))` path as other
  node handlers. Wrap in the widget's budget/reporting if the renderer has a helper.

### 6. Preview and owl-check

`app/src/main/kotlin/dev/omalauncher/widgets/WidgetEngine.kt`

In `preview(...)`, before rendering:

```kotlin
program.browser -> "Browser tiles aren't previewed"
```

Place it next to the existing `program.terminal` skip. `owl-check` will then print
`no preview: Browser tiles aren't previewed` instead of hanging or drawing an empty
tile. The compile check still runs and still reports errors.

If a static preview is wanted later, capture a bitmap from the WebView after
`onPageFinished` and write it to `.preview/`. Do not make v1 depend on it.

### 7. Documentation and store

- `app/src/main/assets/widgets/README.md`:
  - add `Browser` to the node table;
  - add `Browser` and `web` to the owl 18 sentence;
  - add a short "Browser" section with the properties, events, actions, HTTPS rule,
    JS-off default and max two per file;
  - note that Terminal and Browser previews are skipped;
  - add the tabbed face example above (adapted to the reader).
- `docs/ARCHITECTURE.md`: mention that `Browser` uses Android WebView, not the Wayland
  compositor, and that `TerminalTiles` now also owns browser focus/lift.
- `CHANGELOG.md` at release time: "owl 18: a `Browser` node, an embedded WebView for
  faces and widgets."
- Store review: `browser GET …` appears in `Program.commands`, so the existing review
  markdown lists it. A browser is untrusted content; the store should call that out.
  Do not silently allow a widget to load a URL built from values: the compiler's
  fixed-host/`enc()` rule is the gate.

### 8. Tests

Language tests in `owl/src/test/kotlin/dev/omalauncher/widgets/lang/LangTest.kt`:

- `Browser` without `meta { owl: 18 }` fails with a "needs owl 18" message.
- `meta { owl: 18 }` + `Browser { url: "https://example.com" }` compiles.
- `Browser` without `url` fails with a clear message.
- `http://`, `file://`, `javascript:` and `data:` URLs fail.
- An interpolated URL without `enc()`/fixed host fails or warns, matching `http`.
- `js` and `zoom` are accepted; unknown properties fail.
- `web("back")` compiles; `web("sideways")` fails with the allowed words.
- Three `Browser` nodes fail with the max-two message.
- `needsLinux` is false for a Browser-only widget.
- `commands` contains the `browser GET …` shape.
- A face and an app may use `Browser` (they share the node tables).

Home-input tests in `app/src/test/kotlin/dev/omalauncher/ui/TileLiftTest.kt`:

- browser bounds are used for lift/cut when the browser is focused;
- a tap outside deactivates it;
- clearing focus returns to the no-keyboard state.

Manual test matrix (needs a device):

1. `owl-check` a Browser widget: compiles, preview skipped, no errors.
2. Without Linux installed: Browser still renders and loads.
3. `js: false` page renders; `js: true` page runs scripts.
4. A page with a form: tap the field, keyboard rises, tile lifts, bottom content
   stays visible.
5. Tap outside: keyboard hides, page scroll position is kept.
6. Switch terminal/browser tabs repeatedly: terminal process and browser page survive.
7. Navigate a link, then `web("back")`.
8. Leave the face and return: page still shows (or reloads only if the widget
   restarted).
9. Remove/rename the file: WebView is destroyed, no crash.
10. Rotate/unfold: `tileWidth`/`tileHeight` layout still works.

## Milestones

- [x] M1: language contract. Schema, Compiler, Program, Builtins; `LangTest`
      passes; `owl-check` reports the node requires owl 18 and validates URLs. No
      rendering yet.
- [x] M2: render a page. `EmbeddedBrowser`, `BrowserView`, `AndroidView`,
      `WebViewClient`/`WebChromeClient`, basic settings, theme background. A widget
      with one `Browser` shows a page.
- [x] M3: lifecycle and tabs. Cached WebView survives `visible` toggles; widget
      stop destroys it; preview skip; no leaks across page changes.
- [x] M4: input. Tap-to-activate, tap-outside/Back deactivation, keyboard lift
      through `TerminalTiles`, terminal/browser focus exclusivity.
- [x] M5: navigation API. `web(...)` actions, `onNavigate`/`onTitle`/
      `onProgress`, loop guard, dispatch across a widget's pages.
- [ ] M6: docs and example. README and ARCHITECTURE are updated; the example face
      file, the manual matrix on a device and the release notes are still to do.

## Acceptance criteria

- A face at `page: -2` can declare `meta { owl: 18 }` and hold a `Terminal` and a
  `Browser` in two tabs, each full width and 60% of the screen height.
- `Browser` works with no Linux installed.
- Existing widgets and all current tests are unaffected.
- `./gradlew :owl:test` passes.
- `./gradlew :app:assembleDebug` builds.
- `owl-check` on a Browser file prints "compiles", skips the preview with a clear
  reason, and reports no errors.
- No JavaScript bridge, no file access, no cleartext, no non-HTTPS start URL.

## Risks and open questions

Security is the first one. A WebView on the home screen can draw a convincing fake login page.
Script off by default and https only help, but store review still has to treat a Browser as
untrusted, and a store warning ("this widget embeds a web browser") is worth considering.

A WebView consumes horizontal drags, so tap to activate is mandatory. Without it, page swipes
and the drawer break.

Two WebViews can be heavy on low-end phones. The two-page rule is the guard; if it still hurts,
lower it to one or destroy hidden pages after a timeout.

All widgets share the app's WebView profile, so a login in one Browser is visible in another.
That is normal WebView behaviour, and it is documented before the store takes Browser widgets.

Some sites refuse embedded WebViews or behave differently from Chrome. If that becomes a
blocker, a GeckoView backend can be added behind the same node and API.

A Browser file gets no `owl-check` picture in v1. If that hurts the authoring loop, add the
`onPageFinished` bitmap cache later.

`TerminalTiles` now handles browser focus too. Renaming it to something like `HomeTyping` is a
separate change, not part of this one.

## Alternatives considered

GeckoView is a real Firefox engine with better compatibility and process isolation. It is too
large for the first version, and stays an option behind the same node.

A Linux browser over `WindowManager.claim` reuses the existing Wayland `EmbeddedWindowView`, but
it is slow and heavy under proot, has no Android cookies, and its input is awkward.

An external browser (`url(...)`) costs nothing to implement, but it leaves the face and is not
an embedded tab. It stays the fallback for links the WebView refuses.
