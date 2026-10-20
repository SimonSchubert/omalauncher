package dev.omalauncher.theme

/**
 * Following Android's dark mode ([Style.AUTO_THEME]): what it does to the
 * style. [Style.THEME] and [Style.BACKGROUND] stay what shows; the mode's
 * [Style.ModeTheme] is what they become when Android switches to it.
 */
object AutoTheme {
    /** Light mode's theme when turning it on finds no light partner for the one showing. */
    const val DEFAULT_LIGHT = "flexoki-light"

    fun of(dark: Boolean) = if (dark) Style.DARK else Style.LIGHT

    /**
     * [style] showing [dark]'s theme and background, now that Android switched to it.
     * A mode without its own theme yet keeps what shows, which becomes its own.
     */
    fun switched(
        style: Style,
        dark: Boolean,
    ): Style {
        if (!style[Style.AUTO_THEME]) return style
        val mode = of(dark)
        val theme = style[mode.theme] ?: return remembered(style, dark)
        return style.with(Style.THEME, theme).with(Style.BACKGROUND, style[mode.background])
    }

    /** [style] with the theme and background showing kept as [dark]'s: what the user picks while following. */
    fun remembered(
        style: Style,
        dark: Boolean,
    ): Style {
        if (!style[Style.AUTO_THEME]) return style
        val mode = of(dark)
        return style.with(mode.theme, style[Style.THEME] ?: ThemeStore.DEFAULT_THEME).with(mode.background, style[Style.BACKGROUND])
    }

    /** [style] not following; with [forget], without the modes' themes either. */
    fun disabled(
        style: Style,
        forget: Boolean,
    ): Style {
        val off = style.with(Style.AUTO_THEME, false)
        if (!forget) return off
        return listOf(Style.LIGHT, Style.DARK).fold(off) { s, mode -> s.with(mode.theme, null).with(mode.background, null) }
    }

    /** [style] without [name] as a mode's theme, once it's removed: the mode keeps what shows when Android switches to it. */
    fun forgotten(
        style: Style,
        name: String,
    ) = listOf(Style.LIGHT, Style.DARK).fold(style) { s, mode -> if (s[mode.theme] == name) s.with(mode.theme, null).with(mode.background, null) else s }

    /**
     * [style] following, from the Style menu: the theme showing is [dark]'s, and the
     * other mode keeps its own or gets a partner from [themes] (name to whether it's light).
     */
    fun enabled(
        style: Style,
        dark: Boolean,
        themes: Map<String, Boolean>,
    ): Style {
        val on = remembered(style.with(Style.AUTO_THEME, true), dark)
        val other = of(!dark)
        if (on[other.theme] != null) return on
        return on.with(other.theme, partner(on[Style.THEME] ?: ThemeStore.DEFAULT_THEME, light = dark, themes)).with(other.background, null)
    }

    /**
     * A theme for the [light] mode to go with [name]: [name] itself if it's already that,
     * else its other variant (catppuccin and catppuccin-latte), else the default.
     */
    fun partner(
        name: String,
        light: Boolean,
        themes: Map<String, Boolean>,
    ): String {
        if (themes[name] == light) return name
        val family = name.substringBefore('-')
        val variant = themes.keys.sorted().firstOrNull { themes[it] == light && it.substringBefore('-') == family }
        return variant ?: if (light) DEFAULT_LIGHT else ThemeStore.DEFAULT_THEME
    }
}
