package dev.omalauncher.widgets.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

class LangTest {
    /** Just enough runtime for tests: states start at their initial value, lets evaluate on read. */
    private class TestHost(
        val program: Program?,
    ) : Host {
        constructor(n: Int) : this(null) {
            slots = Array(n) { Null }
        }

        var slots = Array<Value>(program?.globals?.size ?: 0) { Null }
        val actions = ArrayList<String>()

        init {
            program?.globals?.forEachIndexed { i, g -> if (g is Global.State) slots[i] = g.init(Env.root(this)) }
        }

        override fun read(slot: Int): Value = (program?.globals?.get(slot) as? Global.Let)?.value?.invoke(Env.root(this)) ?: slots[slot]

        override fun write(
            slot: Int,
            value: Value,
        ) {
            slots[slot] = value
        }

        override fun theme(name: String) = if (name == "accent") 0xff7aa2f7.toInt() else null

        override fun time() = 1.5

        override fun act(
            name: String,
            args: List<Value>,
        ) {
            actions += "$name ${args.joinToString()}"
        }

        override fun runInto(
            command: String,
            slot: Int,
        ) {
            actions += "runInto $slot $command"
        }

        override fun post(
            url: String,
            body: Value,
            headers: Map<String, String>,
            slot: Int?,
        ) {
            actions += "post $slot $url $body $headers"
        }

        override val budget = Budget(10_000)
    }

    /** Compiles a widget with one Text and evaluates its text. */
    private fun text(
        expr: String,
        decls: String = "",
        setup: (Program, TestHost) -> Unit = { _, _ -> },
    ): String {
        val p = Compiler.compile("widget \"t\" {\n$decls\nText { text: $expr }\n}")
        val host = TestHost(p)
        setup(p, host)
        val el = p.root.single() as WNode.Element
        return el.props["text"]!!(Env.root(host)).toString()
    }

    private fun compileError(src: String): String =
        try {
            Compiler.compile(src)
            fail("expected a compile error")
            ""
        } catch (e: CompileError) {
            Source(src).format(e)
        }

    @Test fun arithmeticAndPrecedence() {
        assertEquals("7", text("1 + 2 * 3"))
        assertEquals("9", text("(1 + 2) * 3"))
        assertEquals("2.5", text("5 / 2"))
        assertEquals("1", text("-3 % 2"))
        assertEquals("true", text("1 < 2 && 2 <= 2"))
    }

    /** A list walks its items and a map its {key, value} entries, both capped; anything else walks nothing. */
    @Test fun iterableWalksListsAndMaps() {
        assertEquals(listOf(Num(1.0), Num(2.0)), Builtins.iterable(Lst(listOf(Num(1.0), Num(2.0)))))
        val entries = Builtins.iterable(Obj(mapOf("a" to Num(1.0))))
        assertEquals("a", (entries.single() as Obj).fields["key"].toString())
        assertEquals(Num(1.0), (entries.single() as Obj).fields["value"])
        assertEquals(emptyList<Value>(), Builtins.iterable(Str("abc")))
        assertEquals(Builtins.MAX_LIST, Builtins.iterable(Lst(List(Builtins.MAX_LIST + 5) { Num(it.toDouble()) })).size)
        assertEquals(Builtins.MAX_LIST, Builtins.iterable(Obj((0..Builtins.MAX_LIST + 5).associate { "k$it" to Null })).size)
    }

    /** Every fast-path built-in gives what its general entry gives for numbers, or NaN; NaN in is NaN out. */
    @Test fun everyNumericFastPathMatchesItsBuiltin() {
        val inputs = listOf(0.0, -0.0, 1.0, -2.5, 0.5, 3.7, 1e6, -1e-9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

        fun check(
            name: String,
            xs: List<Double>,
            fast: Double,
        ) {
            val general = runCatching { Builtins.all.getValue(name).impl(Args(xs.map(::Num), null)) }.getOrNull()
            val where = "$name(${xs.joinToString()})"
            if (xs.any { it.isNaN() }) assertTrue("$where: NaN in, $fast out", fast.isNaN())
            if (fast.isNaN()) return
            assertTrue("$where: fast $fast, general $general", general is Num && general.v == fast)
        }
        assertEquals(setOf("floor", "ceil", "abs", "sqrt", "sin", "cos", "tan", "rad", "deg", "sign", "round"), Builtins.numeric1.keys)
        for ((name, g) in Builtins.numeric1) for (x in inputs) check(name, listOf(x), g.of(x))
        for ((name, g) in Builtins.numeric2) for (x in inputs) for (y in inputs) check(name, listOf(x, y), g.of(x, y))
        for ((name, g) in Builtins.numeric3) {
            for (x in inputs) for (y in inputs) for (z in inputs) check(name, listOf(x, y, z), g.of(x, y, z))
        }
    }

    /** Arithmetic runs on doubles when every input is a number, and the general way when one isn't: same results. */
    @Test fun numericFastPathMatchesGeneral() {
        val p = Compiler.compile("widget {\n state x = 3\n Text { text: floor(x * 2.5) + 1 } }")
        assertTrue((p.root[0] as WNode.Element).props["text"]!!.fast != null)
        assertEquals("8", text("floor(x * 2.5) + 1", "state x = 3"))
        // Numeric text, null and text that isn't a number leave the fast path.
        assertEquals("8", text("floor(x * 2.5) + 1", "state x = \"3\""))
        assertEquals("null", text("x * 2 + 1", "state x = null"))
        assertEquals("a6", text("\"a\" + x * 2", "state x = 3"))
        assertEquals("41", text("x + 1", "state x = \"4\""))
        assertEquals("41", text("x + y * 1", "state x = \"4\"\nstate y = 1"))
        // min and max skip null arguments.
        assertEquals("3", text("min(x, 3) * 1", "state x = null"))
        assertEquals("2", text("max(x * 2, 1, -5)", "state x = 1"))
        assertEquals("0", text("10 / (x - 3)", "state x = 3"))
        assertEquals("2", text("(x - 10) % 3", "state x = 3"))
        assertEquals("16", text("(x < 3 ? 2 : 1) * 8", "state x = 1"))
        assertEquals("8", text("(x < 3 ? 2 : 1) * 8", "state x = 5"))
        assertEquals("true", text("sin(x) * 1 < 0.5", "state x = \"0\""))
        assertEquals("false", text("x * 2 > 1", "state x = null"))
        assertEquals("3", text("time * 2"))
        assertEquals("3.5", text("lerp(clamp(x, 0, 10), 4, 0.5) + 0", "state x = 3"))
        assertEquals("1.23", text("round(x * 1.2345, 2)", "state x = 1"))
        assertEquals("1.23", text("round(x, 2)", "state x = \"1.2345\""))
        // A null stays a null through the steps that turn anything else into a number.
        assertEquals("null", text("x / 0 + 1", "state x = null"))
        assertEquals("null", text("x % 0 + 1", "state x = null"))
        assertEquals("null", text("pow(x, 0) + 1", "state x = null"))
        assertEquals("1", text("clamp(x, 1, 10) * 1", "state x = null"))
        assertEquals("NaN", text("sqrt(x) * 1", "state x = -1"))
        // round of NaN fails the way it always has, from the general path.
        val failed = runCatching { text("round(sqrt(x))", "state x = -1") }.exceptionOrNull()
        assertTrue(failed?.message.orEmpty().startsWith("round:"))
    }

    @Test fun constantsFold() {
        val p = Compiler.compile("widget { Text { text: \"a${'$'}{1 + 1}\" } }")
        assertEquals(Str("a2"), (p.root[0] as WNode.Element).props["text"]!!.const)
    }

    @Test fun interpolationAndNulls() {
        assertEquals("cpu 42%", text("\"cpu ${'$'}{x}%\"", "state x = 42"))
        assertEquals("…", text("\"${'$'}{s}\"", "source s = cmd('date')"))
        assertEquals("none", text("s ?? \"none\"", "source s = cmd('date')"))
        assertEquals("fallback", text("\"\" || \"fallback\""))
        assertEquals("  |_ aligned", text("\"${'$'}{s}\"", "state s = \"\\n  |_ aligned \\n\""))
    }

    @Test fun textNumbersCompareAsNumbers() {
        assertEquals("true", text("s > 9", "state s = \"10\""))
        assertEquals("true", text("s == 10", "state s = \"10\""))
    }

    @Test fun listsAndLambdas() {
        assertEquals("[2, 4, 6]", text("[1, 2, 3].map(x => x * 2)"))
        assertEquals("[3, 2]", text("[1, 2, 3].filter(x => x > 1).sortBy(x => -x)"))
        assertEquals("6", text("sum([1, 2, 3])"))
        assertEquals("b", text("[{n: \"a\", v: 1}, {n: \"b\", v: 5}].sortBy(e => -e.v).0.n"))
        assertEquals("[0, 1, 2]", text("range(3)"))
    }

    @Test fun jsonSourcesAndPaths() {
        assertEquals("42", text("info.list[1].v", "source info = cmd('x') json") { _, h ->
            h.slots[0] = Json.parse("""{"list":[{"v":1},{"v":42}]}""")
        })
    }

    @Test fun weatherIcons() {
        assertEquals("\ue30d", text("weatherIcon(\"113\")"))
        assertEquals("\ue32b", text("weatherIcon(113, true)"))
        assertEquals("\ue318", text("weatherIcon(302)"))
        assertEquals("\ue33d", text("weatherIcon(null)"))
    }

    @Test fun stringHelpers() {
        assertEquals("12.5", text("fixed(12.456, 1)"))
        assertEquals("1.5K", text("fmtBytes(1536)"))
        assertEquals("2h 5m", text("fmtDuration(7500)"))
        assertEquals("[a, b]", text("split(\"a,b\", \",\")"))
        assertEquals("  7", text("pad(7, 3)"))
        assertEquals("[10, 20]", text("match(\"x=10 y=20\", \"x=(\\\\d+) y=(\\\\d+)\")"))
    }

    @Test fun plusKeepsSpacesInStrings() {
        assertEquals("- a", text("\"- \" + \"a\""))
        assertEquals("x y", text("\"x\" + \" \" + \"y\""))
        assertEquals("n: 2", text("\"n: \" + 2"))
    }

    @Test fun letsSeeEarlierLetsAndSources() {
        assertEquals("21", text("b", "state a = 10\nlet b = a * 2 + 1"))
    }

    @Test fun handlersAssignStateAndRunActions() {
        val p = Compiler.compile("widget {\nstate n = 1\nonTap: { n = n + 1; run('echo hi') }\nText { text: n }\n}")
        val host = TestHost(p.globals.size)
        host.slots[0] = Num(1.0)
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals(Num(2.0), host.slots[0])
        assertEquals(listOf("run echo hi"), host.actions)
        assertEquals(listOf("echo hi"), p.commands)
    }

    @Test fun runIntoStateAndNewActions() {
        val p = Compiler.compile("widget {\nstate out = \"\"\nonTap: { out = run('date'); copy(out); notify(\"hi\", out); androidApp(\"org.pkg\") }\nText { text: out }\n}")
        val host = TestHost(p.globals.size)
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals(listOf("runInto 0 date", "copy null", "notify hi, null", "androidApp org.pkg"), host.actions)
        assertEquals(listOf("date"), p.commands)
    }

    @Test fun httpSources() {
        val p = Compiler.compile("widget {\nsource w = http(\"https://x.test/a\", {Accept: \"application/json\"}) every 1s json status\nText { text: w.code }\n}")
        val s = p.globals[0] as Global.Source
        assertEquals("http", s.kind)
        assertEquals(5.0, s.every, 0.0)
        assertTrue(s.headers != null && s.status)
        assertEquals(listOf("http GET https://x.test/a"), p.commands)
        assertTrue(compileError("widget {\nsource w = http(\"file:///etc/passwd\")\nText { text: w }\n}").contains("http:// or https://"))
    }

    @Test fun postSendsFromHandlers() {
        val p =
            Compiler.compile(
                "app \"t\" {\n meta { owl: 8 }\n state id = \"a b\"\n state tok = null\n state sent = null\n back: true\n" +
                    " onBack: { tok = post(\"https://x.test/token\", {code: id}, {Authorization: \"Bearer ${'$'}{id}\"}); post(\"https://x.test/s/${'$'}{enc(id)}\") }\n" +
                    " every 5s { sent = post(tok == null ? null : \"https://x.test/poll\", \"raw\") }\n Text { text: id } }",
            )
        val host = TestHost(p)
        p.onBack!!.run(Env.root(host), emptyList())
        assertEquals(listOf("post 1 https://x.test/token {code: a b} {Authorization=Bearer a b}", "post null https://x.test/s/a%20b null {}"), host.actions)
        // A null URL sends nothing, like any action whose value hasn't loaded.
        host.actions.clear()
        p.triggers.single().action.run(Env.root(host), emptyList())
        assertEquals(emptyList<String>(), host.actions)
        assertEquals(setOf("http POST https://x.test/token", "http POST https://x.test/s/${Compiler.VALUE}", "http POST https://x.test/poll"), p.commands.toSet())
        assertFalse(p.needsLinux)
        assertTrue(compileError("widget {\n meta { owl: 7 }\n onTap: post(\"https://x.test/\")\n Text { text: 1 } }").contains("post needs owl 8"))
        assertTrue(compileError("app \"t\" {\n meta { owl: 8 }\n back: true\n onBack: post(\"file:///x\")\n Text { text: 1 } }").contains("http:// or https://"))
        assertEquals(listOf("http POST (a URL built from widget values)"), Compiler.compile("app \"t\" {\n meta { owl: 8 }\n state u = \"\"\n back: true\n onBack: post(u)\n Text { text: 1 } }").commands)
    }

    @Test fun imagesFromTheWeb() {
        val p =
            Compiler.compile(
                """
                app "a" {
                  meta { owl: 8 }
                  state id = 1
                  let base = "https://x.test/art/"
                  Image { src: "https://x.test/logo.svg" }
                  Image { src: "${'$'}{base}${'$'}{id}.svg" }
                  Canvas { image("https://x.test/p/" + id, 0, 0, 10, 10) }
                  Image { src: "~/pics/a.png" }
                }
                """.trimIndent(),
            )
        assertEquals(listOf("image GET https://x.test/logo.svg", "image GET https://x.test/art/…", "image GET https://x.test/p/…"), p.commands)
        assertTrue(!p.needsLinux)
        assertTrue(compileError("widget {\nmeta { owl: 7 }\nImage { src: \"https://x.test/a.png\" }\n}").contains("an image from the web needs owl 8"))
        assertTrue(compileError("app \"a\" {\nmeta { owl: 8 }\nImage { src: \"http://x.test/a.png\" }\n}").contains("https only"))
    }

    @Test fun sounds() {
        val p =
            Compiler.compile(
                """
                app "a" {
                  meta { owl: 8 }
                  state id = 1
                  let base = "https://x.test/audio/"
                  every 5s { sound("${'$'}{base}${'$'}{id}.mp3") }
                  Button { text: "stop"; onTap: sound("stop") }
                  Button { text: "chime"; onTap: sound("~/chime.ogg") }
                  Button { text: "done"; onTap: sound(id > 1 ? "${'$'}{base}done.mp3" : null) }
                }
                """.trimIndent(),
            )
        assertEquals(listOf("sound GET https://x.test/audio/…", "sound GET https://x.test/audio/done.mp3"), p.commands)
        assertTrue(!p.needsLinux)
        assertTrue(compileError("widget {\nmeta { owl: 7 }\nonTap: sound(\"stop\")\nText { text: \"x\" }\n}").contains("sound needs owl 8"))
        assertTrue(compileError("app \"a\" {\nmeta { owl: 8 }\nButton { text: \"x\"; onTap: sound(\"http://x.test/a.mp3\") }\n}").contains("sounds load over https only"))
    }

    @Test fun forLoopsAndLocalLets() {
        val p =
            Compiler.compile(
                """
                widget {
                  Column {
                    for p, i in [1, 2] key p {
                      let doubled = p * 2
                      Text { text: "${'$'}{i}:${'$'}{doubled}" }
                    }
                  }
                }
                """.trimIndent(),
            )
        val col = p.root.single() as WNode.Element
        val loop = col.children.single() as WNode.For
        val lets = loop.body.single() as WNode.Lets
        val t = lets.children.single() as WNode.Element
        val host = TestHost(0)
        val env = Env.root(host).child(Num(2.0), Num(1.0))
        lateinit var frame: Env
        frame = env.childLazy(arrayOf({ lets.values[0](frame) }))
        assertEquals("1:4", t.props["text"]!!(frame).toString())
    }

    @Test fun componentsBindParameters() {
        val p =
            Compiler.compile(
                """
                widget {
                  state v = 3
                  component Stat(label, value, tint = accent) {
                    Text { text: "${'$'}{label}=${'$'}{value}"; color: tint }
                  }
                  Stat { label: "cpu"; value: v * 2; weight: 1 }
                }
                """.trimIndent(),
            )
        val use = p.root.single() as WNode.Use
        assertTrue("weight" in use.wrapper)
        val host = TestHost(p.globals.size)
        host.slots[0] = Num(3.0)
        val caller = Env.root(host)
        val env = Env(host, Env.root(host), Array(use.args.size) { i -> { use.args[i](caller) } })
        assertEquals("cpu=6", ((use.body.single() as WNode.Element).props["text"]!!)(env).toString())
    }

    @Test fun canvasCompiles() {
        val p =
            Compiler.compile(
                """
                widget {
                  Canvas {
                    let r = min(width, height) / 2
                    circle(width / 2, height / 2, r, stroke: accent, width: 2)
                    rotate(time * 90, width / 2, height / 2) {
                      line(width / 2, height / 2, width / 2, 0, stroke: red, cap: round)
                    }
                  }
                }
                """.trimIndent(),
            )
        assertTrue(p.animated)
    }

    @Test fun spritesAreRunsOfLetters() {
        val s = Sprite.parse(Lst(listOf(Str(".aab."), Str("c..cc"))))
        assertEquals(listOf('a', 'b', 'c', 'c'), s.letters.toList())
        assertEquals(listOf(1, 0, 2, 3, 0, 1, 0, 1, 1, 3, 1, 2), s.runs.toList())
        // One string of lines works too; indentation and blank edges don't count.
        assertEquals(s.runs.toList(), Sprite.parse(Str("\n    .aab.\n    c..cc\n  ")).runs.toList())
        Compiler.compile("widget {\nmeta { owl: 3 }\nCanvas { sprite([\"ab\"], 0, 0, 4, palette: {a: red}) }\n}")
    }

    @Test fun newerFeaturesNeedTheirVersionDeclared() {
        assertTrue(compileError("widget {\nCanvas { sprite([\"ab\"], 0, 0, 4) }\n}").contains("sprite needs owl 3: add meta { owl: 3 }"))
        assertTrue(compileError("widget {\nmeta { owl: 2 }\nCanvas { sprite([\"ab\"], 0, 0, 4) }\n}").contains("needs owl 3"))
        assertTrue(compileError("widget {\nTerminal { }\n}").contains("Terminal needs owl 2"))
        assertEquals(3, Compiler.compile("widget {\nmeta { owl: 3 }\nTerminal { }\n}").level)
    }

    @Test fun sourcesAndMeta() {
        val p =
            Compiler.compile(
                """
                widget "cpu" {
                  meta { author: "simon"; version: 2 }
                  size: 3x2
                  minSize: 2x1
                  source stat = cmd('head -1 /proc/stat') every 2s timeout 5
                  source bat = android.battery
                  source now = clock
                  let hist = history(num(stat), 30)
                  Text { text: fmtTime(now, "HH:mm") }
                }
                """.trimIndent(),
            )
        assertEquals(3 to 2, p.size)
        assertEquals(2 to 1, p.minSize)
        assertEquals(Num(2.0), p.meta["version"])
        val s = p.globals[0] as Global.Source
        assertEquals(2.0, s.every, 0.0)
        assertEquals(5, s.timeout)
        assertEquals(30, (p.globals[3] as Global.Sampled).keep)
        assertEquals(listOf("head -1 /proc/stat"), p.commands)
    }

    @Test fun statusSourcesOnlyOnCmd() {
        val p = Compiler.compile("widget \"t\" {\nsource r = cmd('curl x') every 5m json status\nText { text: r.ok ? r.out.temp : r.err }\n}")
        val s = p.globals[0] as Global.Source
        assertTrue(s.status && s.json)
        assertTrue(compileError("widget \"t\" {\nsource r = stream('tail -f x') status\nText { text: r }\n}").contains("status only works on cmd"))
    }

    @Test fun bundledWidgetsCompile() {
        val dir = java.io.File("../app/src/main/assets/widgets")
        val files = dir.walk().filter { it.extension == "owl" }.toList()
        assertTrue("no widgets found in ${dir.absolutePath}", files.size > 5)
        for (f in files) {
            try {
                Compiler.compile(f.readText())
            } catch (e: CompileError) {
                fail("${f.name}: ${Source(f.readText()).format(e)}")
            }
        }
    }

    @Test fun needsLinux() {
        fun needs(src: String) = Compiler.compile("widget {\n$src\n}").needsLinux
        assertEquals(false, needs("source w = http(\"https://x.test\") json\nsource now = clock every 1m\nsource b = android.battery\nText { text: w.a }\nonTap: url(\"https://x.test\")"))
        assertEquals(true, needs("source r = cmd('date')\nText { text: r }"))
        assertEquals(true, needs("source r = stream('tail -f x')\nText { text: r }"))
        assertEquals(true, needs("meta { owl: 2 }\nTerminal { }"))
        // Without Linux, tapping app(...) or terminal(...) offers to install it instead.
        assertEquals(false, needs("onTap: app(\"btop\")\nText { text: \"x\" }"))
        assertEquals(true, needs("requires { pacman: [\"jq\"] }\nText { text: \"x\" }"))
        val dir = java.io.File("../app/src/main/assets/widgets")
        fun bundled(name: String) = Compiler.compile(java.io.File(dir, name).readText()).needsLinux
        assertEquals(false, bundled("10-today.owl"))
        assertEquals(false, bundled("20-fetch.owl"))
        assertEquals(true, bundled("45-terminal.owl"))
    }

    @Test fun persistOnStateAndHistory() {
        val p = Compiler.compile("widget \"t\" {\nsource x = clock\nstate n = 0 persist\nstate m = 1\nlet h = history(x, 5) persist\nText { text: n }\n}")
        assertTrue((p.globals[1] as Global.State).persist)
        assertTrue(!(p.globals[2] as Global.State).persist)
        assertTrue((p.globals[3] as Global.Sampled).persist)
        // On the next line, `persist` isn't a modifier.
        assertTrue(compileError("widget \"t\" {\nstate n = 0\npersist\nText { text: n }\n}").isNotEmpty())
        assertTrue(compileError("widget \"t\" {\nlet d = 2 persist\nText { text: d }\n}").contains("persist a state"))
    }

    @Test fun persistedValuesRoundTrip() {
        val p = Compiler.compile("widget \"t\" {\nsource x = clock every 10s\nstate n = 0 persist\nstate m = 1\nlet h = history(x, 5) persist\nText { text: n }\n}")
        assertEquals(listOf(1, 3), PersistedValues.slots(p))
        val hist = Lst((1..5).map { Num(it.toDouble()) })
        val text = PersistedValues.encode(p, listOf(Num(7.0), hist), 1000)
        assertTrue(text.startsWith("{\"v\":1,\"savedAt\":1000,"))
        assertEquals(mapOf(1 to Num(7.0), 3 to hist), PersistedValues.decode(p, text, 1010.0))
        // A history older than its source takes to fill it (5 samples, 10 s apart) is all gap: state still comes back.
        assertEquals(mapOf(1 to Num(7.0)), PersistedValues.decode(p, text, 1050.0))
        // A longer one saved by an older version of the widget keeps its newest samples.
        val long = PersistedValues.encode(p, listOf(Num(7.0), Lst((1..8).map { Num(it.toDouble()) })), 1000)
        assertEquals(Lst((4..8).map { Num(it.toDouble()) }), PersistedValues.decode(p, long, 1001.0)[3])
        // Unknown names, values of the wrong shape and unreadable files restore nothing.
        assertEquals(emptyMap<Int, Value>(), PersistedValues.decode(p, "{\"v\":1,\"savedAt\":1000,\"values\":{\"old\":1,\"h\":3}}", 1001.0))
        assertEquals(emptyMap<Int, Value>(), PersistedValues.decode(p, "not json", 1001.0))
    }

    @Test fun jsonWritesWhatItReads() {
        val v = Json.parse("""{"a": [1, 2.5, null, true], "s": "q\"\\\n\u0001", "o": {}}""")
        assertEquals(v, Json.parse(Json.write(v)!!))
        assertEquals(null, Json.write(Lst(listOf(Col("accent", 0)))))
    }

    @Test fun tileSizeNamesUnlessShadowed() {
        val tiled =
            object : Host by TestHost(0) {
                override fun tile(name: String): Value = if (name == "cols") Num(3.0) else Null
            }
        val p = Compiler.compile("widget \"t\" {\nText { text: cols < 3 ? \"small\" : \"wide ${'$'}{rows}\" }\n}")
        assertEquals("wide …", (p.root.single() as WNode.Element).props["text"]!!(Env.root(tiled)).text())
        // A widget's own `rows` wins over the built-in.
        assertEquals("2", text("len(rows)", "state rows = [1, 2]"))
    }

    @Test fun cornersFollowTheLauncherStyle() {
        val soft =
            object : Host by TestHost(0) {
                override fun tile(name: String): Value = if (name == "corners") Num(0.5) else Null
            }
        val p = Compiler.compile("widget {\n meta { owl: 6 }\n Text { text: \"${'$'}{8 * corners}\" } }")
        assertEquals("4", (p.root.single() as WNode.Element).props["text"]!!(Env.root(soft)).text())
        assertTrue(compileError("widget {\n meta { owl: 5 }\n Text { text: corners } }").contains("corners needs owl 6"))
    }

    @Test fun requiresDeclaresPackagesPerManager() {
        val p = Compiler.compile("widget {\n  requires { pacman: [\"jq\", \"curl\"] }\n  requires { pacman: \"jq\" }\n  Text { text: \"x\" }\n}")
        assertEquals(mapOf("pacman" to listOf("jq", "curl")), p.requires)
        assertTrue(compileError("widget { requires { brew: [\"jq\"] }\n Text { text: \"x\" } }").contains("unknown package manager 'brew' (pacman)"))
        // Names go into a shell command, so anything but a plain package name is refused.
        assertTrue(compileError("widget { requires { pacman: [\"jq; rm -rf /\"] }\n Text { text: \"x\" } }").contains("isn't a valid pacman package name"))
        assertTrue(compileError("widget { requires { pacman: [1] }\n Text { text: \"x\" } }").contains("package names are strings"))
    }

    @Test fun programsComeFromCommands() {
        val p = Compiler.compile("widget {\n  source a = cmd(\"ps -eo rss | head -5 | awk '{print \$1}'\")\n  onTap: terminal(\"btop\")\n  Text { text: a }\n}")
        assertEquals(setOf("ps", "head", "awk", "btop"), p.programs)
        val loop = Compiler.compile("widget {\n  state x = \"a\"\n  source a = cmd('for a in b c; do command -v ${'$'}a; done')\n  onTap: terminal(x)\n  Text { text: a }\n}")
        assertEquals(emptySet<String>(), loop.programs)
    }

    @Test fun shellWordsSkipQuotingBuiltinsFunctionsAndHeredocs() {
        val script =
            """
            auth="${'$'}{HOME}/auth.json"
            show() {
              [[ -s "${'$'}cache" ]] || return 1
              jq -c '{use, day} | if . then "x|y; z" else empty end' "${'$'}cache"
            }
            token=${'$'}(jq -er '.key' "${'$'}auth" 2>/dev/null) || show
            code=${'$'}(curl -sS -w '%{http_code}' --config - <<EOF
            url = "https://example.com"
            header = "Accept: json"
            EOF
            ) || code=000
            exec 9>>"${'$'}{auth}.lock"
            timeout 5 flock -w 8 9 || true
            day=${'$'}(date -d "${'$'}end" '+%a' 2>/dev/null) # comment: notaprogram
            FOO=1 /usr/bin/env printf '%s' "${'$'}(uname -r | cut -d- -f1)"
            """.trimIndent()
        assertEquals(setOf("jq", "curl", "flock", "date", "uname", "cut"), ShellWords.programs(script))
    }

    @Test fun shellWordsSkipArithmetic() {
        val script = "i=${'$'}((i + 1)); n=${'$'}(( (i * 2) + 1 )); echo ${'$'}((n)) | tee x"
        assertEquals(setOf("tee"), ShellWords.programs(script))
    }

    @Test fun budgetStopsRunawayWork() {
        try {
            text("range(1000).map(a => range(1000).map(b => a * b))")
            fail("expected the budget to stop this")
        } catch (e: WidgetError) {
            assertTrue(e.message!!.contains("too much work"))
        }
    }

    @Test fun errorsPointAtTheProblem() {
        val e = compileError("widget {\n  Text { txt: \"hi\" }\n}")
        assertTrue(e, e.startsWith("2:10: Text has no property 'txt'"))
        assertTrue(compileError("widget {\n  Text { text: nope }\n}").startsWith("2:16: unknown name 'nope'"))
        assertTrue(compileError("widget {\n  Text { text: \"a\" \n}").contains("expected"))
        assertTrue(compileError("widget { Text { text: a } \n state a = 1 \n onTap: b = 2 }").contains("unknown state 'b'"))
        assertTrue(compileError("widget { Column { circle(1, 2, 3) } }").contains("inside a Canvas"))
        assertTrue(compileError("widget { let a = b\n let b = a\n Text { text: a } }").contains("unknown name 'b'"))
    }

    @Test fun enumsAndThemeColors() {
        val p = Compiler.compile("widget { Text { text: \"x\"; style: muted; color: red; align: center } }")
        val el = p.root.single() as WNode.Element
        assertEquals(Str("muted"), el.props["style"]!!.const)
        assertEquals("red", (el.props["color"]!!.const as Col).theme)
        assertEquals(Str("center"), el.props["align"]!!.const)
    }

    @Test fun enumWordsWinOverNamesTheyShadow() {
        val p = Compiler.compile("widget {\n  source top = clock every 1m\n  component Stat(label) { Text { text: label; style: label } }\n  Row { align: top; Stat { label: \"cpu\" } }\n}")
        val row = p.root.single() as WNode.Element
        assertEquals(Str("top"), row.props["align"]!!.const)
        val text = (row.children.single() as WNode.Use).body.single() as WNode.Element
        assertEquals(Str("label"), text.props["style"]!!.const)
    }

    @Test fun colorLiteralsAndHelpers() {
        val p = Compiler.compile("widget { Text { text: \"x\"; color: #ff000080 } }")
        val c = (p.root.single() as WNode.Element).props["color"]!!.const as Col
        assertEquals(0x80ff0000.toInt(), c.argb)
        assertEquals("#ff808080", text("mix(#000000, #ffffff, 0.5)"))
    }

    @Test fun json() {
        assertEquals(Obj(mapOf("a" to Lst(listOf(Num(1.0), Str("x\n"), TRUE, Null)))), Json.parse("""{"a": [1, "x\n", true, null]}"""))
        assertEquals(Str("not json"), Json.parseOrText("not json"))
    }

    @Test fun triggersRunTheirStatements() {
        val p =
            Compiler.compile(
                """
                widget {
                  meta { owl: 4 }
                  state endsAt = 100
                  state n = 0
                  every 10s { n = n + 1 }
                  at endsAt { notify("focus", "done"); endsAt = 0 }
                  when n > 2 { n = 0 }
                  on n { notify("n", "${'$'}{old} -> ${'$'}{value}") }
                  when late { n = 5 }
                  let late = n > 9
                  Text { text: n }
                }
                """.trimIndent(),
            )
        assertEquals(listOf("every", "at", "when", "on", "when"), p.triggers.map { it.kind.word })
        assertEquals(Num(10.0), p.triggers[0].expr.const)
        val host = TestHost(p)
        val root = Env.root(host)
        p.triggers[0].action.run(root, emptyList())
        assertEquals(Num(1.0), host.slots[1])
        p.triggers[1].action.run(root, emptyList())
        assertEquals(listOf("notify focus, done"), host.actions)
        assertEquals(Num(0.0), host.slots[0])
        p.triggers[3].action.run(root, listOf(Num(2.0), Num(1.0)))
        assertEquals("notify n, 1 -> 2", host.actions.last())
    }

    @Test fun triggerErrors() {
        assertTrue(compileError("widget {\n state n = 0\n every 1s { n = 1 }\n Text { text: n } }").contains("every needs owl 4"))
        assertTrue(compileError("widget {\n meta { owl: 4 }\n state n = 0\n every 0.5s { n = 1 }\n Text { text: n } }").contains("at most once a second"))
        assertTrue(compileError("widget {\n meta { owl: 4 }\n when true { refresh() }\n Text { text: 1 } }").contains("needs a value that changes"))
        assertTrue(compileError("widget {\n meta { owl: 4 }\n state n = 0\n Column { when n { n = 1 } } }").contains("only allowed at the top"))
        assertTrue(compileError("widget {\n meta { owl: 4 }\n state n = 0\n when n n = 1\n Text { text: n } }").contains("expected '{'"))
        // Still plain names elsewhere.
        assertEquals("1", text("on + at", "state on = 1\nstate at = 0"))
    }

    @Test fun fileSources() {
        val p = Compiler.compile("widget {\n meta { owl: 4 }\n source c = file(\"~/.cache/x.json\") json\n Text { text: c.a } }")
        val src = p.globals[0] as Global.Source
        assertEquals("file", src.kind)
        assertTrue(src.json)
        assertTrue(p.needsLinux)
        assertEquals(listOf("read file ~/.cache/x.json"), p.commands)
        assertTrue(compileError("widget {\n meta { owl: 4 }\n source c = file(\"x\")\n Text { text: c } }").contains("starting with / or ~/"))
        assertTrue(compileError("widget {\n meta { owl: 4 }\n source c = file(\"/x\") every 5s\n Text { text: c } }").contains("takes no every"))
        assertTrue(compileError("widget {\n source c = file(\"/x\")\n Text { text: c } }").contains("file needs owl 4"))
    }

    @Test fun androidAlarmModesAndPhoneActions() {
        val p = Compiler.compile("widget {\n meta { owl: 7 }\n source a = android.alarm\n source m = android.modes\n Text { text: a.time; onTap: { torch(\"toggle\"); torch(!m.torch); share(\"hi\"); haptic() } } }")
        assertEquals(listOf("android.alarm", "android.modes"), p.globals.map { (it as Global.Source).kind })
        assertTrue(!p.needsLinux)
        assertTrue(compileError("widget {\n meta { owl: 7 }\n Text { text: 1; onTap: torch(\"blink\") } }").contains("torch takes"))
        assertTrue(compileError("widget {\n meta { owl: 6 }\n source m = android.modes\n Text { text: 1 } }").contains("android.modes needs owl 7"))
        assertTrue(compileError("widget {\n meta { owl: 6 }\n Text { text: 1; onTap: haptic() } }").contains("haptic needs owl 7"))
        assertTrue(compileError("widget {\n meta { owl: 6 }\n Text { text: 1; onTap: share(\"x\") } }").contains("share needs owl 7"))
    }

    @Test fun androidApps() {
        val p = Compiler.compile("widget {\n meta { owl: 8 }\n source a = android.apps every 1m\n Image { src: a[0].icon; onTap: androidApp(a[0].key) } }")
        assertEquals(listOf("android.apps"), p.globals.map { (it as Global.Source).kind })
        assertEquals(listOf("read installed Android apps (names, packages and icons)"), p.commands)
        assertTrue(compileError("widget {\n meta { owl: 7 }\n source a = android.apps\n Text { text: 1 } }").contains("android.apps needs owl 8"))
    }

    @Test fun appsGoBackALevelWhileBackHolds() {
        val app = Compiler.compile("app \"x\" {\n meta { owl: 8 }\n state page = \"\"\n title: page == \"\" ? \"x\" : page\n back: page != \"\"\n onBack: page = \"\"\n Text { text: 1; onTap: page = \"tar\" } }")
        val host = TestHost(app)
        val env = Env.root(host)
        assertFalse(app.back!!(env).truthy)
        (app.screen!![0] as WNode.Element).handlers["onTap"]!!.run(env, emptyList())
        assertTrue(app.back!!(env).truthy)
        assertEquals("tar", app.screenTitle!!(env).toString())
        app.onBack!!.run(env, emptyList())
        assertFalse(app.back!!(env).truthy)
        assertNull(Compiler.compile("app {\n meta { owl: 8 }\n Text { text: 1 } }").onBack)
    }

    @Test fun edgeAppsDrawUnderTheNavigationBar() {
        val app = Compiler.compile("app {\n meta { owl: 8 }\n edge: true\n Column { scroll: true\n Spacer { height: screenInset } } }")
        assertTrue(app.edge)
        assertFalse(Compiler.compile("app {\n meta { owl: 8 }\n Text { text: 1 } }").edge)
        assertTrue(compileError("app {\n meta { owl: 8 }\n state e = true\n edge: e\n Text { text: 1 } }").contains("edge is true or false"))
        assertTrue(compileError("widget {\n meta { owl: 8 }\n Text { text: 1 }\n screen { Text { text: screenInset } } }").contains("screenInset is for apps"))
        // Not reserved: a widget's own screenInset still works.
        Compiler.compile("widget {\n let screenInset = 0\n Text { text: screenInset } }")
    }

    @Test fun appsAreScreensOfTheirOwn() {
        val app = Compiler.compile("app \"Notes\" {\n meta { owl: 8 }\n title: \"My notes\"\n state n = 0\n Column { Text { text: n }\n Button { text: \"x\"; onTap: close() } } }")
        assertEquals(OwlKind.App, app.kind)
        assertEquals("Notes", app.name)
        assertTrue(app.root.isEmpty())
        assertEquals(1, app.screen!!.size)
        assertEquals("My notes", app.screenTitle!!(Env.root(TestHost(app))).toString())
        val plain = Compiler.compile("app {\n meta { owl: 8 }\n Input { focus: true } }")
        assertEquals("app", plain.name)
        assertEquals(OwlKind.Widget, Compiler.compile("widget {\n Text { text: 1 } }").kind)

        assertTrue(compileError("app {\n Text { text: 1 } }").contains("needs owl 8"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n size: 2x2\n Text { text: 1 } }").contains("only takes title, icon, back and edge"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n onTap: close()\n Text { text: 1 } }").contains("handlers go on the nodes"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n back: true\n Text { text: 1 } }").contains("back and onBack go together"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n onBack: close()\n Text { text: 1 } }").contains("back and onBack go together"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n screen { Text { text: 1 } } }").contains("is a screen already"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n Text { text: 1; onTap: screen() } }").contains("close() closes it"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n }").contains("needs at least one node"))
        assertTrue(compileError("gadget {\n Text { text: 1 } }").contains("an owl file starts with"))
        assertEquals(OwlKind.App, OwlKind.of("// notes\n/* a\n b */ app \"x\" { Text { text: \"unterminated } }"))
        assertEquals(OwlKind.App, OwlKind.of("  app{"))
        assertEquals(OwlKind.Widget, OwlKind.of("panel {"))
        assertEquals(OwlKind.Widget, OwlKind.of("application {"))
    }

    @Test fun appIconsAreSvgInTheFile() {
        val svg = "<svg viewBox=\"0 0 48 48\"><circle cx=\"24\" cy=\"24\" r=\"20\" fill=\"currentColor\"/></svg>"
        val app = Compiler.compile("app \"x\" {\n meta { owl: 8 }\n title: \"X\"\n icon: \"\"\"\n  $svg\n \"\"\"\n Text { text: 1 } }")
        assertEquals(svg, app.icon)
        // title still comes from title:, wherever icon: is
        assertEquals("X", app.screenTitle!!(Env.root(TestHost(app))).toString())
        assertEquals(null, Compiler.compile("app {\n meta { owl: 8 }\n Text { text: 1 } }").icon)
        assertTrue(compileError("app {\n meta { owl: 8 }\n icon: \"logo.png\"\n Text { text: 1 } }").contains("one <svg>"))
        // Drawn before the app runs: no expressions.
        assertTrue(compileError("app {\n meta { owl: 8 }\n state c = \"red\"\n icon: \"<svg fill='${'$'}{c}'></svg>\"\n Text { text: 1 } }").contains("an SVG in a string"))
        // Or an image embedded as an asset.
        val png = java.util.Base64.getEncoder().encodeToString(byteArrayOf(-119, 80, 78, 71))
        val withPng = Compiler.compile("app {\n meta { owl: 8 }\n asset logo = base64\"$png\"\n icon: logo\n Text { text: 1 } }")
        assertEquals(listOf(-119, 80, 78, 71), withPng.iconImage!!.map { it.toInt() })
        assertEquals(null, withPng.icon)
        assertTrue(compileError("app {\n meta { owl: 8 }\n icon: logo\n Text { text: 1 } }").contains("names an asset"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n icon: \"<svg>${"x".repeat(17000)}</svg>\"\n Text { text: 1 } }").contains("over 16 KB"))
    }

    @Test fun androidNotificationsAndVolume() {
        val p = Compiler.compile("widget {\n meta { owl: 4 }\n source n = android.notifications\n source v = android.volume\n Text { text: n.count; onTap: volume(\"up\") } }")
        assertEquals(listOf("android.notifications", "android.volume"), p.globals.map { (it as Global.Source).kind })
        assertTrue(p.commands.single().startsWith("read Android notifications"))
        assertTrue(!p.needsLinux)
        assertTrue(compileError("widget {\n meta { owl: 4 }\n Text { text: 1; onTap: volume(\"loud\") } }").contains("volume takes"))
        assertTrue(compileError("widget {\n meta { owl: 4 }\n Text { text: 1; onTap: notification(\"read\", \"k\") } }").contains("notification takes"))
        assertTrue(compileError("widget {\n Text { text: 1; onTap: media(\"rewind\") } }").contains("media takes"))
        // The words themselves, and a level in percent for volume, compile.
        Compiler.compile("widget {\n meta { owl: 7 }\n Text { text: 1; onTap: { media(\"next\"); volume(\"50\"); volume(\"mute\"); torch(\"on\"); notification(\"dismiss\", \"k\") } } }")
        assertTrue(compileError("widget {\n Text { text: 1; onTap: volume(50) } }").contains("volume needs owl 4"))
    }

    @Test fun sliderAndInput() {
        val p =
            Compiler.compile(
                "widget {\n meta { owl: 4 }\n state v = 5\n state q = \"\"\n" +
                    " Slider { value: v; max: 10; step: 1; onChange: v = value }\n" +
                    " Input { placeholder: \"search\"; clear: true; onSubmit: q = value; onChange: q = value } }",
            )
        val slider = p.root[0] as WNode.Element
        val host = TestHost(p)
        slider.handlers["onChange"]!!.run(Env.root(host), listOf(Num(7.0)))
        assertEquals(Num(7.0), host.slots[0])
        val input = p.root[1] as WNode.Element
        input.handlers["onSubmit"]!!.run(Env.root(host), listOf(Str("hi")))
        assertEquals(Str("hi"), host.slots[1])
        assertTrue(compileError("widget {\n meta { owl: 4 }\n state v = 1\n Text { text: v; onChange: v = value } }").contains("onChange only works on Slider and Input"))
        assertTrue(compileError("widget {\n Slider { value: 1 } }").contains("Slider needs owl 4"))
    }

    @Test fun screens() {
        val p =
            Compiler.compile(
                "widget \"w\" {\n meta { owl: 5 }\n state n = 1\n onTap: screen()\n Text { text: n }\n" +
                    " screen {\n  title: \"more\"\n  Column { Text { text: \"\${n} \${screenWidth}\" }; Button { text: \"x\"; onTap: close() } }\n }\n}",
            )
        assertEquals(1, p.root.size)
        assertEquals(1, p.screen!!.size)
        assertEquals(Str("more"), p.screenTitle!!.const)
        assertEquals(1, p.nodeCount)
        assertTrue(compileError("widget {\n Text { text: 1 }\n screen { Text { text: 2 } } }").contains("screen needs owl 5"))
        assertTrue(compileError("widget {\n meta { owl: 5 }\n onTap: screen()\n Text { text: 1 } }").contains("need a screen { } block"))
        assertTrue(compileError("widget {\n meta { owl: 5 }\n Text { text: 1 }\n screen { Text { text: 2 } }\n screen { Text { text: 3 } } }").contains("one screen"))
        assertTrue(compileError("widget {\n meta { owl: 5 }\n Column { screen { Text { text: 2 } } } }").contains("top of the widget"))
        assertTrue(compileError("widget {\n meta { owl: 5 }\n Text { text: 1 }\n screen { padding: 4; Text { text: 2 } } }").contains("only takes title"))
        // The screen has its own node budget: 600 nodes fit there but not in a tile.
        val many = "Column { for i in range(1) { " + "Spacer { }".repeat(600) + " } }"
        Compiler.compile("widget {\n meta { owl: 5 }\n Text { text: 1 }\n screen { $many } }")
        assertTrue(compileError("widget {\n meta { owl: 5 }\n $many }").contains("more than 500 nodes"))
    }

    @Test fun inputFocus() {
        val p = Compiler.compile("widget {\n meta { owl: 6 }\n state s = \"\"\n Text { text: 1 }\n screen { Input { focus: s == \"\" } } }")
        assertTrue("focus" in (p.screen!!.single() as WNode.Element).props)
        // A component can go in either; on a tile the launcher ignores focus.
        Compiler.compile("widget {\n meta { owl: 6 }\n component Field() { Input { focus: true } }\n Field { }\n screen { Field { } } }")
        assertTrue(compileError("widget {\n meta { owl: 5 }\n Text { text: 1 }\n screen { Input { focus: true } } }").contains("Input.focus needs owl 6"))
        assertTrue(compileError("widget {\n meta { owl: 6 }\n Input { focus: true } }").contains("only works in a screen"))
    }

    @Test fun optionalSources() {
        val p = Compiler.compile("widget {\n meta { owl: 5 }\n source x = cmd(\"uname\") optional\n Text { text: linux ? x : \"no linux\"; onTap: terminal(\"btop\") } }")
        assertTrue((p.globals[0] as Global.Source).optional)
        assertTrue(!p.needsLinux)
        assertEquals(listOf(Compiler.IF_LINUX + "uname", "btop"), p.commands)
        // run(...) still needs Linux: it would fail unseen without it.
        assertTrue(Compiler.compile("widget {\n Text { text: 1; onTap: run(\"x\") } }").needsLinux)
        assertTrue(compileError("widget {\n source x = cmd(\"uname\") optional\n Text { text: x } }").contains("optional needs owl 5"))
        assertTrue(compileError("widget {\n meta { owl: 5 }\n source x = http(\"https://a.b\") optional\n Text { text: x } }").contains("optional only works"))
        assertTrue(compileError("widget {\n Text { text: linux } }").contains("linux needs owl 5"))
    }

    @Test fun hostileInputFailsAsCompileErrors() {
        // Other scripts' digits are digits to isDigit() but not to toDouble().
        assertTrue(compileError("widget { Text { text: \u0663 } }").contains("unexpected character"))
        assertTrue(compileError("widget { Text { text: 1\u0663 } }").contains("unexpected character"))
        assertTrue(compileError("widget { Text { text: ${"(".repeat(50_000)}1${")".repeat(50_000)} } }").contains("too deeply nested"))
        assertTrue(compileError("widget { Text { text: ${List(100_000) { "1" }.joinToString("+")} } }").contains("too deeply nested"))
        assertTrue(compileError("widget {\n meta { owl: 4 }\n state n = 0\n every sqrt(-1) { n = 1 }\n Text { text: n } }").contains("at most once a second"))
        val clock = Compiler.compile("widget {\n source c = clock every sqrt(-1)\n Text { text: c } }").globals[0] as Global.Source
        assertEquals(1.0, clock.every, 0.0)
    }

    /** Constant folding ticks a budget: it must not carry over from one compile to the next. */
    @Test fun constantFoldingBudgetIsPerCompile() {
        val src = "widget { Text { text: len([${List(1000) { "str(1)" }.joinToString(", ")}]) } }"
        repeat(250) { Compiler.compile(src) }
    }

    @Test fun padWidthIsCapped() {
        assertEquals(Builtins.MAX_LIST, text("pad(\"x\", w)", "state w = 2000000000").length)
    }

    @Test fun jsonNestingIsLimited() {
        try {
            Json.parse("[".repeat(100_000))
            fail("expected a json error")
        } catch (e: WidgetError) {
            assertTrue(e.message!!.contains("nested too deeply"))
        }
        assertEquals(Str("[".repeat(100_000)), Json.parseOrText("[".repeat(100_000)))
        assertEquals(Lst(listOf(Lst(emptyList()))), Json.parse("[[]]"))
    }

    @Test fun shQuotesAValueAsOneShellWord() {
        val owl6 = "meta { owl: 6 }"
        assertEquals("'a b'", text("sh(\"a b\")", owl6))
        assertEquals("'it'\\''s; rm -rf ~'", text("sh(\"it's; rm -rf ~\")", owl6))
        assertEquals("''", text("sh(null)", owl6))
        assertEquals("'3'", text("sh(3)", owl6))
        assertTrue(compileError("widget {\n Text { text: sh(\"x\") } }").contains("sh needs owl 6"))
    }

    @Test fun commandsWithQuotedValuesAreListedAsTemplates() {
        fun commands(body: String) = Compiler.compile("widget {\n meta { owl: 6 }\n state v = \"x\"\n state n = 1\n$body\n Text { text: v } }").commands
        assertEquals(listOf("sed -i ${Compiler.NUMBER}d ~/todo.md"), commands("onTap: run(\"sed -i ${'$'}{int(n)}d ~/todo.md\")"))
        assertEquals(listOf("true", "curl -d ${Compiler.VALUE} https://ha.local"), commands("onTap: run(n > 1 ? \"true\" : \"curl -d ${'$'}{sh(v)} https://ha.local\")"))
        assertEquals(listOf("echo ${Compiler.VALUE}x"), commands("source o = cmd(\"echo \" + sh(v) + \"x\") every 1m"))
        // Unquoted, or quoted where bash would undo it: it could run anything.
        for (bad in listOf("echo ${'$'}{v}", "echo \\\"${'$'}{sh(v)}\\\"", "echo '${'$'}{sh(v)}'", "echo ok # ${'$'}{sh(v)}", "cat <<E\\n${'$'}{sh(v)}\\nE")) {
            assertEquals(bad, listOf("run(…) built from widget values"), commands("onTap: run(\"$bad\")"))
        }
        assertTrue(ShellWords.quotable("a ${Compiler.VALUE} 'b' \"c\" d#e ${Compiler.VALUE}"))
        assertFalse(ShellWords.quotable("a ${'$'}'x ${Compiler.VALUE}'"))
        assertEquals(listOf("jq"), Compiler.compile("widget {\n meta { owl: 6 }\n state v = \"x\"\n onTap: run(\"jq -n ${'$'}{sh(v)}\")\n Text { text: v } }").programs.toList())
    }

    @Test fun encEncodesAValueAsOneUrlComponent() {
        val owl8 = "meta { owl: 8 }"
        assertEquals("a%20b%2Fc%3Fd%26e%23f%3Dg", text("enc(\"a b/c?d&e#f=g\")", owl8))
        assertEquals("%C3%BC", text("enc(\"ü\")", owl8))
        assertEquals("", text("enc(null)", owl8))
        assertEquals("3", text("enc(3)", owl8))
        assertTrue(compileError("widget {\n meta { owl: 7 }\n Text { text: enc(\"x\") } }").contains("enc needs owl 8"))
    }

    @Test fun urlsWithEncodedValuesAreListedAsTemplates() {
        fun commands(url: String) = Compiler.compile("widget {\n meta { owl: 8 }\n state v = \"x\"\n state n = 1\n source s = http($url) json\n Text { text: v } }").commands
        val v = Compiler.VALUE
        assertEquals(listOf("http GET https://a.b/c/$v?d=${Compiler.NUMBER}"), commands("\"https://a.b/c/${'$'}{enc(v)}?d=${'$'}{int(n)}\""))
        // A null branch pauses the source: nothing to list for it.
        assertEquals(listOf("http GET https://a.b/q?x=$v"), commands("n > 1 ? \"https://a.b/q?x=\" + enc(v) : null"))
        assertEquals(listOf("http GET https://a.b/1", "http GET http://c.d:8080/2"), commands("n > 1 ? \"https://a.b/1\" : \"http://c.d:8080/2\""))
        // A value in the host, or not through enc(...), could send it anywhere.
        for (bad in listOf("\"https://${'$'}{enc(v)}/x\"", "\"https://a.b${'$'}{enc(v)}/x\"", "\"https://a.b/${'$'}{v}\"", "\"https://a.b/${'$'}{sh(v)}\"", "\"${'$'}{enc(v)}\"", "n > 1 ? null : null")) {
            assertEquals(bad, listOf("http GET (a URL built from widget values)"), commands(bad))
        }
    }

    @Test fun urlValuesNotThroughEncAreWarnedAboutWithTheFix() {
        fun warnings(url: String) = Compiler.compile("widget {\n meta { owl: 8 }\n state v = \"x\"\n source s = http($url) json\n Text { text: v } }").warnings
        assertEquals(listOf("4:35: a value in this URL isn't put through enc(), so the store's review can't show where it goes: write \${enc(lower(v))} (a number can go through int(n) or fixed(n, 4) instead; a path with / in it can't, so write its fixed parts out and enc() only the values between them)"), warnings("\"https://a.b/q=${'$'}{lower(v)}\""))
        assertTrue(warnings("\"https://a.b/q=\" + v").single().contains("write enc(v)"))
        assertTrue(warnings("\"https://a.b/${'$'}{sh(v)}\"").single().contains("enc(...) instead of sh(...)"))
        assertTrue(warnings("\"https://${'$'}{enc(v)}/x\"").single().contains("the server in this URL comes from a value"))
        assertEquals(emptyList<String>(), warnings("\"https://a.b/${'$'}{enc(v)}\""))
        // The whole URL from a value: enc() would break it, so the fix is to write it out.
        assertTrue(warnings("v").single().contains("this URL is all a value (v)"))
        assertTrue(warnings("v == \"\" ? null : v").single().contains("this URL is all a value"))
        assertTrue(warnings("\"https://a.b/${'$'}{replace(v, \" \", \"%20\")}\"").single().contains("drop the replace(...)"))
        // A fixed let is fixed text: listed, nothing to warn about.
        val p = Compiler.compile("widget {\n meta { owl: 8 }\n let api = \"https://a.b\"\n state v = \"x\"\n source s = http(\"${'$'}{api}/q/${'$'}{enc(v)}\") json\n Text { text: v } }")
        assertEquals(listOf("http GET https://a.b/q/${Compiler.VALUE}"), p.commands)
        assertEquals(emptyList<String>(), p.warnings)
    }

    @Test fun fmtTimeFollowsLanguageChanges() {
        val was = Locale.getDefault()
        try {
            // Noon on Sunday 4 January 1970 in UTC: the same day from UTC-12 to UTC+11.
            val sunday = "fmtTime(${3 * 86400 + 43200}, \"EEEE MMMM\")"
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("Sunday January", text(sunday))
            Locale.setDefault(Locale.GERMAN)
            assertEquals("Sonntag Januar", text(sunday))
        } finally {
            Locale.setDefault(was)
        }
    }
}
