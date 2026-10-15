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
import dev.omalauncher.theme.ColorSpec
import dev.omalauncher.theme.Style

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
    val glass get() = background.copy(alpha = LauncherStyle.style[Style.WIDGET_OPACITY].toFloat())
}

/**
 * How the launcher draws itself, independent of the theme's colors: the
 * current [Style], which StyleStore sets, read where it's drawn.
 */
object LauncherStyle {
    var style by mutableStateOf(Style())

    /** App icons as silhouettes in the accent color (Omarchy) rather than their own colors. */
    val monochromeIcons get() = style[Style.MONOCHROME_ICONS]

    /** Home screen apps as bare icons on the wallpaper instead of framed tiles. */
    val bareAppIcons get() = !style[Style.APP_TILES]

    /** The strip of recently used apps above the bar on home. */
    val recentApps get() = style[Style.RECENT_APPS]

    /** The outline of tiles, the prompt bar and the rails. */
    val tileBorder: Color get() = color(style[Style.TILE_BORDER], Palette.border)

    /** The outline of the tile in use or being arranged. */
    val focusBorder: Color get() = color(style[Style.TILE_BORDER_FOCUSED], Palette.accent)

    /** How thick tiles' outlines are; 0 for none. */
    val borderWidth: Dp get() = style[Style.TILE_BORDER_WIDTH].toFloat().dp

    /** How thick full-screen panels' outlines are; 0 for none. */
    val panelBorderWidth: Dp get() = style[Style.SCREEN_BORDER_WIDTH].toFloat().dp

    /** How thick the focused tile's outline is: never so thin it can't be told apart. */
    val focusWidth: Dp get() = maxOf(borderWidth, 2.dp)

    /** What frames multiply their corner radius by; 0 is square. */
    val cornerScale get() = style[Style.CORNER_SCALE].toFloat()

    /** The shape frames use: square, or [radius] scaled by the corner style. */
    fun shape(radius: Dp = 16.dp): Shape = cornerScale.let { if (it == 0f) RectangleShape else RoundedCornerShape(radius * it) }

    /** [bar]'s band on home, or null without one. */
    fun barFill(bar: Style.Bar): Color? {
        if (!style[bar.framed]) return null
        return color(style[bar.background], Palette.background).copy(alpha = style[bar.opacity].toFloat())
    }

    /** How thick the line along [bar]'s band is; 0 for none. */
    fun barLineWidth(bar: Style.Bar): Dp = style[bar.borderWidth].toFloat().dp

    /** The line along [bar]'s band facing the tiles, or null for none. */
    fun barLine(bar: Style.Bar): Color? =
        when (val spec = style[bar.border]) {
            ColorSpec.NONE -> null
            ColorSpec.THEME -> tileBorder
            else -> color(spec, Palette.border)
        }

    /** A color setting's value: [theme] for "theme" or a color this theme lacks. */
    private fun color(
        spec: String,
        theme: Color,
    ): Color =
        when {
            spec == ColorSpec.THEME -> theme
            spec.startsWith("#") -> hexColor(spec) ?: theme
            else -> Palette.named(spec) ?: theme
        }
}

/** The Style menu's corner presets, from Omarchy's sharp edges to stock Android's round shapes; [scale] multiplies each frame's radius. */
enum class Corners(
    /** What launchers before style.toml saved. */
    val id: String,
    val label: String,
    val scale: Float,
) {
    Edge("edge", "edge", 0f),
    Soft("soft", "soft", 0.5f),
    Sleek("sleek", "sleek", 0.75f),
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
    /** Draws the blurred wallpaper under [fill]: home's surfaces over the wallpaper, see [frosted]. */
    frosted: Boolean = false,
): Modifier {
    val shape = LauncherStyle.shape(radius)
    // 0.dp is Compose's hairline, which border() still draws.
    return clip(shape).then(if (frosted) Modifier.frosted() else Modifier).background(fill, shape).then(if (width > 0.dp) Modifier.border(width, color, shape) else Modifier)
}

/**
 * Omarchy's gaps between framed surfaces, like Hyprland's gaps_in and gaps_out. [HomeGap] is between two of
 * home's tiles (the grid, the strip and the bar); [EdgeGap] is from them to the screen's safe edges; [PanelGap]
 * is from full-screen panels to those edges. The user's, from style.toml.
 */
val HomeGap: Dp get() = LauncherStyle.style[Style.GAPS_IN].toFloat().dp

/** See [HomeGap]. */
val EdgeGap: Dp get() = LauncherStyle.style[Style.GAPS_OUT].toFloat().dp

/** See [HomeGap]. */
val PanelGap: Dp get() = LauncherStyle.style[Style.SCREEN_GAPS_OUT].toFloat().dp

/** The widest a column of text and rows gets on a big screen: the bar beside landscape home, text-heavy panels. */
val ReadingWidth = 640.dp

/** A home-screen surface over the wallpaper: tiles, the prompt bar, the rails; outlined as focused while [focused]. */
fun Modifier.tile(
    focused: Boolean = false,
    /** The glass, see [Palette.glass]; a widget with an opacity of its own passes its own. */
    fill: Color = Palette.glass,
): Modifier =
    frame(
        if (focused) LauncherStyle.focusWidth else LauncherStyle.borderWidth,
        if (focused) LauncherStyle.focusBorder else LauncherStyle.tileBorder,
        fill,
        frosted = true,
    )

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
