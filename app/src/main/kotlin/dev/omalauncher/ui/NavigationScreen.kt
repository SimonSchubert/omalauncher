package dev.omalauncher.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.nav.BackSensitivity
import dev.omalauncher.nav.GestureLayout
import dev.omalauncher.nav.GestureNav
import dev.omalauncher.nav.SystemNavigation

/** Buttons or the launcher's own gestures, and how the gestures behave. Opened from settings. */
@Composable
fun NavigationScreen(
    nav: GestureNav,
    onDismiss: () -> Unit,
) {
    val settings by nav.settings.collectAsState()
    val running by nav.running.collectAsState()
    val system = systemNavigation()
    OverlayPanel("navigation", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            StyleOption("mode", listOf("buttons", "gestures"), if (settings.gestures) 1 else 0) { i ->
                nav.update { it.copy(gestures = i == 1) }
                // Android only lets the user turn an accessibility service on, in its settings.
                if (i == 1 && !running) nav.openAccessibilitySettings()
            }
            if (settings.gestures && !running) {
                Notice(
                    "Turn on “Omalauncher gestures” in Android's accessibility settings. It only draws the swipe strips " +
                        "and presses back, home and recents; it doesn't read the screen.",
                    "open",
                    Palette.accent,
                    nav::openAccessibilitySettings,
                )
            }
            if (settings.gestures) {
                SectionLabel("gestures")
                StyleOption("layout", GestureLayout.entries.map { it.label }, settings.layout.ordinal) { i ->
                    nav.update { it.copy(layout = GestureLayout.entries[i]) }
                }
                StyleOption("animation", listOf("on", "off"), if (settings.animation) 0 else 1) { i ->
                    nav.update { it.copy(animation = i == 0) }
                }
                StyleOption("recents", listOf("omalauncher", "phone's"), if (settings.ownRecents) 0 else 1) { i ->
                    nav.update { it.copy(ownRecents = i == 0) }
                }
                if (settings.layout == GestureLayout.SidesAndBottom) {
                    StyleOption("back sensitivity", BackSensitivity.entries.map { it.label }, settings.sensitivity.ordinal) { i ->
                        nav.update { it.copy(sensitivity = BackSensitivity.entries[i]) }
                    }
                    StyleOption("linux windows", listOf("back", "app"), if (settings.linuxKeepsSides) 1 else 0) { i ->
                        nav.update { it.copy(linuxKeepsSides = i == 1) }
                    }
                }
                Hint(
                    when (settings.layout) {
                        GestureLayout.SidesAndBottom ->
                            "Swipe in from either side for back. Swipe up from the bottom for home; swipe up and hold for recent apps." +
                                if (settings.linuxKeepsSides) " Linux windows keep the sides for themselves: there, back is only on the bottom." else ""
                        GestureLayout.BottomZones -> "Swipe up from the bottom: on the left for back, in the middle for home (hold for recent apps), on the right for recent apps."
                    },
                )
            }
            SectionLabel("phone's navigation bar")
            Notice(
                "now: ${system?.label ?: "unknown"}. " +
                    if (settings.gestures) {
                        "To hide its buttons, pick gesture navigation there. On Xiaomi and Poco this page doesn't put the " +
                            "launcher back to theirs; the phone may bring the buttons back after a restart."
                    } else {
                        "Where the phone allows it with other launchers (Pixel, Samsung), its own gestures work with Omalauncher."
                    },
                "change",
                Palette.muted,
                nav::openSystemNavigationSettings,
            )
        }
    }
}

/** A framed note with an action, like the updates rows in settings. */
@Composable
private fun Notice(
    text: String,
    action: String,
    color: Color,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, Modifier.weight(1f), style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Small))
        BracketAction(action, color, onAction)
    }
}

@Composable
private fun Hint(text: String) = BasicText(text, Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/** The phone's own navigation mode, following changes (made on the page this screen opens). */
@Composable
private fun systemNavigation(): SystemNavigation? {
    val context = LocalContext.current
    val mode = remember { mutableStateOf(SystemNavigation.read(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    mode.value = SystemNavigation.read(context)
                }
            }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(SystemNavigation.MODE_KEY), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return mode.value
}
