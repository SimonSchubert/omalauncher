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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.agentInvocation
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.gestures.detectTapGestures

/**
 * How the prompt bar was summoned from outside it; Send asks the agent with what's typed (the panel's ask row),
 * Listen dictates into it (the landscape rail's mic).
 */
enum class PromptRequest { Type, Send, Listen }

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
    search: BarSearch,
    /** What Enter acts on: an index into the search hits, or -1 for the agent. */
    highlighted: Int,
    onLaunch: (AppHit) -> Unit,
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
        state.linuxOpen = true
    }

    fun send(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        if (!linux) return offerLinux()
        // Looked up now: speech results arrive after the bar may have recomposed.
        val target = runtime.agents.default
        if (target == null) {
            state.query = p
            state.agentsOpen = true
            return
        }
        runtime.terminal("${agentInvocation(target.bin, p)}; exec bash", target.name, target)
        state.query = ""
        focusManager.clearFocus()
        keyboard?.hide()
        state.closeNow()
    }

    /** Enter: the highlighted app, or the agent. */
    fun submit() {
        val hit = search.hits.getOrNull(highlighted)
        if (hit != null) onLaunch(hit) else send(text)
    }

    /** Moves the highlight up the panel (away from the bar) or back down; the ask row sits lowest. */
    fun pick(step: Int) {
        if (text.isBlank()) return
        state.picked = (highlighted + step).coerceIn(-1, search.hits.lastIndex)
    }

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
            null -> {}
        }
        state.promptRequest = null
    }
    // The panel and the bar open and close together: opening it (Super+Space, a widget) focuses the bar, closing it lets go.
    // Set to come on tap, the keyboard waits for a tap on the bar (or Super+A) instead.
    LaunchedEffect(panelShown) {
        // Opened to dictate, the keyboard stays down.
        if (panelShown && !speech.listening && LauncherStyle.drawerKeyboard) {
            focus.requestFocus()
            keyboard?.show()
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
    LaunchedEffect(frame, panelShown, gap) { if (frame > 0 && !panelShown) state.barHeight = frame + 2 * gap }
    // The whole bar and the gutter around it focus the field, not just the text: it's the main way in.
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }.padding(6.dp),
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
                AgentPicker(runtime, onManage = { if (linux) state.agentsOpen = true else offerLinux() })
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
                                // Tapped (or Super+A): the panel opens. Mid-swipe it's already showing.
                                if (it.isFocused && !panelShown) state.open()
                            }
                            // A hardware Enter submits; Shift+Enter starts a new line. Tab and the arrows move the highlight.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val oneLine = '\n' !in text
                                when {
                                    e.key == Key.Enter && !e.isShiftPressed -> submit()
                                    e.key == Key.Tab -> pick(if (e.isShiftPressed) -1 else 1)
                                    e.key == Key.DirectionUp && oneLine -> pick(1)
                                    e.key == Key.DirectionDown && oneLine -> pick(-1)
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
                                    listening -> "listening…"
                                    !linux || agent == null -> "open an app…"
                                    else -> "open an app or ask ${agent.bin}…"
                                }
                            BasicText(hint, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                        }
                    },
                )
                if (text.isNotBlank() && !listening) {
                    // Says what Enter does, so opening an app instead of asking is never a surprise.
                    Box(
                        Modifier.clickable(onClick = ::submit).padding(horizontal = 10.dp, vertical = 12.dp),
                    ) { BasicText(if (highlighted >= 0) "▸ open" else "⏎ ask", style = Mono.copy(color = Palette.accent)) }
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

/** The agent's name at the start of the bar; tapping it lists the installed agents to send prompts to. */
@Composable
private fun AgentPicker(
    runtime: Runtime,
    onManage: () -> Unit,
) {
    val store = runtime.agents
    val agent = store.default
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable { if (agent == null) onManage() else open = !open }
                .padding(start = 10.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(agent?.bin ?: "agent", style = Mono.copy(color = if (agent == null) Palette.muted else Palette.accent))
            Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
        }
        if (open) {
            Popup(
                popupPositionProvider = AboveAnchor,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 200.dp)
                        .frame(2.dp, Palette.accent, Palette.background)
                        .padding(4.dp),
                ) {
                    for (a in store.installed) {
                        val current = a.bin == agent?.bin
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    store.choose(a)
                                    open = false
                                }.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(if (current) "● " else "  ", style = Mono.copy(color = Palette.accent))
                            Column(Modifier.weight(1f)) {
                                BasicText(a.name, style = Mono.copy(color = if (current) Palette.accent else Palette.foreground))
                                BasicText(a.bin, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.border))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                open = false
                                onManage()
                            }.padding(horizontal = 10.dp, vertical = 10.dp),
                    ) { BasicText("  manage agents…", style = Mono.copy(color = Palette.muted)) }
                }
            }
        }
    }
}

/** Opens a popup just above its anchor, kept on screen: the bar sits at the bottom. */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
        (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
    )
}
