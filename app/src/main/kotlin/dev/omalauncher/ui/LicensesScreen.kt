package dev.omalauncher.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.omalauncher.BuildConfig
import dev.omalauncher.R
import dev.omalauncher.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the app ships that others wrote. [license] names a text in assets/licenses; [notice] is
 * the component's copyright line, which MIT and BSD need next to the text. [source] is where the
 * code is: for proot and talloc that's the offer of source their licenses ask for.
 */
private class Component(
    val name: String,
    @StringRes val use: Int,
    val license: String,
    val notice: String,
    val url: String,
    val source: String? = null,
)

private const val APACHE = "apache-2.0"

// proot is built from Termux's source with our patches (patches/proot), talloc and libandroid-shmem
// are Termux's builds (scripts/fetch-proot.sh); libproot.so and libtalloc.so are patched to need
// libtalloc.so instead of libtalloc.so.2, so the notice says so. The source links name the versions
// that script pins; change them together.
private val COMPONENTS =
    listOf(
        Component(
            "PRoot",
            R.string.licenses_use_proot,
            "gpl-2.0",
            "Copyright (C) 2015 STMicroelectronics. Built from Termux's 5.1.107.96 with omalauncher's patches; the binary's libtalloc.so.2 dependency is renamed to libtalloc.so.",
            "https://proot-me.github.io",
            source = "https://github.com/SimonSchubert/omalauncher/tree/main/patches/proot",
        ),
        Component(
            "talloc",
            R.string.licenses_use_talloc,
            "lgpl-3.0",
            "Copyright (C) Andrew Tridgell 2004, Stefan Metzmacher 2006. Termux build of 2.5.0, renamed to libtalloc.so. The LGPL adds to the GPL 3.0 below it.",
            "https://talloc.samba.org",
            source = "https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz",
        ),
        Component(
            "libandroid-shmem",
            R.string.licenses_use_shmem,
            "bsd-3-clause",
            "Copyright (c) 2013, Sergii Pylypenko\nCopyright (c) 2017, Fredrik Fornwall\nAll rights reserved.",
            "https://github.com/termux/libandroid-shmem",
            source = "https://github.com/termux/libandroid-shmem/tree/v0.7",
        ),
        Component(
            "Mesa",
            R.string.licenses_use_mesa,
            "mit",
            "Copyright (C) 1999-2007 Brian Paul and the Mesa contributors. Mesa 26.2.4 with omalauncher's " +
                "zink patch. Most files are MIT; the rest are under the permissive licenses in Mesa's licenses/ directory.",
            "https://mesa3d.org",
            source = "https://github.com/SimonSchubert/omalauncher/tree/main/scripts/mesa",
        ),
        Component(
            "wayland-protocols",
            R.string.licenses_use_wayland,
            "mit",
            "Copyright © 2008-2013 Kristian Høgsberg\nCopyright © 2010-2013 Intel Corporation\n" +
                "Copyright © 2013 Rafael Antognolli\nCopyright © 2013 Jasper St. Pierre\nCopyright © 2014 Jonas Ådahl\n" +
                "Copyright © 2014 Jason Ekstrand\nCopyright © 2014-2015 Collabora, Ltd.\nCopyright © 2015 Red Hat Inc.",
            "https://gitlab.freedesktop.org/wayland/wayland-protocols",
        ),
        Component(
            "Omarchy",
            R.string.licenses_use_omarchy,
            "mit",
            "Copyright (c) David Heinemeier Hansson",
            "https://github.com/omacom/omarchy",
        ),
        Component("Nerd Fonts", R.string.licenses_use_nerd_fonts, "mit", "Copyright (c) 2014 Ryan L McIntyre", "https://www.nerdfonts.com"),
        Component(
            "Weather Icons",
            R.string.licenses_use_weather_icons,
            "ofl-1.1",
            "Copyright (c) Erik Flowers. Licensed under the SIL Open Font License 1.1.",
            "https://erikflowers.github.io/weather-icons/",
        ),
        Component(
            "Material Design Icons",
            R.string.licenses_use_mdi,
            APACHE,
            "Copyright (c) Pictogrammers",
            "https://pictogrammers.com/library/mdi/",
        ),
        Component(
            "Compose Multiplatform",
            R.string.licenses_use_compose,
            APACHE,
            "Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers",
            "https://github.com/JetBrains/compose-multiplatform",
        ),
        Component(
            "Android Jetpack",
            R.string.licenses_use_jetpack,
            APACHE,
            "Copyright The Android Open Source Project",
            "https://developer.android.com/jetpack/androidx",
        ),
        Component(
            "Kotlin and kotlinx.coroutines",
            R.string.licenses_use_kotlin,
            APACHE,
            "Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors",
            "https://kotlinlang.org",
        ),
        Component(
            "Apache Commons Compress",
            R.string.licenses_use_commons_compress,
            APACHE,
            "Apache Commons Compress\nCopyright 2002-2026 The Apache Software Foundation\n\n" +
                "This product includes software developed at The Apache Software Foundation (https://www.apache.org/).",
            "https://commons.apache.org/proper/commons-compress/",
        ),
        Component("AndroidSVG", R.string.licenses_use_androidsvg, APACHE, "Copyright 2013 Paul LeBeau, Cave Rock Software Ltd.", "https://bigbadaboom.github.io/androidsvg/"),
        Component("ZXing", R.string.licenses_use_zxing, APACHE, "Copyright 2007-2025 ZXing authors", "https://github.com/zxing/zxing"),
        Component("JSON-java", R.string.licenses_use_json, "public-domain", "Public Domain.", "https://github.com/stleary/JSON-java"),
    )

/** Third-party notices, opened from settings. Tapping an entry shows its full license text. */
@Composable
fun LicensesScreen(onDismiss: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    OverlayPanel(stringResource(R.string.licenses_title), onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                BasicText(
                    stringResource(R.string.licenses_header, BuildConfig.VERSION_NAME),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(COMPONENTS, key = { it.name }) { c ->
                val expanded = open == c.name
                // Only its header opens and closes it: keys go from there into what it shows, which a tap target around
                // it all would keep them out of.
                Column(Modifier.card()) {
                    Row(Modifier.clickable { open = if (expanded) null else c.name }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(c.name, style = Mono.copy(fontSize = TextSize.Item))
                            BasicText("${c.license} · ${stringResource(c.use)}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                        Symbol(if (expanded) Glyph.ChevronDown else Glyph.ChevronRight, Palette.accent, fontSize = TextSize.Title)
                    }
                    if (expanded) Details(c)
                }
            }
        }
    }
}

@Composable
private fun Details(c: Component) {
    val context = LocalContext.current
    val text by produceState<String?>(null, c.license) {
        value =
            if (c.license == "public-domain") {
                null
            } else {
                withContext(Dispatchers.IO) { context.assets.open("licenses/${c.license}.txt").bufferedReader().use { it.readText() } }
            }
    }
    // The LGPL is a set of permissions on top of the GPL 3.0, so it ships with both texts.
    val gpl3 by produceState<String?>(null, c.license) {
        if (c.license == "lgpl-3.0") value = withContext(Dispatchers.IO) { context.assets.open("licenses/gpl-3.0.txt").bufferedReader().use { it.readText() } }
    }
    val small = Mono.copy(fontSize = TextSize.Small)
    val open = remember(context) { { url: String -> context.openUrl(url); Unit } }
    Spacer(Modifier.height(10.dp))
    BasicText(c.notice, style = small)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BracketAction(stringResource(R.string.licenses_website), Palette.accent) { open(c.url) }
        c.source?.let { BracketAction(stringResource(R.string.licenses_source), Palette.accent) { open(it) } }
    }
    text?.let {
        Spacer(Modifier.height(10.dp))
        BasicText(it, Modifier.keyScrolled(around = true), style = small.copy(color = Palette.muted))
    }
    gpl3?.let {
        Spacer(Modifier.height(10.dp))
        BasicText(it, Modifier.keyScrolled(around = true), style = small.copy(color = Palette.muted))
    }
}
