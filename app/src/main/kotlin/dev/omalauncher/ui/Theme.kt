package dev.omalauncher.ui

import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.R

/** `#rrggbb` or `#aarrggbb` as a color; null if it isn't one. */
fun hexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

/** A theme's colors, parsed once when it's applied rather than on every read. */
@Immutable
class ThemePalette(
    colors: Map<String, String>,
) {
    /** Every color from the theme's colors.toml by name (accent, red, yellow, ...). */
    val named: Map<String, Color> = colors.mapNotNull { (k, v) -> hexColor(v)?.let { k to it } }.toMap()

    private fun color(
        key: String,
        fallback: Long,
    ) = named[key] ?: Color(fallback)

    val isLight = colors["mode"] == "light"
    val background = color("background", 0xFF1A1B26)
    val surface = color("lighter_background", 0xFF24283B)
    val overlay = background.copy(alpha = 0.9f)
    val foreground = color("foreground", 0xFFC0CAF5)
    val muted = color("dark_foreground", 0xFF565F89)
    val accent = color("accent", 0xFF7AA2F7)
    val border = color("muted", 0xFF414868)
    val red = color("red", 0xFFF7768E)
    val green = color("green", 0xFF9ECE6A)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = TextSize.Body, color = foreground)
}

/**
 * Launcher colors, driven by the active Omarchy theme. Until one is applied
 * they're the defaults above, which match Tokyo Night.
 */
object Palette {
    var current by mutableStateOf(ThemePalette(emptyMap()))
        private set

    fun apply(theme: Map<String, String>) {
        current = ThemePalette(theme)
    }

    /** Puts back a palette taken from [current], after drawing in other colors for a while. */
    fun restore(palette: ThemePalette) {
        current = palette
    }

    fun named(name: String): Color? = current.named[name]

    val isLight get() = current.isLight
    val background get() = current.background
    val surface get() = current.surface
    val overlay get() = current.overlay
    val foreground get() = current.foreground
    val muted get() = current.muted
    val accent get() = current.accent
    val border get() = current.border
    val red get() = current.red
    val green get() = current.green

    /** The translucent fill of tiles, bars and rails over the wallpaper. */
    val glass get() = background.copy(alpha = 0.88f)
}

/** How the launcher draws itself, independent of the theme's colors. Saved by ThemeStore. */
object LauncherStyle {
    /** App icons as silhouettes in the accent color (Omarchy) rather than their own colors. */
    var monochromeIcons by mutableStateOf(false)

    /** Home screen apps as bare icons on the wallpaper instead of framed tiles. */
    var bareAppIcons by mutableStateOf(false)

    /** The strip of recently used apps above the bar on home. */
    var recentApps by mutableStateOf(true)

    /** Opening the app drawer brings up the keyboard too; otherwise only a tap on the bar does. */
    var drawerKeyboard by mutableStateOf(true)

    /** The app drawer lists apps pinned to home (on their own or in a folder) too; search finds them either way. */
    var homeAppsInDrawer by mutableStateOf(true)

    /** How frames (tiles, panels, menus, fields) round their corners. */
    var corners by mutableStateOf(Corners.Edge)

    /** The shape frames use: square, or [radius] scaled by the corner style. */
    fun shape(radius: Dp = 16.dp): Shape = if (corners == Corners.Edge) RectangleShape else RoundedCornerShape(radius * corners.scale)
}

/** Corner styles, from Omarchy's sharp edges to stock Android's round shapes; [scale] multiplies each frame's radius. */
enum class Corners(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
    val scale: Float,
) {
    Edge("edge", "edge", 0f),
    Soft("soft", "soft", 0.5f),
    Round("round", "round", 1f),
    ;

    companion object {
        /** Also takes the enum names ("Round") launchers before versioning saved. */
        fun of(id: String?) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Edge
    }
}

/** A framed surface in the user's corner style: clipped, filled, then outlined with one shape. */
fun Modifier.frame(
    width: Dp,
    color: Color,
    fill: Color = Color.Transparent,
    radius: Dp = 16.dp,
): Modifier {
    val shape = LauncherStyle.shape(radius)
    return clip(shape).background(fill, shape).border(width, color, shape)
}

/**
 * Omarchy's gap between framed surfaces: between home's tiles, from them to the screen's
 * safe edges, and from full-screen panels to those edges, so a panel's frame lines up with the tiles'.
 */
val HomeGap = 6.dp

/** The widest a column of text and rows gets on a big screen: the bar beside landscape home, text-heavy panels. */
val ReadingWidth = 640.dp

/** A home-screen surface over the wallpaper: tiles, the prompt bar, the rails; accent while [focused]. */
fun Modifier.tile(focused: Boolean = false): Modifier = frame(2.dp, if (focused) Palette.accent else Palette.border, Palette.glass)

/** The launcher's text style in the theme's foreground color. */
val Mono get() = Palette.current.mono

/**
 * The launcher's text sizes. Nothing is smaller than [Small], which is still
 * readable on a phone at arm's length; Android's font size scales them all.
 */
object TextSize {
    /** Second lines, statuses, hints and labels under icons. */
    val Small = 12.sp

    /** Everything else; [Mono]'s size. */
    val Body = 14.sp

    /** List items and menu entries. */
    val Item = 15.sp

    /** Fields and dialog text. */
    val Large = 16.sp

    /** Screen and dialog titles. */
    val Title = 18.sp
}

/**
 * The few Nerd Font glyphs omarchy uses that Android lacks: weather (U+E300–E3E3, see
 * weatherIcon()) plus the launcher's own [Glyph]s. A subset of Symbols Nerd Font 3.5.1, made
 * with pyftsubset; Weather Icons by Erik Flowers, SIL OFL 1.1; Material Design Icons, Apache 2.0.
 */
val NerdSymbols = FontFamily(Font(R.font.nerd_symbols))

/** The Material Design Icons in [NerdSymbols] the launcher draws with [Symbol]. */
object Glyph {
    /** nf-md-check 󰄬 */
    const val Check = "\uDB80\uDD2C"

    /** nf-md-close 󰅖 */
    const val Close = "\uDB80\uDD56"

    /** nf-md-chevron_down 󰅀 */
    const val ChevronDown = "\uDB80\uDD40"

    /** nf-md-chevron_right 󰅂 */
    const val ChevronRight = "\uDB80\uDD42"

    /** nf-md-chevron_up 󰅃 */
    const val ChevronUp = "\uDB80\uDD43"
}

/** Status/navigation bar icons follow the theme's light or dark mode. */
fun applySystemBars(
    activity: androidx.activity.ComponentActivity,
    light: Boolean,
) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style =
        if (light) {
            androidx.activity.SystemBarStyle.light(transparent, transparent)
        } else {
            androidx.activity.SystemBarStyle.dark(transparent)
        }
    activity.enableEdgeToEdge(style, style)
}
