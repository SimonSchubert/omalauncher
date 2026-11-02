# Screen lock — implementation plan

Status: proposed, nothing implemented yet.
Target: `copongcopong/omalauncher`, a new owl level (12).
Goal: let a widget hold the home screen still while it is being used, so a tap or a swipe on
it doesn't turn the page or open the drawer. A desktop window keeps its drags; a tile should
keep its swipes.

## Why

One page turn is a single sideways drag anywhere on home. A widget that wants a drag has to
fight the pager for it: a Browser and a Terminal get their touches only because the WebView
and the terminal consume the stream, and a face full of buttons or a widget that draws gets
nothing. The lock is the explicit version of what a focused page already does by accident.

## Decisions

- **An action, not a property.** `lock(true)` and `lock(false)`, called from a handler. A
  property on a node would tie the lock to one tile; an action lets a face decide when its
  own UI is in a mode that wants the screen held.
- **owl 12.** A file that asks for the lock must declare `meta { owl: 12 }`, so launchers
  older than this reject it instead of silently ignoring the call and turning pages anyway.
- **Home-wide, not tile-local.** Locking holds the pager, the swipe-up and long-press for
  the whole screen, because they are one gesture surface. A tile cannot lock only itself.
- **Owned by the widget that asked.** The lock is a set of widget instances, not a boolean:
  two widgets that both lock are both honoured, and one stopping cannot free a screen the
  other holds. `locked` is simply "the set is not empty".
- **Never persisted.** A restart, a reboot, a crash or a rebuild comes up unlocked. There is
  no way to boot into a locked home screen.
- **The chip always works.** The unlock button is drawn by the launcher, over everything,
  and is not something a widget can cover or disable. It is the guaranteed way out.

## Public API

```owl
widget "notes" {
  meta { owl: 12 }
  state editing = false
  Column {
    Button { text: editing ? "done" : "draw"; onTap: { editing = !editing; lock(editing) } }
    Canvas { weight: 1 }   // a drag that now belongs to the widget, not the pager
  }
}
```

| call | does |
|---|---|
| `lock(true)` | Holds home still until released. Asking twice is the same as asking once. |
| `lock(false)` | Releases this widget's hold. Other widgets' holds stay. |
| `lock(value)` | Any expression: a state, a comparison, a value from a source. |

A widget that never releases is not a trap: the lock ends when the widget does (see below),
and the chip goes with it.

## What "locked" turns off

| gesture | unlocked | locked |
|---|---|---|
| swipe left/right | turns the page | does nothing |
| swipe up | drawer, per gesture settings | does nothing |
| long-press a tile | lifts it, opens its menu | does nothing |
| tap the prompt bar, the recent strip | opens apps, switches | does nothing |
| tap the locked widget | the widget | the widget |
| system back gesture, notifications, status bar | Android's | Android's, unchanged |

The widget's own taps keep working: the lock is about home's gestures, not a touch shield.

## Release matrix

Released by any of:

| event | why |
|---|---|
| `lock(false)` from the widget that asked | the normal path |
| the widget stopping, being deleted or renamed | its process is gone; a hold with no owner is a bug |
| a launcher restart, a reboot, a crash | never persisted |
| the unlock button on the chip | the escape hatch |

Not in this version, and why: the plan first promised that a lock ends when its node leaves
composition, and when the locked tile loses focus (a tap outside it, another tile tapped).
Neither has a signal to hang on. Focus on home exists only for a terminal, a widget's text
field and a Browser page, so a plain widget that locks — the case this feature is for —
never loses focus in a way the launcher can see; and the lock is held by a widget while the
node that asked for it is one of its nodes, so a node's disposal cannot say whether the
widget is still working. The chip covers the gap. Both are worth doing when home grows a
real notion of which tile is active.

**Not** released by opening an app or Settings from the widget's own action: coming back finds
the screen as it was left. Android's own gestures (notification shade, recents, home) are
never intercepted; a user can always leave.

Back: the locked widget gets it first, as it does now (a page's history, a terminal's
control). If nothing consumes it, Back releases the lock and undoes nothing else, so a
focused tile never swallows Back twice.

## The cue

While locked, the launcher draws, over everything:

- a **2 dp border** around the screen edge, in `Palette.accent`, with `corners` applied, so it
  follows the theme and the corner style rather than being the one hard-cornered thing on
  screen;
- a **chip at top centre**: a lock glyph, the words `screen locked`, and a **tap target**.
  Tapping the chip's button releases the lock; tapping the chip's text does nothing, so a
  swipe passing over it cannot unlock by accident.

On a face, which fills the whole page, the border is drawn at 60% alpha so it reads as a
layer over the artwork rather than a frame around it. The chip is unchanged.

The chip must sit above the widget in the composition order and take its own taps, so a
widget that covers the tile area cannot cover the way out.

## Implementation

### 1. Language

`owl/src/main/kotlin/dev/omalauncher/widgets/lang/`

- `Schema.kt`: `SINCE["lock"] = 12`.
- `Builtins.kt`: `"lock" to 1..1`.
- `Compiler.kt`: `LEVEL = 12`; validate the argument as a boolean where it is constant, and
  `since("lock", call.pos)` at the call.
- `Program.kt`: nothing: the lock is not a compile-time property of the file.

### 2. Runtime

`app/src/main/kotlin/dev/omalauncher/widgets/runtime/WidgetInstance.kt`

- an `onLock: (Boolean) -> Unit` alongside `onScreen`, passed in the constructor and given a
  no-op default, so tests and previews need nothing;
- `act()` intercepts `lock` before `services.act(...)`, coerces the first argument with the
  same truthiness the other builtins use, calls `onLock`, and reports a clear message when
  the widget is in a preview (where there is no home screen to hold).

### 3. Home

`app/src/main/kotlin/dev/omalauncher/ui/TerminalTiles.kt` — it already owns focus, lift and
the keyboard, so it owns the lock too:

- `private val lockedBy = mutableStateMapOf<Any, Unit>()` keyed by the widget instance
  (`mutableStateSetOf` does not exist; a map with unit values is the same thing);
- `val locked: Boolean` derived from it being non-empty;
- `fun lock(owner: Any, on: Boolean)` adds or removes, and `fun unlockAll()` for the chip;
- `deactivate()` and the widget's disposal remove that widget's entry.

`app/src/main/kotlin/dev/omalauncher/ui/Home.kt`

- `homeSwipe(enabled = !terminals.typing && ... && !terminals.locked)`;
- the prompt bar and the recent strip ignore taps while `locked`;
- Back: the existing `BackHandler(enabled = terminals.typing)` stays first; a second
  `BackHandler(enabled = terminals.locked)` calls `unlockAll()`.

`app/src/main/kotlin/dev/omalauncher/ui/HomeGrid.kt`

- `HorizontalPager(..., userScrollEnabled = !locked)`;
- the long-press arm returns early while locked.

### 4. The cue

New `app/src/main/kotlin/dev/omalauncher/ui/LockCue.kt`: a `@Composable LockCue(onUnlock)`
drawing the border and the chip, placed in `Home.kt`'s root `Box` after the pages and before
the gesture-time overlays that must stay above it (none today). It uses `Palette.accent`,
`corners`, the launcher's existing padding and text styles.

### 5. Wiring

`WidgetServices.kt` / `WidgetEngine.kt`: pass `onLock = { on -> terminals.lock(instance, on) }`
where `onScreen` is passed today, and make the same call sites drop the hold when a widget is
stopped or its file removed. In a preview, the callback stays absent and `lock(...)` reports
`a preview has no home screen to lock` rather than failing silently.

## Tests

- `LangTest`: `lock(true)` without `meta { owl: 12 }` fails with the owl-12 message; with it,
  compiles; `lock()` with no argument fails like other arity errors; the call is listed as an
  action for the reviewer.
- `WidgetInstanceBrowserTest` or a new `WidgetInstanceLockTest`: `lock(true)` calls `onLock`
  once and `lock(false)` calls it with false; a preview reports instead of calling.
- Home tests (JVM, no device): a locked `TerminalTiles` reports `locked`; two owners, one
  releasing, still locked; `unlockAll()` clears both; a widget's disposal removes only its
  own hold.

## Acceptance criteria

- A widget that calls `lock(true)` freezes page turns, the swipe-up and long-press, and the
  chip and border appear.
- `lock(false)`, the chip, Back with nothing else to consume it, the widget stopping, its
  file being removed and a launcher restart each release the lock.
- Two widgets locking: releasing one leaves the other held.
- Nothing changes for widgets that never call `lock`.
- `./gradlew :owl:test :app:testDebugUnitTest :app:assembleDebug` passes on pop480, and the
  release matrix is checked on the device.

## Risks

- **A user stuck behind a widget.** Covered twice: the chip is drawn by the launcher and is
  not coverable, and the lock never survives a restart. The chip is the design's safety
  property, not a nicety.
- **A widget that locks on load and forgets.** Legal, and the chip is the answer. Worth a
  README line saying a lock should be tied to a mode the user can see, not to a page load.
- **Lock and a terminal's own Back.** Both want Back. Order is defined above: typing first,
  then lock.
- **Faces.** The border over artwork is a taste call; the alternative is chip-only, which
  reads as "frozen" rather than "locked".
- **Naming.** `TerminalTiles` now owns focus, lift, the keyboard and the lock. A rename to
  something like `HomeFocus` is a separate refactor.
