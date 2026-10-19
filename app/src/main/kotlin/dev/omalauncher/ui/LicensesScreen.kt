package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import dev.omalauncher.BuildConfig
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
    val use: String,
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
            "runs the optional Linux system",
            "gpl-2.0",
            "Copyright (C) 2015 STMicroelectronics. Built from Termux's 5.1.107.96 with omalauncher's patches; the binary's libtalloc.so.2 dependency is renamed to libtalloc.so.",
            "https://proot-me.github.io",
            source = "https://github.com/SimonSchubert/omalauncher/tree/main/patches/proot",
        ),
        Component(
            "talloc",
            "memory allocator used by PRoot",
            "lgpl-3.0",
            "Copyright (C) Andrew Tridgell 2004, Stefan Metzmacher 2006. Termux build of 2.5.0, renamed to libtalloc.so. The LGPL adds to the GPL 3.0 below it.",
            "https://talloc.samba.org",
            source = "https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz",
        ),
        Component(
            "libandroid-shmem",
            "shared memory for PRoot",
            "bsd-3-clause",
            "Copyright (c) 2013, Sergii Pylypenko\nCopyright (c) 2017, Fredrik Fornwall\nAll rights reserved.",
            "https://github.com/termux/libandroid-shmem",
            source = "https://github.com/termux/libandroid-shmem/tree/v0.7",
        ),
        Component(
            "Mesa",
            "OpenGL on Adreno GPUs in the optional Linux system",
            "mit",
            "Copyright (C) 1999-2007 Brian Paul and the Mesa contributors. Mesa 26.2.4 with omalauncher's " +
                "zink patch. Most files are MIT; the rest are under the permissive licenses in Mesa's licenses/ directory.",
            "https://mesa3d.org",
            source = "https://github.com/SimonSchubert/omalauncher/tree/main/scripts/mesa",
        ),
        Component(
            "wayland-protocols",
            "protocol files bundled with Mesa",
            "mit",
            "Copyright © 2008-2013 Kristian Høgsberg\nCopyright © 2010-2013 Intel Corporation\n" +
                "Copyright © 2013 Rafael Antognolli\nCopyright © 2013 Jasper St. Pierre\nCopyright © 2014 Jonas Ådahl\n" +
                "Copyright © 2014 Jason Ekstrand\nCopyright © 2014-2015 Collabora, Ltd.\nCopyright © 2015 Red Hat Inc.",
            "https://gitlab.freedesktop.org/wayland/wayland-protocols",
        ),
        Component(
            "Omarchy",
            "theme colors bundled for first run",
            "mit",
            "Copyright (c) David Heinemeier Hansson",
            "https://github.com/omacom/omarchy",
        ),
        Component("Nerd Fonts", "symbol font", "mit", "Copyright (c) 2014 Ryan L McIntyre", "https://www.nerdfonts.com"),
        Component(
            "Weather Icons",
            "weather glyphs in the symbol font",
            "ofl-1.1",
            "Copyright (c) Erik Flowers. Licensed under the SIL Open Font License 1.1.",
            "https://erikflowers.github.io/weather-icons/",
        ),
        Component(
            "Material Design Icons",
            "glyphs in the symbol font",
            APACHE,
            "Copyright (c) Pictogrammers",
            "https://pictogrammers.com/library/mdi/",
        ),
        Component(
            "Compose Multiplatform",
            "user interface",
            APACHE,
            "Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers",
            "https://github.com/JetBrains/compose-multiplatform",
        ),
        Component(
            "Android Jetpack",
            "activity, Compose and other AndroidX libraries",
            APACHE,
            "Copyright The Android Open Source Project",
            "https://developer.android.com/jetpack/androidx",
        ),
        Component(
            "Kotlin and kotlinx.coroutines",
            "language runtime",
            APACHE,
            "Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors",
            "https://kotlinlang.org",
        ),
        Component(
            "Apache Commons Compress",
            "unpacks downloads",
            APACHE,
            "Apache Commons Compress\nCopyright 2002-2026 The Apache Software Foundation\n\n" +
                "This product includes software developed at The Apache Software Foundation (https://www.apache.org/).",
            "https://commons.apache.org/proper/commons-compress/",
        ),
        Component("AndroidSVG", "draws SVG icons", APACHE, "Copyright 2013 Paul LeBeau, Cave Rock Software Ltd.", "https://bigbadaboom.github.io/androidsvg/"),
        Component("ZXing", "draws the QR codes widgets are shared with", APACHE, "Copyright 2007-2025 ZXing authors", "https://github.com/zxing/zxing"),
        Component("JSON-java", "reads and writes JSON", "public-domain", "Public Domain.", "https://github.com/stleary/JSON-java"),
    )

/** Third-party notices, opened from settings. Tapping an entry shows its full license text. */
@Composable
fun LicensesScreen(onDismiss: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    OverlayPanel("licenses", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                BasicText(
                    "Omalauncher ${BuildConfig.VERSION_NAME} · © 2026 Simon Schubert\n\nIt includes the open source software below.",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(COMPONENTS, key = { it.name }) { c ->
                val expanded = open == c.name
                Column(
                    Modifier.card(onClick = { open = if (expanded) null else c.name }),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BasicText(c.name, style = Mono.copy(fontSize = TextSize.Item))
                            BasicText("${c.license} · ${c.use}", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
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
        BracketAction("website", Palette.accent) { open(c.url) }
        c.source?.let { BracketAction("source code", Palette.accent) { open(it) } }
    }
    text?.let {
        Spacer(Modifier.height(10.dp))
        BasicText(it, style = small.copy(color = Palette.muted))
    }
    gpl3?.let {
        Spacer(Modifier.height(10.dp))
        BasicText(it, style = small.copy(color = Palette.muted))
    }
}
