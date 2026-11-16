package dev.omalauncher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.theme.Style
import dev.omalauncher.widgets.render.WidgetKeybar
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One row's height, keycap and the gap around it; the stock extra keys are two rows, like Termux's default. */
internal val KeyRowHeight = 40.dp
internal val KeyGap = 3.dp
internal val FrameWidth = 2.dp

/**
 * The stock key bar's full height at the user's keybar.gaps-out and keybar.border-width, its gap to the keyboard
 * included: what home lifts a typing tile by until it has measured the bar.
 */
val ExtraKeysHeight: Dp
    get() =
        KeyRowHeight * 2 + (LauncherStyle.style[Style.KEYBAR_BORDER_WIDTH].toFloat().dp + KeyGap) * 2 +
            LauncherStyle.style[Style.KEYBAR_GAPS_OUT].toFloat().dp

/**
 * Whether the extra-keys row belongs on screen: only over a docked soft
 * keyboard. With a hardware keyboard, Gboard shows just a floating toolbar
 * that counts as visible but takes no height, and the hardware keyboard has
 * these keys already.
 */
@Composable
fun extraKeysWanted(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // Derived, so the keyboard's slide-in animation doesn't recompose every frame.
    val docked by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return docked
}

/**
 * The keys a phone keyboard lacks, docked above it for terminals: keybar.owl (`keybar "…" { }`), else the stock ones.
 * Their nodes never take focus, so the soft keyboard stays up; `pressKey(...)` and `latch(...)` reach [Runtime.keyboard].
 */
@Composable
fun ExtraKeys(
    runtime: Runtime,
    modifier: Modifier = Modifier,
) {
    val widgets = runtime.widgets
    // Before the file runs too, so it's live as soon as it does.
    DisposableEffect(widgets) {
        widgets.keybarShown(true)
        onDispose { widgets.keybarShown(false) }
    }
    val inst = widgets.keybarInstance() ?: return
    WidgetKeybar(inst, modifier.fillMaxWidth())
}

/** The extra keys at the bottom of a screen, where the keyboard would push them up, for owl-check's picture of them. */
@Composable
internal fun KeybarPreview(inst: WidgetInstance) {
    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
        WidgetKeybar(inst, Modifier.fillMaxWidth())
    }
}

/**
 * A keycap that acts on touch down, and keeps acting while held when it [repeats]:
 * the editor's extra keys. Shows [icon] turned by [rotation], else [label].
 */
@Composable
internal fun RepeatingKeyCap(
    label: String,
    icon: ImageVector?,
    rotation: Float,
    repeats: Boolean,
    modifier: Modifier,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    KeyCap(
        modifier.pointerInput(label, repeats) {
            presses { scope ->
                pressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val job =
                    scope.launch {
                        press()
                        if (!repeats) return@launch
                        delay(RepeatDelay)
                        while (true) {
                            press()
                            delay(RepeatInterval)
                        }
                    }
                waitForUpOrCancellation()
                job.cancel()
                pressed = false
            }
        },
        fill = if (pressed) Palette.accent else Palette.surface,
    ) {
        val color = if (pressed) Palette.background else Palette.foreground
        if (icon != null) {
            Image(icon, label, Modifier.size(20.dp).rotate(rotation), colorFilter = ColorFilter.tint(color))
        } else {
            BasicText(label, style = Mono.copy(color = color), maxLines = 1)
        }
    }
}

@Composable
internal fun KeyCap(
    modifier: Modifier,
    fill: Color,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = LauncherStyle.shape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (outline != null) Modifier.border(FrameWidth, outline, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Runs [press] for each touch on the key, with a scope to start key repeat or long-press timers in. */
internal suspend fun PointerInputScope.presses(press: suspend AwaitPointerEventScope.(CoroutineScope) -> Unit) =
    coroutineScope {
        val scope = this
        awaitEachGesture {
            awaitFirstDown()
            press(scope)
        }
    }

/** How long a key is held before it repeats, and how often it repeats then: the editor's keys and owl's `repeats`. */
internal const val RepeatDelay = 400L
internal const val RepeatInterval = 50L
