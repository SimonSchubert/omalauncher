package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.agentInvocation
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * How the prompt bar was summoned from outside it; Send asks the agent with what's typed (the panel's ask row),
 * Listen dictates into it (the landscape rail's mic).
 */
enum class PromptRequest { Type, Send, Listen, Release }

/** Height of the collapsed bar; the home grid leaves this much room for it. */
val PromptBarHeight = 48.dp

/**
 * The one input at the bottom of the home screen: it finds apps and asks agents.
 * Focusing it opens the app panel above it; typing narrows that panel, and Enter
 * opens the highlighted app or sends the text to the default agent in a terminal
 * (the send button says which). The mic always asks the agent: hold to talk and
 * release to send, tap to dictate into the field (it stops at the next pause).
 */
@Composable
internal fun PromptBar(
    runtime: Runtime,
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val speech = remember { Speech(context.applicationContext) }
    DisposableEffect(speech) { onDispose { speech.cancel() } }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val text = state.query
    var focused by remember { mutableStateOf(false) }
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    val agent = runtime.agents.default
    val linuxState by runtime.bootstrap.state.collectAsState()
    // Agents run in Linux: without it, the bar offers to install it instead.
    val linux = linuxState == LinuxState.Installed

    fun offerLinux() {
        focusManager.clearFocus()
        keyboard?.hide()
        state.closeNow()
        state.show(SettingsPage.Linux)
    }

    fun send(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        if (!linux) return offerLinux()
        // Looked up now: speech results arrive after the bar may have recomposed.
        val target = runtime.agents.default
        if (target == null) {
            state.query = p
            state.show(SettingsPage.Agents)
            return
        }
        runtime.terminal("${agentInvocation(target.bin, p)}; exec bash", target.name, target)
        state.query = ""
        focusManager.clearFocus()
        keyboard?.hide()
        state.closeNow()
    }

    /** The drawer's panel, while it shows: what's typed, Enter and the arrow keys are its. */
    fun drawer() = if (panelShown) runtime.widgets.drawerPanel()?.let { runtime.widgets.panelInstance(it)?.second } else null

    /** Enter: what the drawer does with it (open the app it picked), or, from a drawer without onSubmit, the agent. */
    fun submit() {
        if (drawer()?.submit(text) == true) state.query = "" else send(text)
    }

    /** The drawer gets [key] with its `onKey`, and moves its own highlight; false without one, and the key goes on. */
    fun drawerKey(key: String) = drawer()?.key(key) == true

    /** The agent picker over the bar, opened from the agent's name at its start. */
    var agentsOpen by remember { mutableStateOf(false) }

    fun manageAgents() = if (linux) state.show(SettingsPage.Agents) else offerLinux()

    /** Whether a finished recognition sends (hold-to-talk) or lands in the field for editing. */
    var sendWhenDone by remember { mutableStateOf(false) }

    fun listen(hold: Boolean) {
        focusManager.clearFocus()
        keyboard?.hide()
        sendWhenDone = hold
        val typed = text
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        speech.start(hold) { heard ->
            val full = listOf(typed, heard).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
            if (sendWhenDone && heard.isNotEmpty()) send(full) else state.query = full
        }
    }

    // A press that had to ask for the mic first can't still be held, so it dictates instead.
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) listen(hold = false)
        }

    fun talk(hold: Boolean) {
        if (!linux) return offerLinux()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen(hold)
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(state.promptRequest) {
        when (state.promptRequest) {
            PromptRequest.Type -> {
                focus.requestFocus()
                keyboard?.show()
            }
            PromptRequest.Send -> send(text)
            PromptRequest.Listen -> talk(hold = false)
            // A menu opened over the panel: the keyboard stays down from here on, rather than coming back as it closes.
            PromptRequest.Release -> {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            null -> {}
        }
        state.promptRequest = null
    }
    // The panel and the bar open and close together: opening it (Super+D, a widget) focuses the bar, closing it lets go.
    LaunchedEffect(panelShown) {
        // Opened to dictate, the keyboard stays down. So it does for a drawer with keyboard: false, though the bar still
        // takes focus, for a hardware keyboard to type into; focusing it can raise the soft one, so that's sent down again.
        if (panelShown && !speech.listening) {
            focus.requestFocus()
            if (runtime.widgets.drawerPanel()?.let { runtime.widgets.panelInstance(it) }?.second?.program?.keyboard != false) keyboard?.show() else keyboard?.hide()
        } else if (!panelShown && focused) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    BackHandler(enabled = speech.listening) { speech.cancel() }

    val listening = speech.listening
    // Followed in an effect and read only when drawing the level bar: the mic updates it many times a second,
    // and reading it here would recompose the whole bar each time.
    val level = remember { Animatable(0f) }
    LaunchedEffect(speech, listening) {
        snapshotFlow { if (listening) speech.level else 0f }.collectLatest { level.animateTo(it) }
    }
    // Measured rather than assumed (a large system font makes the bar taller), and passed on while home shows:
    // with the panel open the bar can grow by lines of text that home shouldn't make room for.
    var frame by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { HomeGap.roundToPx() }
    val edge = with(LocalDensity.current) { EdgeGap.roundToPx() }
    LaunchedEffect(frame, panelShown, gap, edge) { if (frame > 0 && !panelShown) state.barHeight = frame + gap + edge }
    // The whole bar and the gutter around it focus the field, not just the text: it's the main way in.
    Column(
        modifier
            .fillMaxWidth()
            // Up from the bar goes to the recent apps, or with none to home's tiles: Compose's own search passes over those,
            // as the pages span the whole screen, under the bar too, so they aren't above it. In the drawer, to its apps.
            .focusProperties { if (!panelShown && !(LauncherStyle.recentApps && recentApps(runtime, 1).isNotEmpty())) up = state.homeEntry }
            .pointerInput(Unit) {
                detectTapGestures {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }.padding(start = EdgeGap, top = HomeGap, end = EdgeGap, bottom = EdgeGap),
    ) {
        speech.error?.takeIf { !listening }?.let {
            BasicText(
                it,
                Modifier
                    // Takes no room: it shows over the recent apps strip rather than pushing it into the grid.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, 0) { p.place(0, -p.height) }
                    }.background(Palette.background)
                    .clickable { speech.error = null }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = Mono.copy(color = Palette.red, fontSize = TextSize.Small),
            )
        }
        Box(Modifier.onSizeChanged { frame = it.height }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = PromptBarHeight)
                    .tile(listening || focused)
                    .padding(start = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentName(runtime, agentsOpen, onToggle = { agentsOpen = !agentsOpen }, onManage = ::manageAgents)
                val shown = if (listening) speech.text else text
                BasicTextField(
                    value = shown,
                    onValueChange = {
                        if (!listening) state.query = it
                        speech.error = null
                    },
                    readOnly = listening,
                    maxLines = 5,
                    textStyle = Mono.copy(fontSize = TextSize.Item, color = if (listening) Palette.accent else Palette.foreground),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .onFocusChanged {
                                focused = it.isFocused
                                // Tapped (or Super+D): the panel opens. Mid-swipe it's already showing.
                                if (it.isFocused && !panelShown) state.open()
                            }
                            // Esc closes the drawer, as Omarchy's launcher does, before the soft keyboard can keep it to itself.
                            .onInterceptKeyBeforeSoftKeyboard { e ->
                                if (e.key != Key.Escape || !panelShown) return@onInterceptKeyBeforeSoftKeyboard false
                                if (e.type == KeyEventType.KeyUp) {
                                    focusManager.clearFocus()
                                    state.close()
                                }
                                true
                            }
                            // A hardware Enter submits; Shift+Enter starts a new line. Tab and the arrows go to the drawer, to move its highlight;
                            // with nothing typed there's none, and Tab or Up moves on into the panel, above, to what takes taps there.
                            // Shift+Tab goes back to the agent's name, and Right, with no text to move along, on to the mic.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val oneLine = '\n' !in text
                                when {
                                    e.key == Key.Enter && !e.isShiftPressed -> submit()
                                    text.isEmpty() && panelShown && (e.key == Key.Tab || e.key == Key.DirectionUp) ->
                                        focusManager.moveFocus(if (e.key == Key.Tab && e.isShiftPressed) FocusDirection.Previous else FocusDirection.Up)
                                    text.isEmpty() && e.key == Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                                    e.key == Key.Tab -> return@onPreviewKeyEvent drawerKey(if (e.isShiftPressed) "shift-tab" else "tab")
                                    e.key == Key.DirectionUp && oneLine -> return@onPreviewKeyEvent drawerKey("up")
                                    e.key == Key.DirectionDown && oneLine -> return@onPreviewKeyEvent drawerKey("down")
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                    // Padding inside the field, so the bar's full height takes taps.
                    decorationBox = { inner ->
                        Box(Modifier.padding(vertical = 12.dp)) {
                        if (shown.isEmpty()) {
                            val hint =
                                when {
                                    listening -> stringResource(R.string.prompt_listening)
                                    !linux || agent == null -> stringResource(R.string.prompt_hint_apps)
                                    else -> stringResource(R.string.prompt_hint_ask, agent.bin)
                                }
                            BasicText(hint, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                        }
                    },
                )
                // The drawer says what its Enter does, with its `enter`.
                val enter = drawer()?.enterHint() ?: stringResource(R.string.prompt_enter_ask)
                if (text.isNotBlank() && !listening && enter.isNotEmpty()) {
                    // Says what Enter does, so opening an app instead of asking is never a surprise.
                    Box(
                        Modifier.clickable(onClick = ::submit).padding(horizontal = 10.dp, vertical = 12.dp),
                    ) { BasicText(enter, style = Mono.copy(color = Palette.accent)) }
                }
                MicButton(
                    listening = listening,
                    // A tap dictates into the field and stops at the next pause; another tap stops it sooner.
                    onTap = { if (speech.listening) speech.stop() else talk(hold = false) },
                    onHold = { talk(hold = true) },
                    onRelease = { if (speech.listening) speech.stop() },
                    onCancel = {
                        speech.cancel()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
            }
            // Beside the bar's frame, not in it: the menu lines up with the frame, not with the agent's name inside.
            if (agentsOpen) AgentsMenu(runtime, onDismiss = { agentsOpen = false }, onManage = ::manageAgents)
            // Input level along the bottom edge while listening.
            if (listening) {
                val accent = Palette.accent
                Box(
                    Modifier.matchParentSize().drawBehind {
                        val h = 3.dp.toPx()
                        drawRect(accent, Offset(0f, size.height - h), Size(size.width * (0.05f + 0.95f * level.value), h))
                    },
                )
            }
        }
    }
}

/** How long a press on the mic waits before it counts as hold-to-talk rather than a tap or a passing swipe. */
private const val HOLD_MILLIS = 250L

/**
 * Tap to dictate, or hold to talk and release to send. Nothing starts until the press
 * is clearly one or the other: a swipe that only passes over the button (Back from
 * the screen edge, a page swipe) moves away first and is left alone.
 * Sliding well off the button while holding cancels, like voice messages in chat apps.
 * Keys tap it, ringed while they're on it.
 */
@Composable
private fun MicButton(
    listening: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        Modifier
            .size(PromptBarHeight)
            .background(if (listening) Palette.red else Color.Transparent)
            .keyActivated({ tap() }, null, ring = true)
            .pointerInput(Unit) {
                val slop = 64.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // true: released in time (a tap). false: moved, or taken by another gesture. null: still held.
                    val early =
                        withTimeoutOrNull<Boolean>(HOLD_MILLIS) {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                if (change.isConsumed) return@withTimeoutOrNull false
                                if (!change.pressed) return@withTimeoutOrNull true
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                            }
                            @Suppress("UNREACHABLE_CODE")
                            false
                        }
                    when (early) {
                        true -> tap()
                        false -> return@awaitEachGesture
                        null -> {
                            hold()
                            var cancelled = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    if (!cancelled) release()
                                    break
                                }
                                val p = change.position
                                val outside = p.x < -slop || p.y < -slop || p.x > size.width + slop || p.y > size.height + slop
                                if (outside && !cancelled) {
                                    cancelled = true
                                    cancel()
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { MicIcon(if (listening) Palette.background else Palette.foreground) }
}

/** A microphone drawn on a 24-unit grid, like the home screen's other icons. */
@Composable
internal fun MicIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(2 * u, cap = StrokeCap.Round)
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(9f * u, 2f * u),
            size = androidx.compose.ui.geometry.Size(6f * u, 12f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u),
            style = stroke,
        )
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(5f * u, 7f * u),
            size = androidx.compose.ui.geometry.Size(14f * u, 11f * u),
            style = stroke,
        )
        drawPath(
            Path().apply {
                moveTo(12f * u, 18f * u)
                lineTo(12f * u, 22f * u)
                moveTo(8f * u, 22f * u)
                lineTo(16f * u, 22f * u)
            },
            color,
            style = stroke,
        )
    }
}

/** The agent's name at the start of the bar; tapping it lists the installed agents to send prompts to ([AgentsMenu]). */
@Composable
private fun AgentName(
    runtime: Runtime,
    open: Boolean,
    onToggle: () -> Unit,
    onManage: () -> Unit,
) {
    val agent = runtime.agents.default
    Row(
        Modifier
            .clickable { if (agent == null) onManage() else onToggle() }
            .padding(start = 10.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(agent?.bin ?: stringResource(R.string.prompt_agent), style = Mono.copy(color = if (agent == null) Palette.muted else Palette.accent))
        Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
    }
}

/** The installed agents, to pick the one prompts go to, above the bar it's held next to and lined up with its start. */
@Composable
private fun AgentsMenu(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val store = runtime.agents
    // As far above the bar as the drawer's menus are above an app.
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    ChoicePopup(
        store.installed,
        store.installed.firstOrNull { it.bin == store.default?.bin },
        remember(gap) { AboveAnchor(gap) },
        onDismiss = onDismiss,
        onPick = store::choose,
        label = { it.name },
        minWidth = 200.dp,
        sublabel = { it.bin },
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.border))
        Box(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onDismiss()
                    onManage()
                }.padding(horizontal = 10.dp, vertical = 10.dp),
        ) { BasicText(stringResource(R.string.prompt_manage_agents), style = Mono.copy(color = Palette.muted)) }
    }
}

/** Opens a popup [gap] pixels above its anchor, lined up with its start and kept on screen: the bar sits at the bottom. */
private class AboveAnchor(
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
        (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0),
    )
}
