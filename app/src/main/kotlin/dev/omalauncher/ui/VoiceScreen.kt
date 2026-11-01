package dev.omalauncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.max as maxDp
import androidx.compose.ui.unit.min as minDp
import dev.omalauncher.AppLanguage
import dev.omalauncher.R
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.JobState
import dev.omalauncher.Runtime
import dev.omalauncher.voice.VoiceSession
import dev.omalauncher.voice.VoiceSession.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The voice screen: a spoken conversation with a coding agent ([VoiceSession]). It listens as it opens; a tap on
 * the orb talks again or talks over the reply. The agent (a tap picks another) heads it; the orb shows what's
 * happening (listening to your voice's level, speaking, working, waiting for an OK) with a word under it; the
 * conversation runs up from the bottom, where the step in progress, any trouble, stop and the switch to typing
 * are; and an agent's question slides up above them.
 */
@Composable
fun VoiceScreen(
    runtime: Runtime,
    /** Counts the times it was opened: each one listens. */
    opens: Int,
    /** In sight; out of it, it stops listening. */
    shown: Boolean,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val session = runtime.voice
    val speaker = runtime.speaker
    val speech = remember { Speech(context.applicationContext) { speaker.locale } }
    val haptics = LocalHapticFeedback.current
    var viewing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var bottomHeight by remember { mutableIntStateOf(0) }
    val field = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val typing = session.typing

    DisposableEffect(Unit) { onDispose { speech.cancel() } }
    LaunchedEffect(Unit) { speaker.warmUp() }
    // Looked at again each time it comes back: a recognizer may have been installed or picked meanwhile.
    val canHear = remember(shown) { speech.available }
    LaunchedEffect(shown) { if (!shown) speech.cancel() }
    // An agent installed or removed while it's open.
    LaunchedEffect(session.voiceAgents.map { it.bin }) { session.wake() }

    fun listen() {
        speaker.stop()
        speech.start(hold = false) { heard -> if (heard.isNotBlank()) session.ask(heard) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) listen() }

    fun talk() {
        if (speech.listening) return speech.stop()
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Opening it is asking to talk: listen once the agent is up. With no agent there's no one to hear it.
    LaunchedEffect(opens) {
        while (session.phase == Phase.Starting) delay(100)
        if (session.agent != null && canHear && !typing && !speech.listening) talk()
    }
    // Typing: the keyboard comes up, as opening to talk listens.
    LaunchedEffect(typing, session.agent != null) { if (typing && session.agent != null) runCatching { field.requestFocus() } }

    fun send() {
        if (draft.isBlank()) return
        session.ask(draft)
        draft = ""
    }

    fun switchTyping() {
        if (!typing) {
            speech.cancel()
            session.type(true)
        } else {
            session.type(false)
            focus.clearFocus()
            if (canHear) talk()
        }
    }
    LaunchedEffect(session.approvals.firstOrNull()) { if (session.approvals.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val agentName = session.agent?.name ?: "agent"
    val now by produceState(System.currentTimeMillis(), session.busy) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = if (session.busy && session.since > 0) ((now - session.since) / 1000).let { "%d:%02d".format(it / 60, it % 60) } else ""
    // Under the orb: what it's doing, or what a tap on it does. The agent's name is at the top.
    val status =
        when {
            speech.listening -> stringResource(if (session.busy) R.string.voice_listening_working else R.string.voice_listening)
            session.phase == Phase.Starting -> stringResource(R.string.voice_starting)
            session.approvals.isNotEmpty() -> stringResource(R.string.voice_needs_ok, elapsed)
            session.busy -> stringResource(R.string.voice_working, elapsed)
            typing -> ""
            speaker.speaking -> stringResource(R.string.voice_tap_over)
            else -> stringResource(R.string.voice_tap)
        }

    // Newest first: the list runs from the bottom.
    val items =
        buildList {
            if (speech.listening && speech.text.isNotBlank()) add(VoiceSession.Entry("you", speech.text))
            if (session.reply.isNotBlank()) add(VoiceSession.Entry("agent", session.reply))
            addAll(session.transcript.asReversed())
        }
    val talking = items.isNotEmpty()
    // An agent picked that isn't installed yet has the screen: the one before (and its conversation) is left.
    val installing = session.installing
    val linux by runtime.bootstrap.state.collectAsState()
    val ready = session.agent != null && installing == null && (canHear || typing)
    val yellow = Palette.named("yellow") ?: Palette.accent
    // Landscape: the orb at the side and the conversation next to it. Asked of the window, not of the space left:
    // the keyboard coming up mustn't turn a phone held upright into landscape.
    val wide = LocalWindowInfo.current.containerSize.let { it.width > it.height }

    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            // Who it talks to (a tap picks another), and what holds for the whole conversation.
            // As high with the buttons as without them (an install, no agent): the name doesn't move.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    // With Linux there, every agent Voice talks to: picking one that isn't installed installs it.
                    val options = if (linux == LinuxState.Installed) session.linked else session.voiceAgents
                    val installed = session.voiceAgents
                    val many = options.size > 1
                    val name = (installing ?: session.agent)?.name ?: stringResource(R.string.activity_voice)
                    val pickable = many && !session.busy && !speech.listening
                    BasicText(
                        if (many) "$name ▾" else name,
                        Modifier
                            .clip(LauncherStyle.shape(8.dp))
                            .clickable(enabled = pickable) { picking = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .graphicsLayer { alpha = if (pickable || !many) 1f else 0.5f },
                        style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (picking) {
                        val install = stringResource(R.string.agents_install)
                        val installingNow = stringResource(R.string.linux_job_installing) + "…"
                        ChoicePopup(
                            options = options,
                            selected = installing ?: session.agent,
                            position = BelowAnchorStart,
                            onDismiss = { picking = false },
                            onPick = {
                                speaker.stop()
                                if (it in installed) {
                                    session.switchTo(it)
                                } else {
                                    speech.cancel()
                                    session.install(it)
                                }
                            },
                            label = { it.name },
                            minWidth = 160.dp,
                            sublabel = { agent ->
                                when {
                                    agent in installed -> null
                                    runtime.agents.states[agent.bin] is JobState.Working -> installingNow
                                    else -> install
                                }
                            },
                        )
                    }
                }
                if (ready) Chip((if (session.approveAll) "● " else "○ ") + stringResource(R.string.voice_allow_all), if (session.approveAll) yellow else Palette.muted) { session.approveEverything(!session.approveAll) }
                // A new conversation puts this one aside, so one tap is enough: resume brings it back.
                if (ready && !session.busy) {
                    if (session.transcript.isNotEmpty()) {
                        TopIcon(Icons.Plus, stringResource(R.string.voice_new)) {
                            speaker.stop()
                            session.startOver()
                        }
                    } else if (session.canResume) {
                        Chip(stringResource(R.string.voice_resume), Palette.accent) {
                            speech.cancel()
                            session.resume()
                        }
                    }
                }
                // The language, voice and speed, last in the row.
                if (ready) {
                    TopIcon(Icons.Gear, stringResource(R.string.voice_voice)) {
                        // Not listening while samples play: it would hear them.
                        speech.cancel()
                        tuning = true
                    }
                }
            }

            if (installing != null) {
                // Its install, in the middle, then a retry if it failed.
                val state = session.installState
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { VoiceIcon(Palette.muted) }
                    Column(Modifier.widthIn(max = CONVERSATION)) {
                        if (state is JobState.Working) BasicText("${stringResource(state.action)}…", style = Mono.copy(color = Palette.accent))
                        JobLog(state)
                        if (state is JobState.Failed) BracketAction(stringResource(R.string.home_retry), Palette.accent) { session.install(installing) }
                    }
                }
            } else if (!canHear && !typing && session.agent != null) {
                Missing(Modifier.weight(1f).fillMaxWidth(), stringResource(R.string.voice_no_speech), stringResource(R.string.voice_input_settings), stringResource(R.string.voice_type) to { switchTyping() }) {
                    val settings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(settings) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            } else if (session.agent == null) {
                Missing(Modifier.weight(1f).fillMaxWidth(), stringResource(R.string.voice_no_agent), stringResource(R.string.voice_agents)) { runtime.openSettings(SettingsPage.Agents) }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Typing: a small sign of what it's doing, out of the keyboard's way.
                    val most = if (typing) 96f else 250f
                    val orb = (if (wide) min(min((maxHeight - CAPTION).value * 0.8f, maxWidth.value * 0.36f), most) else min(min(maxWidth.value * 0.62f, maxHeight.value * 0.42f), most)).dp
                    val unit = orb + CAPTION
                    // The conversation no wider than reads well (a tablet), beside the orb in landscape, the two
                    // centred together; its own 16dp padding is the gap to the orb and the edge.
                    val list = minDp(maxWidth - if (wide) orb + 32.dp else 0.dp, CONVERSATION)
                    val left = if (wide) maxDp(16.dp, (maxWidth - orb - 16.dp - list) / 2) else 0.dp
                    // In the middle until there's a conversation, then above it (beside it in landscape).
                    val orbTop by animateDpAsState(if (talking && !wide) 0.dp else ((maxHeight - unit) / 2).coerceAtLeast(0.dp), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "orb")
                    val orbStart by animateDpAsState(if (talking && wide) left else (maxWidth - orb) / 2, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "orbX")
                    if (talking) {
                        LazyColumn(
                            Modifier
                                .fillMaxHeight()
                                .padding(top = if (wide) 0.dp else unit)
                                .then(if (wide) Modifier.offset(x = left + orb + 16.dp) else Modifier.align(Alignment.TopCenter))
                                .width(list),
                            reverseLayout = true,
                            // At the bottom, by the controls, while it's short.
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            items(items.size) { i -> Message(items[i]) { viewing = it } }
                        }
                    }
                    val touch = remember { MutableInteractionSource() }
                    val pressed by touch.collectIsPressedAsState()
                    Column(
                        Modifier
                            .offset { IntOffset(orbStart.roundToPx(), orbTop.roundToPx()) }
                            .width(orb)
                            .clickable(interactionSource = touch, indication = null, enabled = !typing) { talk() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Orb(session = session, speech = speech, speaking = speaker.speaking, pressed = pressed, modifier = Modifier.size(orb))
                        // Up into the orb's box: it's drawn smaller than its box until it listens, and the ring
                        // stays clear of this even then.
                        Box(Modifier.height(CAPTION).offset(y = -orb * 0.1f), contentAlignment = Alignment.TopCenter) {
                            // Wider than a small orb (typing) when it needs to be.
                            BasicText(status, Modifier.wrapContentWidth(unbounded = true), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small, textAlign = TextAlign.Center), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // Below: what it's doing, what went wrong, and the controls. The approval card comes up over the rest.
            if (ready) {
                Column(Modifier.fillMaxWidth().onSizeChanged { bottomHeight = it.height }) {
                    // What it's doing now; a tap shows the steps before.
                    if (session.busy && session.approvals.isEmpty()) {
                        val steps = session.steps
                        Column(Modifier.fillMaxWidth().clickable { showSteps = !showSteps }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            if (showSteps) {
                                for (s in steps.dropLast(1).takeLast(6)) BasicText("  " + s.trim(), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText("● ", style = Mono.copy(color = yellow))
                                BasicText(steps.lastOrNull()?.trim() ?: stringResource(R.string.voice_thinking), Modifier.weight(1f), style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (steps.size > 1) BasicText(pluralStringResource(R.plurals.voice_steps, steps.size, steps.size), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                            }
                        }
                    }
                    // Trouble in red; replies that show but can't be heard (no text-to-speech) say so in grey.
                    val trouble = session.notice ?: speech.error
                    val silent = if (!typing) speaker.problem else null
                    (trouble ?: silent)?.let { BasicText(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = Mono.copy(color = if (trouble != null) Palette.red else Palette.muted, fontSize = TextSize.Small)) }
                    // Not signed in: the agent in its own terminal, which walks through it.
                    val agent = session.agent
                    if (agent != null && session.notice != null && session.needsLogin) {
                        BracketAction(stringResource(R.string.voice_log_in), Palette.accent, Modifier.padding(start = 8.dp)) {
                            speech.cancel()
                            session.loggingIn()
                            runtime.terminal(agent.command, agent.name, agent)
                        }
                    }

                    val stoppable = session.busy || (!typing && speaker.speaking)
                    fun stop() {
                        speech.cancel()
                        session.stop()
                    }
                    if (typing) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Back to talking.
                            if (canHear) {
                                Box(Modifier.size(44.dp).clip(LauncherStyle.shape(22.dp)).clickable { switchTyping() }, contentAlignment = Alignment.Center) { MicIcon(Palette.accent) }
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }
                            val style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item)
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                maxLines = 5,
                                textStyle = style,
                                cursorBrush = SolidColor(Palette.accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { send() }),
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .focusRequester(field)
                                        // A hardware Enter sends too; Shift+Enter is a new line.
                                        .onPreviewKeyEvent {
                                            if (it.key != Key.Enter || it.isShiftPressed) return@onPreviewKeyEvent false
                                            if (it.type == KeyEventType.KeyDown) send()
                                            true
                                        }
                                        .clip(LauncherStyle.shape(20.dp))
                                        .background(Palette.surface)
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (draft.isEmpty()) BasicText(if (session.approvals.isEmpty()) stringResource(R.string.voice_message, agentName) else stringResource(R.string.voice_answer_hint), style = style.copy(color = Palette.muted))
                                        inner()
                                    }
                                },
                            )
                            // Nothing typed while it works: the button stops it instead.
                            if (draft.isBlank() && stoppable) BracketAction(stringResource(R.string.voice_stop), Palette.red) { stop() } else BracketAction(stringResource(R.string.voice_send), if (draft.isBlank()) Palette.muted else Palette.accent) { send() }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            // The orb's tap stops listening; stop is for the agent and its voice.
                            if (!speech.listening && stoppable) BracketAction(stringResource(R.string.voice_stop), Palette.red, Modifier.align(Alignment.Center)) { stop() }
                            Chip(stringResource(R.string.voice_type), Palette.accent, Modifier.align(Alignment.CenterEnd)) { switchTyping() }
                        }
                    }
                }
            }
        }

        // An agent's question, from the bottom, above the controls: an answer can be typed there.
        val ask = session.approvals.firstOrNull()
        val above = if (ready) with(LocalDensity.current) { bottomHeight.toDp() } else 0.dp
        AnimatedVisibility(ask != null, Modifier.align(Alignment.BottomCenter).padding(bottom = above).widthIn(max = 560.dp), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            val a = ask ?: return@AnimatedVisibility
            Column(Modifier.padding(12.dp).card(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val showDetail = a.detail.isNotEmpty() && a.detail != a.label
                // The command shows whole below, so the sentence doesn't start it again.
                val what =
                    if (showDetail && a.act.verb == dev.omalauncher.voice.Act.Verb.Run && a.act.what.isNotEmpty() && a.detail.startsWith(a.act.what)) {
                        stringResource(R.string.voice_do_command)
                    } else {
                        a.said
                    }
                BasicText(stringResource(R.string.voice_wants, agentName, what), style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
                // A long command scrolls, so the answers below stay on a short (landscape) screen.
                if (showDetail) Box(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) { CodeBlock(a.detail) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Answer(stringResource(R.string.voice_allow), Palette.green, filled = true) { session.answer(a.id, true) }
                    Answer(stringResource(R.string.voice_always), Palette.accent) { session.answer(a.id, true, always = true) }
                    Answer(stringResource(R.string.voice_deny), Palette.red) { session.answer(a.id, false) }
                }
                BasicText(stringResource(if (typing) R.string.voice_or_type else R.string.voice_or_say), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }

        // A picture, whole: pinch to zoom, tap to close.
        viewing?.let { path -> ImageViewer(path) { viewing = null } }
        if (tuning) {
            VoiceSettings(session, speaker) {
                tuning = false
                speaker.stop()
            }
        }
    }
}

/**
 * The language Voice listens and reads aloud in, the voice it reads in and how fast: each pick plays a sample. The
 * languages are the text-to-speech engine's (and the launcher's), the voices its own for the language picked.
 */
@Composable
private fun VoiceSettings(
    session: VoiceSession,
    speaker: Speaker,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    LaunchedEffect(Unit) { speaker.warmUp() }
    // Read again once the engine is up: before, it can't say what it has.
    val ready = speaker.ready
    val language = session.language
    val launcher = configuration.locales[0]
    val shown = language ?: launcher.language
    val languages =
        remember(ready) {
            (speaker.languages() + AppLanguage.supported.map { Locale.forLanguageTag(it).language })
                .distinct()
                .sortedBy { AppLanguage.name(it).lowercase(launcher) }
        }
    val voices = remember(ready, shown) { speaker.voices(shown) }

    /** A sentence in the language replies are read in: its own words when the launcher has them, else its name. */
    fun sample() {
        val locale = speaker.locale
        val text = AppLanguage.stringIn(context, locale, R.string.voice_sample) ?: AppLanguage.name(locale.toLanguageTag())
        speaker.stop()
        speaker.speak(text)
    }

    fun use(
        language: String? = session.language,
        voice: String? = session.voice,
        rate: Float = session.rate,
    ) {
        session.useVoice(language, voice, rate)
        sample()
    }

    DialogFrame(onDismiss) {
        BasicText(stringResource(R.string.voice_voice), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))

        var pickLanguage by remember { mutableStateOf(false) }
        Setting(stringResource(R.string.voice_language)) {
            Box {
                Pick(if (language == null) stringResource(R.string.voice_language_launcher, AppLanguage.name(launcher.language)) else AppLanguage.name(language)) { pickLanguage = true }
                if (pickLanguage) {
                    ChoicePopup(
                        options = listOf<String?>(null) + languages,
                        selected = language,
                        position = BelowAnchorStart,
                        onDismiss = { pickLanguage = false },
                        // Another language's voices are other voices.
                        onPick = { use(language = it, voice = null) },
                        label = { it?.let(AppLanguage::name) ?: resources.getString(R.string.voice_language_launcher, AppLanguage.name(launcher.language)) },
                    )
                }
            }
        }

        if (voices.isNotEmpty()) {
            var pickVoice by remember { mutableStateOf(false) }
            // Numbered in each region: the engine's names (de-de-x-deb-local) say nothing to most. A voice and its
            // online twin (de-de-x-deb-network) share the number.
            val labels =
                remember(voices) {
                    val numbers = HashMap<String, Int>()
                    val count = HashMap<String, Int>()
                    voices.associate { v ->
                        val region = v.locale.getDisplayCountry(launcher).ifEmpty { AppLanguage.name(v.locale.language) }
                        val twin = region + "|" + v.name.substringAfter("-x-", v.name).substringBeforeLast('-')
                        val n = numbers.getOrPut(twin) { (count[region] ?: 0).plus(1).also { count[region] = it } }
                        v.name to (if (v.isNetworkConnectionRequired) "$region $n · ${resources.getString(R.string.voice_online)}" else "$region $n")
                    }
                }
            val current = voices.firstOrNull { it.name == session.voice }
            Setting(stringResource(R.string.voice_voice)) {
                Box {
                    Pick(current?.let { labels[it.name] } ?: stringResource(R.string.voice_default)) { pickVoice = true }
                    if (pickVoice) {
                        ChoicePopup(
                            options = listOf<android.speech.tts.Voice?>(null) + voices,
                            selected = current,
                            position = BelowAnchorStart,
                            onDismiss = { pickVoice = false },
                            onPick = { use(voice = it?.name) },
                            label = { v -> v?.let { labels[it.name] } ?: resources.getString(R.string.voice_default) },
                            sublabel = { v -> v?.name.orEmpty() },
                        )
                    }
                }
            }
        }

        Setting(stringResource(R.string.voice_speed)) {
            Row {
                for (r in RATES) {
                    Chip("${r.toString().removeSuffix(".0")}×", if (r == session.rate) Palette.accent else Palette.muted) { use(rate = r) }
                }
            }
        }

        if (!speaker.reads(speaker.locale)) {
            BasicText(stringResource(R.string.voice_no_voice, AppLanguage.name(speaker.locale.language)), style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
        }
        // The engine, its voices' downloads and its own defaults. Back or a tap outside closes this.
        BracketAction(stringResource(R.string.voice_tts_settings), Palette.accent) {
            val settings = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/** How fast replies can be read; 1 is the engine's normal speed. */
private val RATES = listOf(0.8f, 1f, 1.25f, 1.5f)

/** A setting in [VoiceSettings]: its name, and what it's set to. */
@Composable
private fun Setting(
    name: String,
    value: @Composable () -> Unit,
) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    BasicText(name, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    value()
}

/** What a setting is set to; a tap picks another. */
@Composable
private fun Pick(
    text: String,
    onClick: () -> Unit,
) = BasicText(
    "$text ▾",
    Modifier.clip(LauncherStyle.shape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item),
)

/** The status line's room under the orb. */
private val CAPTION = 28.dp

/** The conversation's widest, its 16dp padding in: 560dp of text, as the approval card, a readable line on a tablet. */
private val CONVERSATION = 592.dp

/** One of the approval card's three answers, all the same size: [filled] for the one most taps want. */
@Composable
private fun RowScope.Answer(
    label: String,
    color: Color,
    filled: Boolean = false,
    onClick: () -> Unit,
) = Box(
    Modifier
        .weight(1f)
        .clip(LauncherStyle.shape(8.dp))
        .then(if (filled) Modifier.background(color) else Modifier.border(1.dp, color.copy(alpha = 0.6f), LauncherStyle.shape(8.dp)))
        .clickable(onClick = onClick)
        .padding(vertical = 12.dp),
    contentAlignment = Alignment.Center,
) { BasicText(label, style = Mono.copy(color = if (filled) Palette.background else color)) }

/** One line of the conversation. */
@Composable
private fun Message(
    e: VoiceSession.Entry,
    onImage: (String) -> Unit,
) {
    when (e.who) {
        "you" ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                BasicText(
                    e.text,
                    Modifier.widthIn(max = 300.dp).clip(LauncherStyle.shape(14.dp)).background(Palette.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = Mono.copy(color = Palette.foreground),
                )
            }
        "note" -> BasicText("· " + e.text, Modifier.fillMaxWidth(), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
        "image" -> {
            val file = e.text.substringBefore('|')
            val name = e.text.substringAfterLast('|').substringAfterLast('/')
            val bitmap = rememberBitmap(file, 600)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(LauncherStyle.shape(10.dp)).background(Palette.surface).clickable { onImage(file) }) {
                    bitmap?.let { Image(it, name, Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit) }
                        ?: Spacer(Modifier.height(120.dp))
                }
                BasicText(name, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        }
        else -> BasicText(e.text.ifBlank { "…" }, Modifier.fillMaxWidth(), style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
    }
}

/** What's happening, as a shape: it follows your voice while listening, breathes while speaking, and is ringed while working. */
@Composable
private fun Orb(
    session: VoiceSession,
    speech: Speech,
    speaking: Boolean,
    pressed: Boolean,
    modifier: Modifier,
) {
    val yellow = Palette.named("yellow") ?: Palette.accent
    val waiting = session.approvals.isNotEmpty()
    val color by animateColorAsState(
        when {
            speech.listening -> Palette.green
            waiting -> Palette.red
            // The theme's accent, unless it's the red of waiting for an OK or the green of listening.
            speaking -> Palette.accent.takeIf { it != Palette.red && it != Palette.green } ?: Palette.named("blue") ?: Palette.accent
            session.busy -> yellow
            else -> Palette.muted
        },
        tween(300),
        label = "color",
    )
    val size by animateFloatAsState(
        when {
            speech.listening -> 0.84f + 0.16f * speech.level
            speaking -> 0.86f
            waiting -> 0.8f
            session.busy -> 0.74f
            else -> 0.7f
        },
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "size",
    )
    val working = session.busy && !waiting
    val active = speech.listening || speaking || session.busy || waiting
    // Idle it's a hollow ring; anything going on fills it.
    val fill by animateFloatAsState(if (active) 1f else 0f, tween(450), label = "fill")
    // A finger on it: it gives a little and lights up at once, before the tap does anything.
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    val level by animateFloatAsState(if (speech.listening) speech.level else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "level")
    val loop = rememberInfiniteTransition(label = "orb")
    val turn by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "turn")
    val breath by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val ripple by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ripple")
    Canvas(modifier.graphicsLayer { scaleX = size * (1f - 0.07f * press); scaleY = size * (1f - 0.07f * press) }) {
        val fill = max(fill, 0.45f * press)
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = min(this.size.width, this.size.height) / 2 - 10.dp.toPx()
        val swell = if (speaking || waiting) breath else 0f
        val core = r * 0.72f * (1f + 0.035f * swell)
        // A soft light around it, brighter as it speaks or listens louder.
        val glow = 0.06f + fill * (0.14f + 0.10f * swell + 0.16f * level) + 0.10f * press
        drawCircle(Brush.radialGradient(0.55f to color.copy(alpha = glow), 1f to Color.Transparent, center = c, radius = r), r, c)
        // Listening: rings leave the core and fade, stronger the louder you are.
        if (speech.listening) {
            for (k in 0..1) {
                val p = (ripple + k * 0.5f) % 1f
                val a = (1f - p) * (1f - p) * (0.25f + 0.55f * level)
                drawCircle(color.copy(alpha = a), core + (r - core) * p, c, style = Stroke(1.5.dp.toPx()))
            }
        }
        // The core: lit from the top, a touch deeper at the bottom.
        drawCircle(
            Brush.linearGradient(listOf(lerp(color, Color.White, 0.22f), color, lerp(color, Color.Black, 0.14f)), Offset(c.x - core * 0.5f, c.y - core), Offset(c.x + core * 0.5f, c.y + core)),
            core, c, alpha = fill,
        )
        drawCircle(color.copy(alpha = 0.05f * (1f - fill)), core, c)
        drawCircle(color.copy(alpha = 1f - fill), core, c, style = Stroke(1.5.dp.toPx()))
        // Working: one comet circling just outside, its tail fading away.
        if (working) {
            val ring = r * 0.9f
            rotate(turn, c) {
                drawArc(
                    // Starts past 0°, so the tail's round cap sits in the clear part, not where the sweep wraps to yellow.
                    Brush.sweepGradient(0f to yellow.copy(alpha = 0f), 0.04f to yellow.copy(alpha = 0f), 0.72f to yellow, 0.75f to yellow, center = c),
                    15f, 255f, false, Offset(c.x - ring, c.y - ring), Size(ring * 2, ring * 2),
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun ImageViewer(
    path: String,
    onClose: () -> Unit,
) {
    val bitmap = rememberBitmap(path, 2000)
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .pointerInput(Unit) {
                detectTransformGestures { _, move, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    pan = if (scale == 1f) Offset.Zero else pan + move
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.Fit)
        }
        BasicText(stringResource(R.string.voice_tap_close), Modifier.align(Alignment.BottomCenter).padding(16.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), )
    }
}

/** A picture file, decoded off the main thread at about [maxSide] pixels. */
@Composable
private fun rememberBitmap(
    path: String,
    maxSide: Int,
): ImageBitmap? =
    produceState<ImageBitmap?>(null, path) {
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
    }.value

/** An icon in the top row that does something, [label] for screen readers. */
@Composable
private fun TopIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) = Box(
    Modifier.size(44.dp).clip(LauncherStyle.shape(22.dp)).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Image(icon, label, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Palette.muted)) }

/** A small word in the top row that does something. */
@Composable
private fun Chip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = BasicText(
    text,
    modifier.clip(LauncherStyle.shape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    style = Mono.copy(color = color, fontSize = TextSize.Small, textAlign = TextAlign.Center),
)

/** What Voice can't do without ([text]: no agent, no speech recognition), [action] to get it, and maybe [instead]. */
@Composable
private fun Missing(
    modifier: Modifier,
    text: String,
    action: String,
    instead: Pair<String, () -> Unit>? = null,
    onAction: () -> Unit,
) = Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { VoiceIcon(Palette.muted) }
    BasicText(
        text,
        Modifier.widthIn(max = 420.dp),
        style = Mono.copy(color = Palette.foreground, textAlign = TextAlign.Center),
    )
    Row {
        BracketAction(action, Palette.accent, onClick = onAction)
        instead?.let { (label, go) -> BracketAction(label, Palette.muted, onClick = go) }
    }
}
