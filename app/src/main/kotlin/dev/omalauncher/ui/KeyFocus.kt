package dev.omalauncher.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize

/**
 * What a focused node holds of a key on it: whether its press was seen here, and whether holding it long-pressed. And
 * whether keys' focus is on it ([self]) or on it or something inside it ([within]).
 */
private class KeyHold {
    var down = false
    var longed = false
    var self = false
    var within = false
}

/** Keys that tap a focused node: a gamepad's A comes as itself, or as the D-pad's center where nothing takes it. */
internal val activateKeys = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar, Key.ButtonA)

/**
 * Lets a keyboard or a gamepad reach something that takes taps: it takes focus once keys are in use (not while
 * touching, like Compose's clickable), so Tab and the arrows move to it. Enter, Space, A or the D-pad's center taps it
 * as the key lifts; holding one, or Menu (a gamepad's Select), long-presses it. With [ring] it's ringed while it has
 * focus, at [radius]; without, [onFocus] follows its focus, for it to show that its own way.
 *
 * What takes taps inside it, like a widget's buttons on a home tile, is reached with the arrow along the reading
 * direction (Right, in English): Compose's arrows only move between neighbors, never into one. The arrow back from the
 * first thing inside comes out to it again, and [inside] gives focus to what's inside, as Enter on a tile with no tap
 * of its own does.
 */
@Composable
fun Modifier.keyActivated(
    onTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    inside: FocusRequester = remember { FocusRequester() },
    ring: Boolean = false,
    radius: () -> Float = { 4f * LauncherStyle.cornerScale },
    onFocus: (FocusState) -> Unit = {},
): Modifier {
    val inputMode = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val hold = remember { KeyHold() }
    var ringed by remember { mutableStateOf(false) }
    val self = remember { FocusRequester() }
    val refocus = remember { { self.requestFocus() } }
    return this
        .focusProperties { canFocus = inputMode.inputMode == InputMode.Keyboard }
        .onFocusChanged {
            if (!it.isFocused) hold.down = false
            hold.self = it.isFocused
            if (it.isFocused) lastFocused = java.lang.ref.WeakReference(refocus)
            hold.within = it.hasFocus
            if (ring) ringed = it.isFocused
            onFocus(it)
        }.onKeyEvent { e ->
            when {
                e.key in activateKeys && hold.self ->
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (e.nativeKeyEvent.repeatCount == 0) {
                                hold.down = true
                                hold.longed = false
                            } else if (hold.down && !hold.longed && onLongPress != null && onTap != null) {
                                hold.longed = true
                                onLongPress()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            // Only a press that started here: Enter that opened this screen lifts on it.
                            val ours = hold.down
                            if (ours && !hold.longed) (onTap ?: onLongPress)?.invoke()
                            hold.down = false
                            ours
                        }
                        else -> false
                    }
                e.key == Key.Menu && onLongPress != null -> {
                    if (e.type == KeyEventType.KeyUp) onLongPress()
                    true
                }
                e.key == (if (ltr) Key.DirectionRight else Key.DirectionLeft) && hold.self ->
                    e.type == KeyEventType.KeyDown && inside.requestFocus(if (ltr) FocusDirection.Right else FocusDirection.Left)
                // Back past the first thing inside: here, rather than on to the neighbor beyond.
                e.key == (if (ltr) Key.DirectionLeft else Key.DirectionRight) && hold.within && !hold.self -> {
                    if (e.type == KeyEventType.KeyDown) {
                        if (!focusManager.moveFocus(if (ltr) FocusDirection.Left else FocusDirection.Right) || !hold.within) self.requestFocus()
                    }
                    true
                }
                else -> false
            }
        }.focusRequester(self)
        .focusable()
        .focusRequester(inside)
        .focusGroup()
        .drawWithContent {
            drawContent()
            if (ringed) drawKeyFocus(radius())
        }
}

/**
 * Menu (a gamepad's Select) long-presses a combinedClickable, as it does what [keyActivated] makes take keys: Compose's
 * own only long-presses as Enter is held.
 */
fun Modifier.menuLongPresses(onLongPress: () -> Unit): Modifier =
    onKeyEvent { e ->
        if (e.key != Key.Menu) return@onKeyEvent false
        if (e.type == KeyEventType.KeyUp) onLongPress()
        true
    }

/**
 * For something that covers home, like a panel: keys' focus goes into it as it appears, while keys are in use (a touch
 * shouldn't focus its text field, raising the keyboard), and stays in it, so the arrows can't reach what's behind.
 * It starts on [start] where that's there, like a panel's ✕, rather than on a text field that keeps the arrows. An
 * arrow with nothing focused in the window, as after a tap, starts there again ([keysStartAgain]), back where it was.
 * When one over it closes, like a menu or a dialog, keys go back to what had them as it opened: in the newest one left,
 * or with none left, like a widget's button on home that opened a Popup.
 *
 * The drawer only wants the last: it opens from the bar, which keeps focus to type in, and the bar is outside it, so
 * [enters] and [traps] are false there.
 */
@Composable
fun Modifier.holdsKeys(
    start: FocusRequester? = null,
    enters: Boolean = true,
    traps: Boolean = true,
): Modifier {
    val inputMode = LocalInputModeManager.current
    val view = LocalView.current
    val window = LocalWindowInfo.current
    val screen = LocalKeysScreen.current ?: view.rootView
    val entry = remember { FocusRequester() }
    // Where keys are as it first shows, before anything in it can take them (a menu's own window gives its first node
    // focus by itself): in the newest one under it, or anywhere on the screen with none under it.
    val opener =
        remember {
            val under = holders.lastOrNull { it.screen === screen }
            lastFocused.takeIf { enters && (under?.focused ?: keysFocused(screen)) }
        }
    val holder = remember(view, screen, start, traps) { KeyHolder(view, screen, entry, start, traps, opener) }
    DisposableEffect(holder) {
        holders += holder
        onDispose {
            val newest = holders.lastOrNull() === holder
            holders -= holder
            // What's under it gets the keys back where they were: a panel gone from the window leaves Compose giving
            // them to the window's first node, and a menu's or a dialog's own window gives them back to none in particular.
            // Only on its own screen: one closing over another activity's leaves that one's keys alone.
            val under = holders.lastOrNull { it.screen === holder.screen }
            val opener = holder.opener
            if (under != null && opener != null) under.back = opener
            // What opened it first: entering the one under it only gets as far as its direct child, like the drawer's list.
            if (newest && inputMode.inputMode == InputMode.Keyboard) {
                screen.post { if (opener?.get()?.invoke() != true) under?.enter(first = true) }
            }
        }
    }
    LaunchedEffect(holder) {
        if (!enters) return@LaunchedEffect
        // A new window, like a screen's or a dialog's, knows whether keys are in use once it has focus, and Compose then
        // gives its first node focus by itself: going in after that, it starts where it should.
        snapshotFlow { window.isWindowFocused }.first { it }
        if (inputMode.inputMode != InputMode.Keyboard) return@LaunchedEffect
        // Content that isn't laid out yet takes it a frame later.
        if (!holder.enter(first = true)) withFrameNanos {}.also { holder.enter(first = true) }
    }
    return focusRequester(entry)
        .onFocusChanged { holder.focused = it.hasFocus }
        .focusProperties {
            onExit = {
                // Only the keys' moves stay in: focus asked for elsewhere, like the next settings page, goes.
                if (traps && requestedFocusDirection in keyMoves) cancelFocusChange() else entry.saveFocusedChild()
            }
        }.focusGroup()
}

/**
 * A [holdsKeys] showing in [view]'s window, on [screen] (its activity's, which a menu's or a dialog's window is on
 * too), and how keys go into it: back where they were, else at [start].
 */
private class KeyHolder(
    val view: View,
    val screen: View,
    private val entry: FocusRequester,
    private val start: FocusRequester?,
    val traps: Boolean,
    /** What had the keys as it opened, to give them back to as it closes. */
    val opener: java.lang.ref.WeakReference<() -> Boolean>?,
) {
    var focused = false

    /**
     * Gives the keys back to where they were in it as one over it in the same window took them, like a settings page
     * opened from a row: [restoreFocusedChild][FocusRequester.restoreFocusedChild] only reaches its direct children, not
     * a row in a list.
     */
    var back: java.lang.ref.WeakReference<() -> Boolean>? = null

    /** Whether keys are in it now. Not [first]: they stay where they are in it. */
    fun enter(first: Boolean = false): Boolean =
        !first && focused || entry.restoreFocusedChild() || back?.get()?.invoke() == true || start?.requestFocus() == true || entry.requestFocus()
}

/** How to give keys' focus back to what last had it, of what takes taps. */
private var lastFocused: java.lang.ref.WeakReference<() -> Boolean>? = null

/** The [holdsKeys] showing, newest last. */
private val holders = mutableListOf<KeyHolder>()

/** When keys' focus last left everything in each window, from its [keysRoot]. */
private val dropped = java.util.WeakHashMap<View, Long>()

/** The windows where something has keys' focus, from their [keysRoot]. */
private val focusedIn = java.util.WeakHashMap<View, Boolean>()

/** The windows whose keys [passesKeys] had nowhere to pass yet, for [takesPassedKeys]. */
private val waiting = java.util.WeakHashMap<View, Boolean>()

/** The windows where keys' focus is on something going, from when, until [passesKeys] has passed them on. */
private val passing = java.util.WeakHashMap<View, Long>()

/**
 * Around a window's content: notes whether anything in it has keys' focus ([keysFocused]), and when that leaves
 * everything in it, for [passesKeys], and keeps Compose from giving it straight to the window's first node meanwhile,
 * which would scroll there.
 */
@Composable
fun Modifier.keysRoot(): Modifier {
    val view = LocalView.current
    return onFocusChanged {
        focusedIn[view.rootView] = it.hasFocus
        if (!it.hasFocus) dropped[view.rootView] = SystemClock.uptimeMillis()
    }
        .focusProperties { onEnter = { if (view.rootView in passing) cancelFocusChange() } }
        .focusGroup()
}

/** A key went down in [window]: keys [passesKeys] had nowhere to pass go where it takes them, not to what shows later. */
fun keyPressed(window: View) {
    waiting -= window.rootView
    keyDownAt = SystemClock.uptimeMillis()
}

/** When a key last went down, for [focusFromKey]. */
private var keyDownAt = 0L

/**
 * Whether focus arriving now is a key's doing, rather than Android's own: a window's first layout gives its first node
 * focus by itself, keys in use or not.
 */
fun focusFromKey() = keyDownAt > 0 && SystemClock.uptimeMillis() - keyDownAt < 500

/**
 * Where keys' focus is on this as it goes, or as it stops taking focus, like a remove button swapped for its confirm or
 * a − that can't go lower, keys go on to the first of [to] that takes them, rather than being lost. Where none does,
 * as with an install button swapped for its progress, they wait for one that [takesPassedKeys], like the open it ends
 * with, unless a key goes down first.
 */
@Composable
fun Modifier.passesKeys(vararg to: FocusRequester): Modifier {
    val view = LocalView.current
    val had = remember { booleanArrayOf(false) }
    val targets = to.toList()
    val pass = remember { arrayOf<() -> Unit>({}) }
    pass[0] = {
        val at = SystemClock.uptimeMillis()
        val root = view.rootView
        passing[root] = at
        // Focus leaving the window's content with it, rather than moving on, is lost. What takes its place is laid out
        // in the same frame, so it can take focus once that's done.
        view.post {
            if (passing[root] == at) passing -= root
            if ((dropped[root] ?: -1) >= at && targets.none { it.requestFocus() }) waiting[root] = true
        }
    }
    DisposableEffect(Unit) { onDispose { if (had[0]) pass[0]() } }
    return onFocusChanged {
        if (had[0] && !it.hasFocus) pass[0]()
        had[0] = it.hasFocus
    }
}

/** Takes the keys [passesKeys] had nowhere to pass as this appears, if no key has gone down since. */
@Composable
fun Modifier.takesPassedKeys(): Modifier {
    val view = LocalView.current
    val self = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (waiting[view.rootView] == true && self.requestFocus()) waiting -= view.rootView
    }
    return focusRequester(self)
}

/** Whether anything in [window], under its [keysRoot], has keys' focus. */
fun keysFocused(window: View) = focusedIn[window.rootView] == true

/**
 * A key that moves focus, with nothing focused in [window], has nowhere to move from, as after a tap: it starts in
 * the newest [holdsKeys] there. So does one with focus left behind one that keeps the keys, as when a tap opened it.
 * Whether one took it.
 */
fun keysStartAgain(window: View): Boolean {
    val holder = holders.lastOrNull { it.view.rootView === window.rootView } ?: return false
    return (!keysFocused(window) || holder.traps && !holder.focused) && holder.enter()
}

/** Whether [keyCode] moves keys' focus: the arrows, and Tab. */
fun movesFocus(keyCode: Int) = keyCode in arrowKeys || keyCode == KeyEvent.KEYCODE_TAB

/**
 * For a one-line text field: Up and Down move keys' focus on, as Tab does, where there's something that way, rather
 * than the cursor to the start or the end.
 */
@Composable
fun Modifier.leavesUpDown(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { e ->
        val direction =
            when (e.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> return@onPreviewKeyEvent false
            }
        e.type == KeyEventType.KeyDown && focusManager.moveFocus(direction)
    }
}

/** The arrows, which move keys' focus. */
private val arrowKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

private val keyMoves = setOf(FocusDirection.Up, FocusDirection.Down, FocusDirection.Left, FocusDirection.Right, FocusDirection.Next, FocusDirection.Previous)

/**
 * Something keys move rather than press, like a slider or a drag handle: it takes focus once keys are in use, ringed
 * while it has it, and the arrows along it ([vertical] or not) call [onStep] with -1 or 1, and whether Shift asks for a
 * finer step, again as a held key repeats. Where [onStep] can't go further (false), the arrow moves focus on instead.
 */
@Composable
fun Modifier.keyStepped(
    vertical: Boolean,
    onStep: (by: Int, fine: Boolean) -> Boolean,
): Modifier {
    val inputMode = LocalInputModeManager.current
    var focused by remember { mutableStateOf(false) }
    return this
        .focusProperties { canFocus = inputMode.inputMode == InputMode.Keyboard }
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { e ->
            val by =
                when (e.key) {
                    if (vertical) Key.DirectionUp else Key.DirectionLeft -> -1
                    if (vertical) Key.DirectionDown else Key.DirectionRight -> 1
                    else -> return@onKeyEvent false
                }
            e.type != KeyEventType.KeyDown || onStep(by, e.isShiftPressed)
        }.focusable()
        .drawWithContent {
            drawContent()
            if (focused) drawKeyFocus()
        }
}

/**
 * Long text keys read through, like a license or a file: it takes focus once keys are in use, ringed while it has it,
 * and while some of it is out of sight the arrows that way bring the next of it into view, Page Up and Page Down a
 * screenful, rather than moving on. Once it's all shown that way, they move focus on. Whatever it's in scrolls, so
 * after a horizontalScroll of its own, Left and Right read across too. Bare text has its ring [around] it, clear of
 * the letters.
 */
@Composable
fun Modifier.keyScrolled(around: Boolean = false): Modifier {
    val inputMode = LocalInputModeManager.current
    val scope = rememberCoroutineScope()
    val requester = remember { BringIntoViewRequester() }
    val step = with(LocalDensity.current) { 48.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    val laid = remember { arrayOfNulls<Laid>(1) }
    return this
        .bringIntoViewRequester(requester)
        .then(LaidElement(laid))
        .focusProperties { canFocus = inputMode.inputMode == InputMode.Keyboard }
        .onFocusChanged { focused = it.isFocused }
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val at = laid[0]?.coordinates() ?: return@onKeyEvent false
            // What shows of it, in its own coordinates: its bounds in the window are cut to what it's in.
            val seen = at.boundsInWindow().translate(-at.positionInWindow())
            if (seen.isEmpty) return@onKeyEvent false
            val page = (seen.height - step).coerceAtLeast(step)
            val next =
                when (e.key) {
                    Key.DirectionDown -> Rect(seen.left, seen.bottom, seen.right, seen.bottom + step)
                    Key.DirectionUp -> Rect(seen.left, seen.top - step, seen.right, seen.top)
                    Key.PageDown -> Rect(seen.left, seen.bottom, seen.right, seen.bottom + page)
                    Key.PageUp -> Rect(seen.left, seen.top - page, seen.right, seen.top)
                    Key.DirectionRight -> Rect(seen.right, seen.top, seen.right + step, seen.bottom)
                    Key.DirectionLeft -> Rect(seen.left - step, seen.top, seen.left, seen.bottom)
                    else -> return@onKeyEvent false
                }.intersect(Rect(Offset.Zero, at.size.toSize()))
            // Less than a pixel left that way: it's all been shown.
            if (next.width < 1f || next.height < 1f) return@onKeyEvent false
            scope.launch { requester.bringIntoView(next) }
            true
        }.focusable()
        .drawWithContent {
            drawContent()
            if (focused) {
                val out = if (around) -6.dp.toPx() else 0f
                inset(out) { drawKeyFocus() }
            }
        }
}

/**
 * Where what it's on is laid out, as a key asks: onGloballyPositioned would be told on each frame of a scroll around it.
 */
private class Laid : Modifier.Node() {
    fun coordinates(): LayoutCoordinates? = if (isAttached) runCatching { requireLayoutCoordinates() }.getOrNull() else null
}

private class LaidElement(
    private val into: Array<Laid?>,
) : ModifierNodeElement<Laid>() {
    override fun create() = Laid().also { into[0] = it }

    override fun update(node: Laid) {
        into[0] = node
    }

    override fun equals(other: Any?) = other is LaidElement && other.into === into

    override fun hashCode() = System.identityHashCode(into)
}

/**
 * What keys have focus on shows it: a ring in the accent color over a tint of it, with the launcher's corners. A line in
 * the background color inside the ring keeps it apart from what it's around, a button filled with the accent too.
 */
fun DrawScope.drawKeyFocus(radius: Float = 4f * LauncherStyle.cornerScale) {
    val ring = 3.dp.toPx()
    val gap = 2.dp.toPx()
    val r = radius.dp.toPx()
    drawRoundRect(Palette.accent.copy(alpha = 0.15f), cornerRadius = CornerRadius(r))
    drawRoundRect(Palette.accent, Offset(ring / 2, ring / 2), Size(size.width - ring, size.height - ring), CornerRadius(r), style = Stroke(ring))
    val inset = ring + gap / 2
    val inner = CornerRadius((r - ring).coerceAtLeast(0f))
    drawRoundRect(Palette.background, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), inner, style = Stroke(gap))
}

/**
 * What a clickable shows: a press or a hover darkens it as Compose's own does, and keys' focus rings it
 * ([drawKeyFocus]) where Compose's barely tints it, so a keyboard or a gamepad can see where it is.
 */
private object KeyIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)

    override fun equals(other: Any?) = other === this

    override fun hashCode() = 0

    private class Node(
        private val source: InteractionSource,
    ) : Modifier.Node(),
        DrawModifierNode,
        FocusRequesterModifierNode {
        private val refocus = { isAttached && requestFocus() }

        private var pressed = 0
        private var hovered = 0
        private var focused = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed--
                        is HoverInteraction.Enter -> hovered++
                        is HoverInteraction.Exit -> hovered--
                        is FocusInteraction.Focus -> {
                            focused++
                            lastFocused = java.lang.ref.WeakReference(refocus)
                        }
                        is FocusInteraction.Unfocus -> focused--
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            when {
                pressed > 0 -> drawRect(Color.Black.copy(alpha = 0.3f))
                hovered > 0 -> drawRect(Color.Black.copy(alpha = 0.1f))
            }
            if (focused > 0) drawKeyFocus()
        }
    }
}

/** The activity's window that [holdsKeys] are on, menus' and dialogs' own windows too. */
private val LocalKeysScreen = staticCompositionLocalOf<View?> { null }

/** Gives every clickable in [content] the launcher's [KeyIndication], and its [holdsKeys] their activity's window. */
@Composable
fun KeyIndicated(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalIndication provides KeyIndication, LocalKeysScreen provides LocalView.current.rootView, content = content)
