package dev.omalauncher.theme

import androidx.compose.runtime.Immutable

/**
 * One setting in style.toml. [key] is its place in the file ("widgets.gaps-in");
 * it's what agents and users write, so renaming one takes a migration (see
 * [StyleFile.MIGRATIONS]). Adding one takes nothing: a file without it gets [default].
 */
@Immutable
sealed class Setting<T>(
    val key: String,
    val default: T,
    /** What defaults.toml says about it. */
    val doc: String,
) {
    /** The file's value as this setting's, or [IllegalArgumentException] saying what it takes. */
    abstract fun decode(value: Any): T

    class Flag(
        key: String,
        default: Boolean,
        doc: String,
    ) : Setting<Boolean>(key, default, doc) {
        override fun decode(value: Any) = value as? Boolean ?: throw IllegalArgumentException("takes true or false")
    }

    class Number(
        key: String,
        default: Double,
        val range: ClosedFloatingPointRange<Double>,
        doc: String,
    ) : Setting<Double>(key, default, doc) {
        override fun decode(value: Any): Double {
            val v = value as? Double
            require(v != null && v in range) { "takes a number from ${Toml.format(range.start)} to ${Toml.format(range.endInclusive)}" }
            return v
        }
    }

    /** Free text, unset by default: which theme, which background. */
    class Name(
        key: String,
        doc: String,
    ) : Setting<String?>(key, null, doc) {
        override fun decode(value: Any): String? = nonBlank(value, "takes a name in quotes")
    }

    class Count(
        key: String,
        default: Int,
        val range: IntRange,
        doc: String,
    ) : Setting<Int>(key, default, doc) {
        override fun decode(value: Any): Int {
            val v = value as? Double
            require(v != null && v == Math.rint(v) && v.toInt() in range) { "takes a whole number from ${range.first} to ${range.last}" }
            return v.toInt()
        }
    }

    class Text(
        key: String,
        default: String,
        doc: String,
    ) : Setting<String>(key, default, doc) {
        override fun decode(value: Any): String = nonBlank(value, "takes text in quotes")
    }

    protected fun nonBlank(
        value: Any,
        takes: String,
    ): String = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException(takes)

    /** A color: [ColorSpec.THEME], a theme color's name or #rrggbb, and with [noneAllowed] [ColorSpec.NONE]. */
    class Color(
        key: String,
        doc: String,
        val noneAllowed: Boolean = false,
    ) : Setting<String>(key, ColorSpec.THEME, doc) {
        override fun decode(value: Any): String {
            val v = (value as? String)?.trim()?.lowercase()
            require(v != null && ColorSpec.isValid(v, noneAllowed)) {
                "takes \"theme\"${if (noneAllowed) ", \"none\"" else ""}, a theme color (accent, red, color4, …) or \"#rrggbb\""
            }
            return v
        }
    }
}

/** How a color setting's value reads; resolved against the theme when drawing. */
object ColorSpec {
    /** What the launcher draws without the setting. */
    const val THEME = "theme"
    const val NONE = "none"

    private val HEX = Regex("#[0-9a-f]{6}")

    /** The names every theme has once [ThemeColors.resolve] ran, plus the optional ones Omarchy's themes use. */
    val NAMES: Set<String> =
        buildSet {
            addAll(listOf("background", "foreground", "accent", "cursor", "muted", "selection", "selection_background", "selection_foreground"))
            addAll(listOf("light_foreground", "bright_foreground", "dark_foreground", "lighter_background", "dark_background", "darker_background"))
            for (c in listOf("red", "green", "yellow", "blue", "magenta", "purple", "cyan")) {
                add(c)
                add("bright_$c")
            }
            addAll(listOf("orange", "brown"))
            for (i in 0..15) add("color$i")
        }

    fun isValid(
        v: String,
        noneAllowed: Boolean,
    ) = v == THEME || (noneAllowed && v == NONE) || v in NAMES || HEX.matches(v)
}

/** The launcher's style: every [Setting]'s value, read from style.toml. [with] copies, so nothing changes one once made. */
@Immutable
class Style(
    private val values: Map<String, Any?> = emptyMap(),
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(setting: Setting<T>): T = if (setting.key in values) values[setting.key] as T else setting.default

    fun <T> with(
        setting: Setting<T>,
        value: T,
    ) = Style(values + (setting.key to value))

    /** Each of [settings] set to its [value]: settings the Style menu changes together, like both bars'. */
    fun <T, S : Setting<T>> with(
        settings: List<S>,
        value: (S) -> T,
    ) = settings.fold(this) { acc, s -> acc.with(s, value(s)) }

    /** The value [settings] share, or null where style.toml sets them apart. */
    fun <T : Any> common(settings: List<Setting<T>>): T? = settings.map { this[it] }.distinct().singleOrNull()

    override fun equals(other: Any?) = other is Style && StyleFile.ALL.all { this[it] == other[it] }

    override fun hashCode() = StyleFile.ALL.map { this[it] }.hashCode()

    /** The settings that differ between this and [other]. */
    fun changed(other: Style) = StyleFile.ALL.filter { this[it] != other[it] }.map { it.key }.toSet()

    companion object {
        /** How much tiles and the system bars' bands cover the wallpaper. */
        const val GLASS = 0.88

        val THEME = Setting.Name("theme", "The Omarchy theme, by its directory name in ~/.local/share/omarchy/themes or ~/.config/omarchy/themes (yours win).")
        val BACKGROUND = Setting.Name("background", "A file name from the theme's backgrounds/ or ~/.config/omarchy/backgrounds/<theme>/. Unset: the theme's first.")

        val APP_TILES = Setting.Flag("home.app-tiles", true, "Apps on home in a tile like a widget; false draws bare icons on the wallpaper.")
        val RECENT_APPS = Setting.Flag("home.recent-apps", true, "The strip of recently used apps above the prompt bar.")
        val COLUMNS = Setting.Count("home.columns", 6, 4..9, "Columns of home's grid. Fewer make bigger cells, and apps show their names under their icons once a cell is tall enough.")
        val FORCE_SINGLE_PAGE =
            Setting.Flag(
                "home.single-page",
                false,
                "One page even on a screen shaped like an unfolded foldable's; false shows two there, auto-detected from the screen's shape and width.",
            )

        val GAPS_IN = Setting.Number("widgets.gaps-in", 6.0, 0.0..24.0, "Space in dp between two of home's widgets (all of it, unlike Hyprland's gaps_in, which each window has on its side).")
        val GAPS_OUT = Setting.Number("widgets.gaps-out", 6.0, 0.0..48.0, "Space in dp from home's widgets, the strip and the prompt bar to the screen's edges.")
        val TILE_BORDER_WIDTH = Setting.Number("widgets.border-width", 2.0, 0.0..8.0, "How thick widgets' outlines are, in dp. 0 draws none, but the focused one's.")
        val TILE_BORDER = Setting.Color("widgets.border", "The outline of widgets, the prompt bar and the rails: \"theme\" is the theme's border color.")
        val TILE_BORDER_FOCUSED = Setting.Color("widgets.border-focused", "The outline of the widget in use or being arranged: \"theme\" is the theme's accent.")

        val SCREEN_GAPS_OUT = Setting.Number("screens.gaps-out", 6.0, 0.0..48.0, "Space in dp from full-screen panels (settings, style, the stores) to the screen's edges.")
        val SCREEN_BORDER_WIDTH = Setting.Number("screens.border-width", 2.0, 0.0..8.0, "How thick full-screen panels' outlines are, in dp.")

        val KEYBAR_GAPS_OUT = Setting.Number("keybar.gaps-out", 6.0, 0.0..48.0, "Space in dp from the terminal key bar's frame to the screen's sides and the keyboard.")
        val KEYBAR_BORDER_WIDTH = Setting.Number("keybar.border-width", 2.0, 0.0..8.0, "How thick the terminal key bar's frame is, in dp. 0 draws none.")

        val MONOCHROME_ICONS = Setting.Flag("icons.monochrome", false, "App icons as silhouettes in the accent color, as on Omarchy, rather than in their own colors.")

        val CORNER_SCALE = Setting.Number("corners.scale", 0.0, 0.0..2.0, "Multiplies every frame's corner radius: 0 square (Omarchy), 0.5 soft, 1 round like stock Android.")

        val STATUS_BAR = Bar("status-bar", "Android's status bar at the top of home")
        val STATUS_BAR_SHOWN = Setting.Flag("status-bar.shown", true, "Android's status bar on home; false hides it until a swipe down from the top edge.")
        val NAVIGATION_BAR = Bar("navigation-bar", "Android's gesture or button bar on home")

        /** [FONT]'s value for Android's own monospace font. */
        const val SYSTEM_FONT = "system"

        val FONT = Setting.Text("font", SYSTEM_FONT, "The font family of the launcher's text and widgets: \"system\" is Android's monospace; fc-list :spacing=100 lists those Linux has.")
        val TERMINAL_FONT = Setting.Text("terminal.font", "JetBrains Mono", "The terminals' font family; fc-list :spacing=100 lists those installed.")
        val TERMINAL_TEXT = Setting.Number("terminal.text", 12.0, 6.0..32.0, "The terminals' em size in dp, before Android's font size: 10 smaller, 12 default, 14 larger.")

        val SWIPE_UP =
            Setting.Text(
                "gestures.swipe-up",
                "drawer.owl",
                "The app drawer, which swiping up opens: a panel's file name in ~/.config/omalauncher/widgets, by default drawer.owl; \"none\" leaves only tapping the bar to open drawer.owl. One that's gone or doesn't compile falls back to drawer.owl, then to .stock-drawer.owl, the drawer as it ships.",
            )
        val SWIPE_DOWN =
            Setting.Text(
                "gestures.swipe-down",
                "none",
                "What swiping down on home opens: \"none\" nothing, or a panel's file name in ~/.config/omalauncher/widgets (\"my-panel.owl\"); one that's gone or doesn't compile opens nothing.",
            )
    }

    /** A system bar's band on home; [name] is its section in the file. */
    @Immutable
    class Bar(
        name: String,
        what: String,
    ) {
        val framed = Setting.Flag("$name.framed", false, "$what on a band of the tiles' glass; false leaves the bare wallpaper.")
        val background = Setting.Color("$name.background", "The band's color: \"theme\" is the theme's background, like the tiles.")
        val opacity = Setting.Number("$name.opacity", GLASS, 0.0..1.0, "How much the band covers the wallpaper: ${Toml.format(GLASS)} like the tiles, 1 solid.")
        val border = Setting.Color("$name.border", "The line on the band's side facing the widgets: \"theme\" follows widgets.border.", noneAllowed = true)
        val borderWidth = Setting.Number("$name.border-width", 2.0, 0.0..8.0, "How thick that line is, in dp; 0 draws none.")
        val all = listOf(framed, background, opacity, border, borderWidth)
    }
}

/**
 * style.toml: the launcher's [Style] as a file, which the Style menu, agents
 * and the user all edit. It holds only what differs from the defaults, so a
 * new setting needs no change to anyone's file; defaults.toml lists them all.
 * Keys it doesn't know are kept, for a newer launcher (after a downgrade) or
 * a typo the user can still see and fix.
 */
object StyleFile {
    /** The file's format. Bump it with a step in [MIGRATIONS] when a key is renamed or a value's meaning changes. */
    const val VERSION = 1
    const val VERSION_KEY = "version"

    /**
     * MIGRATIONS[n] turns a version n + 1 table into version n + 2, in place.
     * Adding a setting doesn't need one; renaming or reshaping one does, for
     * example `{ t -> t.remove("icons")?.let { t["icons.monochrome"] = it == "monochrome" } }`.
     */
    val MIGRATIONS: List<(MutableMap<String, Any>) -> Unit> = emptyList()

    val ALL: List<Setting<*>> =
        with(Style) {
            listOf(THEME, BACKGROUND, FONT, APP_TILES, RECENT_APPS, COLUMNS, FORCE_SINGLE_PAGE, GAPS_IN, GAPS_OUT, TILE_BORDER_WIDTH, TILE_BORDER, TILE_BORDER_FOCUSED) +
                listOf(SCREEN_GAPS_OUT, SCREEN_BORDER_WIDTH, KEYBAR_GAPS_OUT, KEYBAR_BORDER_WIDTH, MONOCHROME_ICONS, CORNER_SCALE) +
                listOf(STATUS_BAR_SHOWN) + STATUS_BAR.all + NAVIGATION_BAR.all + listOf(TERMINAL_FONT, TERMINAL_TEXT, SWIPE_UP, SWIPE_DOWN)
        }

    private val byKey = ALL.associateBy { it.key }

    /** A file without a version was written by hand against the current defaults.toml; one below 1 reads as the first. */
    fun versionOf(table: Map<String, Any>) = ((table[VERSION_KEY] as? Double)?.toInt() ?: VERSION).coerceAtLeast(1)

    /** Brings [table] up to [VERSION]. A newer one (after a downgrade) is left as it is. */
    fun migrate(table: MutableMap<String, Any>) {
        var v = versionOf(table)
        while (v < VERSION) {
            MIGRATIONS[v - 1](table)
            v++
        }
        table[VERSION_KEY] = maxOf(v, VERSION).toDouble()
    }

    class Decoded(
        val style: Style,
        /** One line per key that was ignored, saying why. */
        val problems: List<String>,
    )

    /** [table]'s style; a key that's unknown or has a bad value is reported, and keeps its value from [previous]. */
    fun decode(
        table: Map<String, Any>,
        previous: Style = Style(),
    ): Decoded {
        val values = HashMap<String, Any?>()
        val problems = ArrayList<String>()
        for ((key, value) in table) {
            if (key == VERSION_KEY) continue
            val setting = byKey[key]
            if (setting == null) {
                problems += "$key: not a setting; see defaults.toml"
                continue
            }
            try {
                values[key] = setting.decode(value)
            } catch (e: IllegalArgumentException) {
                values[key] = previous[setting]
                problems += "$key: ${e.message}; keeping ${previous[setting]?.let(Toml::format) ?: "it unset"}"
            }
        }
        return Decoded(Style(values), problems)
    }

    /**
     * [table] with [style]'s values: a setting at its default is taken out,
     * so the file keeps saying only what was changed. Unknown keys stay.
     */
    fun encode(
        table: Map<String, Any>,
        style: Style,
    ): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out[VERSION_KEY] = (table[VERSION_KEY] as? Double) ?: VERSION.toDouble()
        for ((k, v) in table) if (k != VERSION_KEY && k !in byKey) out[k] = v
        // Known keys in the schema's order, so the sections come out the same way every time.
        for (setting in ALL) style[setting]?.takeIf { it != setting.default }?.let { out[setting.key] = it }
        return out
    }

    const val HEADER =
        "# The launcher's style. The Style menu writes it too; settings left out have\n" +
            "# their default, and defaults.toml next to this file lists them all.\n" +
            "# Changes show on the phone within a second; errors.txt says what was ignored.\n\n"

    /** defaults.toml: every setting with its default and what it does, for agents and users to look up. */
    fun defaultsText(): String =
        buildString {
            append("# Every setting style.toml takes, at its default. Copy what you want to change into\n")
            append("# style.toml next to this file. The launcher rewrites this file on updates.\n\n")
            append("$VERSION_KEY = $VERSION\n")
            var section = ""
            for (setting in ALL) {
                val s = setting.key.substringBefore('.', "")
                if (s != section) {
                    section = s
                    append("\n[").append(s).append("]\n")
                } else {
                    append('\n')
                }
                append("# ").append(setting.doc).append('\n')
                val name = setting.key.substringAfter('.')
                val default = setting.default
                if (default == null) append("# ").append(name).append(" = \"…\"\n") else append(name).append(" = ").append(Toml.format(default)).append('\n')
            }
        }
}
