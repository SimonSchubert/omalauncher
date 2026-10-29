package dev.omalauncher.widgets.lang

import dev.omalauncher.widgets.market.Review
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        // Before init: a state's first value can call builtins, which tick it.
        override val budget = Budget(10_000)

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
            method: String,
        ) {
            actions += "post $slot $url $body $headers" + (if (method != "POST") " $method" else "")
        }
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
        assertEquals(setOf("floor", "ceil", "abs", "sqrt", "sin", "cos", "tan", "rad", "deg", "sign", "asin", "acos", "round"), Builtins.numeric1.keys)
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
        // Past what a Long holds after scaling, round leaves the number as it is.
        assertEquals("999998000001", text("round(x * 999999, 8)", "state x = 999999"))
        assertEquals("999998000001", text("round(x, 8)", "state x = 999998000001"))
        assertEquals("1e+21", text("round(pow(x, 21))", "state x = 10"))
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

    /** Numbers read as JavaScript writes them, with no exponent from 1e-6 to 1e21. */
    @Test fun numbersReadWithoutAnExponent() {
        assertEquals("1792231445.5", text("1792231445.5"))
        assertEquals("-12345678.25", text("-12345678.25"))
        assertEquals("10000000000000000", text("10000000000000000"))
        assertEquals("0.0005", text("0.0005"))
        assertEquals("0.0000025", text("0.0000025"))
        assertEquals("0.25", text("0.25"))
        assertEquals("1e-7", text("0.0000001"))
        assertEquals("-2.5e-7", text("-0.00000025"))
        assertEquals("1e+21", text("1000000000000000000000"))
        assertEquals("1.5e+22", text("15000000000000000000000"))
        assertEquals("0", text("-0"))
        assertEquals("{\"t\":1792231445.5}", text("toJson({t: 1792231445.5})", owl9))
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

    /** post's method: sends PUT, PATCH or DELETE (owl 12), listed as what it is. */
    @Test fun postMethods() {
        val p =
            Compiler.compile(
                "app \"t\" {\n meta { owl: 12 }\n back: true\n" +
                    " onBack: { post(\"https://x.test/items/1\", null, {}, method: \"delete\"); post(\"https://x.test/b\", {a: 1}, {}, method: \"PUT\"); post(\"https://x.test/c\", {a: 2}, {}, method: \"patch\") }\n" +
                    " Text { text: 1 } }",
            )
        val host = TestHost(p)
        p.onBack!!.run(Env.root(host), emptyList())
        assertEquals(listOf("post null https://x.test/items/1 null {} DELETE", "post null https://x.test/b {a: 1} {} PUT", "post null https://x.test/c {a: 2} {} PATCH"), host.actions)
        assertEquals(setOf("http DELETE https://x.test/items/1", "http PUT https://x.test/b", "http PATCH https://x.test/c"), p.commands.toSet())
        val app = "app \"t\" {\n meta { owl: 12 }\n back: true\n onBack: post(\"https://x.test/\", null, {}, "
        assertTrue(compileError("app \"t\" {\n meta { owl: 11 }\n back: true\n onBack: post(\"https://x.test/\", null, {}, method: \"PUT\")\n Text { text: 1 } }").contains("needs owl 12"))
        assertTrue(compileError(app + "method: \"HEAD\")\n Text { text: 1 } }").contains("method: is one of"))
        assertTrue(compileError(app + "retries: 3)\n Text { text: 1 } }").contains("no other named arguments"))
    }

    /** A secret only goes into a request's headers and body, or a check that it's set (owl 12). */
    @Test fun secretsOnlyGoIntoRequests() {
        val w = "widget {\n meta { owl: 12 }\n state token = null secret\n state out = null\n"
        val p =
            Compiler.compile(
                w + " source s = http(token == null ? null : \"https://x.test/a\", {Authorization: \"Bearer ${'$'}{token}\"})\n" +
                    " onTap: { if token == null { token = \"t\" } else { out = post(\"https://x.test/b\", {t: token}, {}) } }\n Text { text: token != \"\" ? \"in\" : \"out\" } }",
            )
        val host = TestHost(p)
        assertEquals("in", (p.root.single() as WNode.Element).props["text"]!!(Env.root(host)).toString())
        p.onTap!!.run(Env.root(host), emptyList())
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals(listOf("post 1 https://x.test/b {t: t} {}"), host.actions)
        assertTrue((p.globals[0] as Global.State).secret)
        for (leak in listOf("Text { text: token }", "Text { text: \"x\"; onTap: copy(token) }", "Text { text: \"x\"; onTap: run(\"echo ${'$'}{token}\") }", "Text { text: (token ?? \"\") == \"a\" }")) {
            assertTrue(leak, compileError("$w $leak }").contains("token is a secret"))
        }
        compileError(w + " on token { out = 1 }\n Text { text: 1 } }").let { assertTrue(it, it.contains("token is a secret")) }
        compileError(w + " source s = http(\"https://x.test/${'$'}{token}\")\n Text { text: 1 } }").let { assertTrue(it, it.contains("token is a secret")) }
        // In a URL only as enc(token), for an API that takes its key in the query, on a server written out.
        val inUrl = Compiler.compile(w + " source s = http(token == null ? null : \"https://Api.test/q?appid=${'$'}{enc(token)}\")\n onTap: post(\"https://b.test/${'$'}{enc(token)}\", {}, method: \"DELETE\")\n Text { text: 1 } }")
        assertEquals(listOf("https://api.test", "https://b.test"), inUrl.secretHosts["token"])
        assertTrue(inUrl.commands.toString(), inUrl.commands.none { "built from widget values" in it })
        TestHost(inUrl).let { h ->
            h.write(0, Str("a b"))
            assertEquals("https://Api.test/q?appid=a%20b", (inUrl.globals.first { it.name == "s" } as Global.Source).arg!!(Env.root(h)).toString())
        }
        for (leak in listOf("\"https://x.test/?k=${'$'}{enc(upper(token))}\"", "\"https://${'$'}{enc(token)}.x.test/\"", "out ?? \"https://x.test/?k=${'$'}{enc(token)}\"")) {
            assertTrue(leak, compileError("$w source s = http($leak)\n Text { text: 1 } }").contains("token is a secret"))
        }
        compileError(w + " Text { text: enc(token) } }").let { assertTrue(it, it.contains("token is a secret")) }
        // Bound to the servers it goes to, which are written out, so an edit can't send it elsewhere unnoticed.
        assertEquals(mapOf("token" to listOf("https://x.test")), p.secretHosts)
        compileError(w + " state h = \"x.test\"\n source s = http(\"https://${'$'}{h}/a\", {A: token})\n Text { text: 1 } }").let { assertTrue(it, it.contains("server it goes to is written out")) }
        compileError(w + " state u = \"https://x.test\"\n onTap: post(u, {t: token})\n Text { text: 1 } }").let { assertTrue(it, it.contains("server it goes to is written out")) }
        assertEquals(listOf("https://x.test:8443"), Compiler.compile(w + " onTap: post(\"https://X.test:8443\", {t: token})\n Text { text: 1 } }").secretHosts["token"])
        compileError(w + " onTap: post(\"http://x.test/a\", {t: token})\n Text { text: 1 } }").let { assertTrue(it, it.contains("only to an https:// server")) }
        // A user name before the host would bind it to "x.test@evil.test", and send it to evil.test.
        compileError(w + " onTap: post(\"https://x.test@evil.test/a\", {t: token})\n Text { text: 1 } }").let { assertTrue(it, it.contains("server it goes to is written out")) }
        // Bound to the server written out at the URL's start, whatever follows it: a value not through enc(), more ways than are listed.
        val many = "${'$'}{out == null ? \"a\" : \"b\"}".repeat(4)
        for (url in listOf("\"https://x.test/${'$'}{out}\"", "\"https://x.test/$many\"", "\"https://x.test?k=${'$'}{enc(token)}\"")) {
            assertEquals(url, listOf("https://x.test"), Compiler.compile("$w source s = http($url, {A: token})\n Text { text: 1 } }").secretHosts["token"])
        }
        compileError(w + " source s = http(\"https://x.test${'$'}{out}\", {A: token})\n Text { text: 1 } }").let { assertTrue(it, it.contains("server it goes to is written out")) }
        // A let can't hold it; the error says to write it out where it's sent.
        compileError(w + " let auth = \"Bearer ${'$'}{token}\"\n Text { text: 1 } }").let { assertTrue(it, it.contains("not through a let")) }
        compileError("widget {\n meta { owl: 12 }\n state token = null secret persist\n Text { text: 1 } }").let { assertTrue(it, it.contains("without persist")) }
        compileError("widget {\n meta { owl: 11 }\n state token = null secret\n Text { text: 1 } }").let { assertTrue(it, it.contains("secret needs owl 12")) }
    }

    @Test fun base64() {
        assertEquals("aGk/Pz4=", text("toBase64(\"hi??>\")", "meta { owl: 12 }"))
        // base64url, unpadded, as a JWT or a link carries JSON.
        assertEquals("hi??>", text("fromBase64(\"aGk_Pz4\")", "meta { owl: 12 }"))
        assertEquals("pc", text("parseJson(fromBase64(\"eyJob3N0IjoicGMifQ\")).host", "meta { owl: 12 }"))
        assertEquals("null", text("fromBase64(\"*\")", "meta { owl: 12 }"))
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

    @Test fun shadersAnimateOnlyWhenTheyReadTime() {
        fun shader(body: String) = Compiler.compile("widget {\n  Shader { code: \"\"\"$body\"\"\" }\n}").animated
        assertTrue(shader("half4 main(float2 p) { return half4(sin(time), 0, 0, 1); }"))
        assertFalse(shader("half4 main(float2 p) { return half4(p.x / size.x, 0, 0, 1); }"))
        assertFalse(shader("half4 main(float2 p) { float times = 2.0; return half4(times, 0, 0, 1); }"))
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
        assertEquals(false, needs("requires { pacman: [] }\nText { text: \"x\" }"))
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

    @Test fun gapsAndBorderFollowTheLauncherStyle() {
        val style =
            object : Host by TestHost(0) {
                override fun tile(name: String): Value = mapOf("gapsIn" to Num(4.0), "gapsOut" to Num(6.0), "borderWidth" to Num(2.0))[name] ?: Null
            }
        val p = Compiler.compile("keybar \"k\" {\n meta { owl: 12 }\n Text { text: \"${'$'}{gapsIn / 2 + gapsOut + borderWidth}\" } }")
        assertEquals("10", (p.screen!!.single() as WNode.Element).props["text"]!!(Env.root(style)).text())
        assertTrue(compileError("widget {\n meta { owl: 11 }\n Box { border: borderWidth } }").contains("borderWidth needs owl 12"))
        // A widget's own names win at any level, as with `rows`: files from before owl 12 may use them.
        assertEquals("3", text("borderWidth", "state borderWidth = 3"))
        assertEquals("5", text("gapsIn + 1", "let gapsIn = 4"))
        Compiler.compile("widget {\n meta { owl: 10 }\n component C(gapsOut) { Box { padding: gapsOut } }\n C { gapsOut: 2 } }")
    }

    @Test fun tileGlassFollowsTheLauncherStyle() {
        val style =
            object : Host by TestHost(0) {
                override fun tile(name: String): Value = mapOf("tileOpacity" to Num(0.5), "tileFrost" to Num(24.0))[name] ?: Null
            }
        val p = Compiler.compile("face \"f\" {\n meta { owl: 13 }\n Text { text: \"${'$'}{tileOpacity} ${'$'}{tileFrost}\" } }")
        assertEquals("0.5 24", (p.root.single() as WNode.Element).props["text"]!!(Env.root(style)).text())
        assertTrue(compileError("widget {\n meta { owl: 12 }\n Box { frost: tileFrost } }").contains("tileFrost needs owl 13"))
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

    /** Doubling takes few steps but would take gigabytes: an OutOfMemoryError took the launcher down. */
    @Test fun textStopsGrowingPastItsCap() {
        for (expr in listOf(
            "len(reduce(range(n), (s, i) => s + s, \"a\"))",
            "len(reduce(range(n), (s, i) => \"\${s}\${s}\", \"a\"))",
            "len(toJson(reduce(range(n), (s, i) => [s, s], [1])))",
            "len(str(reduce(range(n), (s, i) => [s, s], [1])))",
            "len(join(reduce(range(n), (s, i) => [s + s], [\"a\"]), \",\"))",
        )) {
            try {
                text(expr, "meta { owl: 9 }\nstate n = 30")
                fail("expected $expr to stop at the cap")
            } catch (e: WidgetError) {
                assertTrue(e.message, e.message!!.contains("over 4M characters"))
            }
        }
        assertEquals("1048576", text("len(reduce(range(n), (s, i) => s + s, \"a\"))", "meta { owl: 9 }\nstate n = 20"))
    }

    /** A pattern that backtracks without end gives up after a moment, rather than freezing the thread that builds home. */
    @Test fun aRunawayRegexGivesUp() {
        val started = System.nanoTime()
        try {
            text("match(pad(\"\", n, \"a\"), \"(.*a){27}b\")", "state n = 32")
            fail("expected the regex to give up")
        } catch (e: WidgetError) {
            assertTrue(e.message, e.message!!.contains("takes too long"))
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
        // Refused at once from then on, and other patterns still work.
        try {
            text("match(s, \"(.*a){27}b\")", "state s = \"x\"")
            fail("expected the slow pattern to be refused")
        } catch (e: WidgetError) {
            assertTrue(e.message!!.contains("takes too long"))
        }
        assertEquals("[12, ab]", text("match(s, \"(\\\\d+)-(\\\\w+)\")", "state s = \"x 12-ab\""))
        assertEquals("[1, 2, 3]", text("matchAll(s, \"\\\\d\")", "state s = \"1a2b3\""))
    }

    /** A font in spans is listed for review like a `font:`, in a Text and in a Canvas text. */
    @Test fun fontsInSpansAreListed() {
        val p =
            Compiler.compile(
                "widget \"t\" {\nmeta { owl: 10 }\nstate u = \"x\"\n" +
                    "Column { Text { spans: [\"a \", {text: \"b\", font: \"https://evil.test/f.ttf\"}] }\n" +
                    "Canvas { text([{text: \"c\", font: u == \"x\" ? \"https://other.test/g.ttf\" : \"serif\"}], 0, 0) } }\n}",
            )
        assertEquals(setOf("font GET https://evil.test/f.ttf", "font GET https://other.test/g.ttf"), p.commands.toSet())
    }

    /** -0.0 is zero and nothing compares with NaN, on both the fast path and the general one. */
    @Test fun negativeZeroAndNaNCompareAsNumbers() {
        val d = "state x = 0\nstate t = \"-0\"\nstate nan = \"NaN\""
        assertEquals("false", text("-x < 0", d))
        assertEquals("false", text("x * -1 < 0", d))
        assertEquals("true", text("-x >= 0", d))
        assertEquals("false", text("t < 0", d))
        assertEquals("false", text("nan > 5", d))
        assertEquals("false", text("nan < 5", d))
        assertEquals("false", text("num(nan) > 5", d))
        assertEquals("[-1, 0, 2]", text("sort([2, -x, -1])", d))
    }

    @Test fun formattingWhatIsNoNumberGivesNull() {
        val d = "meta { owl: 9 }\nstate z = 0"
        assertEquals("null", text("fmtTime(num(\"NaN\") + z, \"HH:mm\")", d))
        assertEquals("null", text("fmtNum(num(\"NaN\") + z)", d))
        assertEquals("null", text("parseTime(\"2026-01-01T00:00:00+99:99\")", d))
        assertEquals("1767225600", text("parseTime(\"2026-01-01T00:00:00Z\")", d))
    }

    /** A URL, command or image src with a value still loading waits for it, rather than asking with "…" in it. */
    @Test fun aRequestWaitsForEveryPartOfIt() {
        val p =
            Compiler.compile(
                "widget \"t\" {\nmeta { owl: 8 }\nstate n = null\nstate q = null\n" +
                    "source a = http(\"https://a.example/x?d=\${int(n)}\")\nsource b = cmd(\"echo \${int(n)}\")\n" +
                    "source c = http(\"https://a.example/x?q=\${enc(q)}\")\n" +
                    "Column { Image { src: \"https://a.example/\${int(n)}.png\" }\nText { text: \"d=\${int(n)}\" } }\n}",
            )
        val host = TestHost(p)
        fun source(name: String) = (p.globals.first { it.name == name } as Global.Source).arg!!(Env.root(host))
        assertEquals(Null, source("a"))
        assertEquals(Null, source("b"))
        // enc() and sh() of null are empty on purpose: a search with nothing typed yet.
        assertEquals("https://a.example/x?q=", source("c").toString())
        val column = p.root.single() as WNode.Element
        val (image, text) = column.children.map { it as WNode.Element }
        assertEquals(Null, image.props["src"]!!(Env.root(host)))
        assertEquals("d=…", text.props["text"]!!(Env.root(host)).toString())
        host.slots[p.globals.indexOfFirst { it.name == "n" }] = Num(7.0)
        assertEquals("https://a.example/x?d=7", source("a").toString())
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

    /** What code written from QML or JavaScript habit gets wrong: the error says what to write instead. */
    @Test fun errorsSayTheFix() {
        assertTrue(compileError("widget { let n = 1e9\n Text { text: n } }").contains("write 1e9 as 1000000000"))
        assertTrue(compileError("widget { let n = 2.5e-3\n Text { text: n } }").contains("write 2.5e-3 as 0.0025"))
        assertTrue(compileError("widget { let n = 1e+9\n Text { text: n } }").contains("write 1e+9 as 1000000000"))
        assertTrue(compileError("widget { let a = b + 1\n let b = 2\n Text { text: a } }").contains("unknown name 'b': it's declared below"))
        assertTrue(compileError("widget { Column { let a = b\n let b = 2\n Text { text: a } } }").contains("unknown name 'b': it's declared below"))
        assertTrue(compileError("widget { Column { width: k\n let k = 3\n Text { text: k } } }").contains("unknown name 'k': it's a let inside a node"))
        assertTrue(compileError("widget { let f = x => x * 2\n Text { text: f(2) } }").contains("'f' is a let, and a let can't be called"))
        assertTrue(compileError("widget { state a = true\n Text { text: \"x\"; style: a ? title : muted } }").contains("as in style: title; in an expression, quote it"))
        assertTrue(compileError("widget { state n = 0\n component Chip(l) { Text { text: l } }\n Chip { l: \"a\"; onTap: n = 1 } }").contains("or wrap it: Box { onTap: …; Chip { … } }"))
        // The quoted words work, read as text like the bare ones.
        Compiler.compile("widget { state a = true\n Text { text: \"x\"; align: a ? \"start\" : \"end\"; style: a ? \"title\" : \"muted\" } }")
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

    @Test fun androidNetwork() {
        val p =
            Compiler.compile(
                "widget {\n meta { owl: 13 }\n source n = android.network every 2s\n state down = 0\n" +
                    " on n { if old != null && value.rx >= old.rx { down = (value.rx - old.rx) / (value.uptime - old.uptime) } }\n" +
                    " Text { text: fmtBytes(down) + \"/s\" } }",
            )
        val n = p.globals.first() as Global.Source
        assertEquals("android.network", n.kind)
        assertEquals(2.0, n.every, 0.0)
        assertTrue(!p.needsLinux)
        assertTrue(compileError("widget {\n meta { owl: 12 }\n source n = android.network\n Text { text: 1 } }").contains("android.network needs owl 13"))
    }

    @Test fun androidPower() {
        val p = Compiler.compile("widget {\n meta { owl: 15 }\n source p = android.power every 2s\n Text { text: p.current + \" mA \" + p.watts + \" W\" } }")
        val s = p.globals.first() as Global.Source
        assertEquals("android.power", s.kind)
        assertEquals(2.0, s.every, 0.0)
        assertTrue(!p.needsLinux)
        assertTrue(compileError("widget {\n meta { owl: 14 }\n source p = android.power every 2s\n Text { text: 1 } }").contains("android.power needs owl 15"))
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

    @Test fun androidAgents() {
        val p = Compiler.compile("widget {\n meta { owl: 10 }\n source ag = android.agents\n Text { text: ag.default.bin; onTap: terminal(ag.default.command, ag.default.name) } }")
        assertEquals(listOf("android.agents"), p.globals.map { (it as Global.Source).kind })
        assertTrue(compileError("widget {\n meta { owl: 9 }\n source ag = android.agents\n Text { text: 1 } }").contains("android.agents needs owl 10"))
    }

    @Test fun inputsCanLeaveOutTheirLine() {
        Compiler.compile("widget {\n meta { owl: 10 }\n Input { underline: false } }")
        assertTrue(compileError("widget {\n meta { owl: 9 }\n Input { underline: false } }").contains("Input.underline needs owl 10"))
    }

    @Test fun popupsNeedOnDismissAndOwl10() {
        val ok = "widget {\n meta { owl: 10 }\n state open = false\n Box { Text { text: \"x\"; onTap: open = true }\n Popup { visible: open; position: above; onDismiss: open = false\n Text { text: \"y\" } } } }"
        Compiler.compile(ok)
        assertTrue(compileError(ok.replace("owl: 10", "owl: 9")).contains("Popup needs owl 10"))
        assertTrue(compileError(ok.replace("; onDismiss: open = false", "")).contains("a Popup needs onDismiss"))
        assertTrue(compileError("widget {\n meta { owl: 10 }\n Text { text: 1; onDismiss: refresh() } }").contains("onDismiss only works on Popup"))
    }

    @Test fun recentAppsOpenAndAgentsAsk() {
        val p =
            Compiler.compile(
                "widget {\n meta { owl: 10 }\n source r = android.recent\n state q = \"\"\n" +
                    " Image { src: r[0].icon; onTap: open(r[0].key) }\n Input { text: q; onSubmit: ask(value) }\n Text { text: 1; onTap: ask(\"what's new?\", \"claude\") } }",
            )
        assertEquals(listOf("android.recent"), p.globals.filterIsInstance<Global.Source>().map { it.kind })
        assertEquals(listOf("read the recently opened apps", "ask the agent something built from widget values", "ask the agent: what's new?"), p.commands)
        for (old in listOf("source r = android.recent\n Text { text: 1 }", "Text { text: 1; onTap: open(\"l:foot\") }", "Text { text: 1; onTap: ask(\"hi\") }")) {
            assertTrue(compileError("widget {\n meta { owl: 9 }\n $old }").contains("needs owl 10"))
        }
    }

    @Test fun drawerAppsAndTheirMenu() {
        val p =
            Compiler.compile(
                "widget {\n meta { owl: 11 }\n source d = android.drawer\n" +
                    " Column { for a in d.apps { Text { text: a.name; onTap: appAction(a.actions[0], a.key) } } }\n" +
                    " Text { text: d.updates; onTap: appAction(\"uninstall\", d.apps[0].key) }\n Text { text: 1; onTap: workApps(\"toggle\") } }",
            )
        assertEquals(listOf("android.drawer"), p.globals.filterIsInstance<Global.Source>().map { it.kind })
        assertEquals(listOf("read the launcher's apps (names and icons)", "act on an app from the drawer's menu: any of its entries", "act on an app from the drawer's menu: uninstall"), p.commands)
        assertTrue(compileError("widget {\n meta { owl: 11 }\n Text { text: 1; onTap: appAction(\"delete\", \"a:x\") } }").contains("appAction takes pin, info"))
        assertTrue(compileError("widget {\n meta { owl: 11 }\n Text { text: 1; onTap: workApps(\"off\") } }").contains("workApps takes pause, resume, toggle"))
        for (old in listOf("source d = android.drawer\n Text { text: 1 }", "Text { text: 1; onTap: appAction(\"pin\", \"l:foot\") }", "Text { text: 1; onTap: workApps(\"pause\") }")) {
            assertTrue(compileError("widget {\n meta { owl: 10 }\n $old }").contains("needs owl 11"))
        }
    }

    @Test fun chunkMakesRows() {
        val owl = "meta { owl: 11 }"
        assertEquals("[[1, 2], [3, 4], [5]]", text("chunk([1, 2, 3, 4, 5], 2)", owl))
        assertEquals("[]", text("chunk([], 3)", owl))
        // Fewer than one per row would never end: it takes one.
        assertEquals("[[1], [2]]", text("chunk([1, 2], 0)", owl))
        assertEquals("[[1, 2]]", text("chunk([1, 2], 5)", owl))
        // Rows with their place, as a hand-built grid keys them.
        assertEquals("r0:a,b r1:c", text("join(chunk([\"a\", \"b\", \"c\"], 2).map((row, i) => \"r\" + i + \":\" + join(row, \",\")), \" \")", owl))
        assertTrue(compileError("widget {\n meta { owl: 10 }\n let r = chunk([1], 1)\n Text { text: 1 } }").contains("needs owl 11"))
    }

    @Test fun rankAsTheBarRanks() {
        val apps =
            "meta { owl: 11 }\n let apps = [{name: \"Calendar\", score: 0}, {name: \"Calculator\", score: 5}, {name: \"Café\", score: 0}, " +
                "{name: \"WhatsApp\", alias: \"WhatsApp work\", score: 0}, {name: \"Files\"}]"
        assertEquals("Calculator,Calendar", text("join(rank(apps, \"cal\").map(a => a.name), \",\")", apps))
        assertEquals("Café", text("join(rank(apps, \"cafe\").map(a => a.name), \",\")", apps))
        assertEquals("WhatsApp", text("join(rank(apps, \"work\").map(a => a.name), \",\")", apps))
        assertEquals("0", text("len(rank(apps, \" \"))", apps))
        assertEquals("Visual Studio Code", text("join(rank([\"Claude Code\", \"Visual Studio Code\"], \"vsc\"), \",\")", apps))
        // Enter opens at initials or better, by name or alias, for one line of two words at most.
        val enter = listOf("Files" to "fi", "Visual Studio Code" to "vsc", "Files" to "il", "Visual Studio Code" to "vis stu code", "Visual Studio Code" to "vis\\ncode")
        assertEquals("true,true,false,false,false", enter.joinToString(",") { (n, q) -> text("strongMatch(\"$n\", \"$q\")", "meta { owl: 11 }") })
        assertEquals("true", text("strongMatch(apps[3], \"work\")", apps))
        for (old in listOf("let r = rank([\"a\"], \"a\")\n Text { text: 1 }", "let s = strongMatch(\"a\", \"a\")\n Text { text: 1 }", "Column { lazy: true }", "Grid { lazy: true }")) {
            assertTrue(compileError("widget {\n meta { owl: 10 }\n $old }").contains("needs owl 11"))
        }
    }

    @Test fun panelsAreViewsAGestureOpens() {
        val p = Compiler.compile("panel \"apps\" {\n meta { owl: 11 }\n state sent = \"\"\n onSubmit: sent = value\n Text { text: query; onTap: close() } }")
        assertEquals(OwlKind.Panel, p.kind)
        assertEquals("apps", p.name)
        assertTrue(p.root.isEmpty())
        assertEquals(1, p.screen!!.size)
        assertNotNull(p.onSubmit)
        assertEquals(OwlKind.Panel, OwlKind.of("// a drawer of my own\npanel \"x\" { }"))

        assertTrue(compileError("panel {\n meta { owl: 10 }\n Text { text: 1 } }").contains("needs owl 11"))
        assertTrue(compileError("panel {\n meta { owl: 11 }\n size: 2x2\n Text { text: 1 } }").contains("a panel only takes enter"))
        assertTrue(compileError("panel {\n meta { owl: 11 }\n onTap: close()\n Text { text: 1 } }").contains("only onSubmit"))
        // Arrow and Tab keys from the bar: at the panel's top, never on a node.
        val keys = Compiler.compile("panel {\n meta { owl: 11 }\n state pick = 0\n onKey: pick = value == \"up\" ? pick + 1 : pick - 1\n Text { text: pick } }")
        assertNotNull(keys.onKey)
        assertTrue(compileError("panel {\n meta { owl: 11 }\n Text { text: 1; onKey: close() } }").contains("onKey goes at the top of a panel"))
        assertTrue(compileError("panel {\n meta { owl: 11 }\n screen { Text { text: 1 } } }").contains("a panel is a screen already"))
        assertTrue(compileError("app {\n meta { owl: 11 }\n Text { text: query } }").contains("only a panel has"))
        assertTrue(compileError("widget {\n meta { owl: 11 }\n Text { text: query } }").contains("only a panel has"))
        // A panel's own state named query wins, as declarations shadow the tile's names.
        Compiler.compile("panel {\n meta { owl: 11 }\n state query = 1\n Text { text: query } }")
    }

    @Test fun aPanelSaysHowMuchItCoversHome() {
        assertNull(Compiler.compile("panel {\n meta { owl: 11 }\n Text { text: 1 } }").opacity)
        assertNotNull(Compiler.compile("panel {\n meta { owl: 13 }\n opacity: 0.9\n Text { text: 1 } }").opacity)
        // Worked out as it shows, like the rest of the panel.
        assertNotNull(Compiler.compile("panel {\n meta { owl: 13 }\n state dim = true\n opacity: dim ? 0.8 : 1\n Text { text: 1 } }").opacity)
        assertTrue(compileError("panel {\n meta { owl: 12 }\n opacity: 0.9\n Text { text: 1 } }").contains("needs owl 13"))
        for (bad in listOf("1.5", "-0.1", "\"half\"")) {
            assertTrue(compileError("panel {\n meta { owl: 13 }\n opacity: $bad\n Text { text: 1 } }").contains("from 0 (home shows through) to 1"))
        }
        assertTrue(compileError("widget {\n meta { owl: 13 }\n opacity: 0.9\n Text { text: 1 } }").isNotEmpty())
    }

    @Test fun aPanelCanKeepTheKeyboardDown() {
        assertTrue(Compiler.compile("panel {\n meta { owl: 11 }\n Text { text: 1 } }").keyboard)
        assertFalse(Compiler.compile("panel {\n meta { owl: 15 }\n keyboard: false\n Text { text: 1 } }").keyboard)
        assertTrue(Compiler.compile("panel {\n meta { owl: 15 }\n keyboard: true\n Text { text: 1 } }").keyboard)
        assertTrue(compileError("panel {\n meta { owl: 14 }\n keyboard: false\n Text { text: 1 } }").contains("needs owl 15"))
        assertTrue(compileError("panel {\n meta { owl: 15 }\n state k = false\n keyboard: k\n Text { text: 1 } }").contains("keyboard is true or false"))
        assertTrue(compileError("widget {\n meta { owl: 15 }\n keyboard: false\n Text { text: 1 } }").isNotEmpty())
    }

    @Test fun extraKeysAreAFileOfTheirOwn() {
        val p =
            Compiler.compile(
                "keybar \"mine\" {\n meta { owl: 12 }\n source kb = android.modifiers\n state down = \"\"\n" +
                    " Row { Box { repeats: true; onPress: { if repeat == 0 { down = \"up\" }; pressKey(\"up\") }; onRelease: down = \"\" }\n" +
                    " Text { text: get(kb ?? {}, \"ctrl\", \"off\"); onTap: latch(\"ctrl\"); onLongPress: latch(\"ctrl\", \"locked\") }\n" +
                    " Button { text: \"ls\"; onPress: typeText(\"ls\\n\") } } }",
            )
        assertEquals(OwlKind.Keybar, p.kind)
        assertEquals("mine", p.name)
        assertTrue(p.root.isEmpty())
        assertFalse(p.needsLinux)
        assertEquals(listOf("press keys in the Linux window you're typing in: up", "type into the Linux window you're typing in: ls\n"), p.commands)
        val host = TestHost(p)
        val env = Env.root(host)
        val row = (p.screen!!.single() as WNode.Element).children.map { it as WNode.Element }
        // The press, then a repeat: the state is set once, the key sent each time.
        row[0].handlers["onPress"]!!.run(env, listOf(Num(0.0), Num(0.0), Num(0.0)))
        row[0].handlers["onPress"]!!.run(env, listOf(Num(0.0), Num(0.0), Num(1.0)))
        assertEquals(Str("up"), host.slots[1])
        row[0].handlers["onRelease"]!!.run(env, emptyList())
        assertEquals(Str(""), host.slots[1])
        row[1].handlers["onLongPress"]!!.run(env, listOf(Num(0.0), Num(0.0)))
        assertEquals(listOf("pressKey up", "pressKey up", "latch ctrl, locked"), host.actions)

        assertTrue(compileError("keybar {\n meta { owl: 11 }\n Text { text: 1 } }").contains("needs owl 12"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n size: 2x2\n Text { text: 1 } }").contains("takes no properties"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n onTap: pressKey(\"esc\")\n Text { text: 1 } }").contains("handlers go on the keys"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n screen { Text { text: 1 } } }").contains("no screen { }"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n Text { text: 1; onTap: close() } }").contains("comes and goes with the keyboard"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n Text { text: 1; repeats: true; onTap: pressKey(\"up\") } }").contains("add onPress"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n Text { text: 1; onTap: pressKey(\"ctrl+escape\") } }").contains("pressKey takes a key"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n Text { text: 1; onTap: latch(\"super\") } }").contains("latch takes ctrl, alt, shift"))
        assertTrue(compileError("keybar {\n meta { owl: 12 }\n Text { text: 1; onTap: latch(\"ctrl\", \"twice\") } }").contains("once, locked, off"))
        assertEquals(Lst(listOf(Str("a"))), Compiler.compile("keybar {\n meta { owl: 12 }\n Text { text: keys({a: 1}) } }").let { (it.screen!![0] as WNode.Element).props["text"]!!(Env.root(TestHost(it))) })
        assertTrue(compileError("widget {\n meta { owl: 12 }\n Text { text: typeText(1) } }").contains("is an action"))
        // Anywhere in owl 12, not only in extra keys.
        for (old in listOf("Text { text: 1; onTap: pressKey(\"esc\") }", "Text { text: 1; onPress: haptic() }", "Text { text: 1; onRelease: haptic() }")) {
            assertTrue(compileError("widget {\n meta { owl: 11 }\n $old }").contains("needs owl 12"))
            Compiler.compile("widget {\n meta { owl: 12 }\n $old }")
        }
    }

    @Test fun photosPickedPasteIntoTheWindow() {
        val p = Compiler.compile("keybar {\n meta { owl: 15 }\n Box { onTap: pickImages(); Text { text: 1 } } }")
        assertEquals(listOf("paste images you pick on Android into the Linux window you're typing in"), p.commands)
        val host = TestHost(p)
        (p.screen!!.single() as WNode.Element).handlers["onTap"]!!.run(Env.root(host), listOf(Num(0.0), Num(0.0)))
        assertEquals(listOf("pickImages "), host.actions)
        assertTrue(compileError("keybar {\n meta { owl: 14 }\n Box { onTap: pickImages(); Text { text: 1 } } }").contains("needs owl 15"))
        assertTrue(compileError("keybar {\n meta { owl: 15 }\n Box { onTap: pickImages(\"a\"); Text { text: 1 } } }").isNotEmpty())
        for (h in listOf("onPress", "onRelease")) {
            assertTrue(compileError("keybar {\n meta { owl: 15 }\n Box { $h: pickImages(); Text { text: 1 } } }").contains("put it in onTap"))
        }
        // A handler inside another node's doesn't carry over: the outer one is onTap again after it.
        Compiler.compile("keybar {\n meta { owl: 15 }\n Box { onPress: haptic(); onTap: pickImages(); Text { text: 1 } } }")
    }

    @Test fun speechDictatesIntoASource() {
        val src = "widget {\n meta { owl: 15 }\n source said = android.speech\n on said.final { if value { typeText(value) } }\n Text { text: said.text; onTap: speech(\"toggle\") } }"
        val p = Compiler.compile(src)
        assertEquals("android.speech", (p.globals.single() as Global.Source).kind)
        assertEquals("listen through the microphone after a tap, and read what you say", p.commands.first())
        val host = TestHost(p)
        (p.root.single() as WNode.Element).handlers["onTap"]!!.run(Env.root(host), listOf(Num(0.0), Num(0.0)))
        assertEquals(listOf("speech toggle"), host.actions)
        assertTrue(compileError(src.replace("owl: 15", "owl: 14")).contains("needs owl 15"))
        assertTrue(compileError(src.replace("\"toggle\"", "\"listen\"")).contains("speech takes start, stop, toggle, cancel"))
        // Nothing comes back from it: what's heard is the source's.
        assertTrue(compileError(src.replace("text: said.text", "text: speech(\"start\")")).contains("gives nothing back"))
        assertTrue(compileError("widget {\n meta { owl: 15 }\n Text { text: 1; onTap: speech(\"start\") } }").contains("source said = android.speech"))
        // Starting may ask for the microphone: from a tap, not a press or a trigger. Stopping from anywhere.
        for (h in listOf("onPress", "onRelease")) assertTrue(compileError(src.replace("onTap", h)).contains("start it in onTap"))
        Compiler.compile(src.replace("onTap: speech(\"toggle\")", "onTap: speech(\"start\"); onRelease: speech(\"stop\")"))
        assertTrue(compileError(src.replace("typeText(value)", "speech(\"start\")")).contains("not in a trigger"))
        Compiler.compile(src.replace("typeText(value)", "speech(\"stop\")"))
        // Only on said.final may type what was heard: not said.text, though it ends with the same words.
        assertTrue(p.triggers.single().dictation)
        assertFalse(Compiler.compile(src.replace("on said.final", "on said.text")).triggers.single().dictation)
        val other = "widget {\n meta { owl: 15 }\n source said = android.speech\n state final = \"\"\n on final { typeText(value) }\n Text { text: said.text; onTap: speech(\"toggle\") } }"
        assertFalse(Compiler.compile(other).triggers.single().dictation)
    }

    @Test fun keyNamesAndCombos() {
        assertEquals(KeyNames.Combo(1, emptyList()), KeyNames.combo("esc"))
        assertEquals(KeyNames.Combo(46, listOf(29)), KeyNames.combo("ctrl+c"))
        assertEquals(KeyNames.Combo(46, listOf(29)), KeyNames.combo("CTRL+c"))
        // Ctrl+C as people write it, not Ctrl+Shift+C (a terminal's copy); a capital alone is still shifted.
        assertEquals(KeyNames.Combo(46, listOf(29)), KeyNames.combo("ctrl+C"))
        assertEquals(KeyNames.Combo(46, listOf(29, 42)), KeyNames.combo("ctrl+shift+c"))
        assertEquals(KeyNames.Combo(30, listOf(42)), KeyNames.combo("A"))
        assertEquals(KeyNames.Combo(110, listOf(42)), KeyNames.combo("shift+insert"))
        assertEquals(KeyNames.Combo(20, listOf(29, 56)), KeyNames.combo("ctrl+alt+t"))
        // A shifted character holds Shift itself; + on its own, or last after a +, is the key.
        assertEquals(KeyNames.Combo(39, listOf(42)), KeyNames.combo(":"))
        assertEquals(KeyNames.Combo(13, listOf(29, 42)), KeyNames.combo("ctrl++"))
        assertEquals(KeyNames.Combo(13, listOf(42)), KeyNames.combo("+"))
        assertEquals(KeyNames.Combo(88, emptyList()), KeyNames.combo("f12"))
        for (bad in listOf("", "escape", "hyper+a", "ctrl+", "é", "ctrl+ab")) assertNull(bad, KeyNames.combo(bad))
    }

    @Test fun drawerCellsAndMenus() {
        val p =
            Compiler.compile(
                "panel {\n meta { owl: 11 }\n state menu = []\n enter: query == \"\" ? \"\" : \"⏎ ask\"\n" +
                    " Column { lazy: true; reverse: true; for i in range(3) { Image { src: \"icon:l:foot\"; label: \"foot\"; onLongPress: menu = shortcuts(\"a:x\") } } } }",
            )
        assertNotNull(p.enter)
        assertEquals(listOf("read an app's shortcuts (the actions its long-press menu offers)"), p.commands)
        assertTrue(compileError("panel {\n meta { owl: 11 }\n Text { text: 1; onTap: shortcuts(\"a:x\") } }").contains("puts an app's shortcuts into a state"))
        assertTrue(compileError("panel {\n meta { owl: 11 }\n Column { reverse: true } }").contains("add lazy: true"))
        assertTrue(compileError("panel {\n meta { owl: 11 }\n size: 2x2\n Text { text: 1 } }").contains("a panel only takes enter"))
        for (old in listOf("state s = []\n Text { text: 1; onTap: s = shortcuts(\"a:x\") }", "Image { src: \"x.png\"; label: \"x\" }", "Text { text: 1; minLines: 2 }", "Column { lazy: true; reverse: true }")) {
            assertTrue(compileError("widget {\n meta { owl: 10 }\n $old }").contains("needs owl 11"))
        }
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

    @Test fun appsWithoutAStatusBarStepAroundTheCamera() {
        Compiler.compile("app {\n meta { owl: 12 }\n statusBar: false\n Column { padding: [12, screenTop, 12, 0]\n Text { text: 1 } } }")
        assertTrue(compileError("app {\n meta { owl: 11 }\n statusBar: false\n Spacer { height: screenTop } }").contains("needs owl 12"))
        assertTrue(compileError("widget {\n meta { owl: 12 }\n Text { text: 1 }\n screen { Text { text: screenTop } } }").contains("screenTop is for apps"))
    }

    @Test fun edgeFacesDrawUnderTheStatusBar() {
        val face = Compiler.compile("face {\n meta { owl: 14 }\n edge: true\n Column { padding: [12, screenTop, 12, 0]\n Text { text: 1 } } }")
        assertTrue(face.edge)
        assertFalse(Compiler.compile("face {\n meta { owl: 13 }\n Text { text: 1 } }").edge)
        assertTrue(compileError("face {\n meta { owl: 13 }\n edge: true\n Text { text: 1 } }").contains("needs owl 14"))
        assertTrue(compileError("face {\n meta { owl: 14 }\n state e = true\n edge: e\n Text { text: 1 } }").contains("edge is true or false"))
        assertTrue(compileError("face {\n meta { owl: 14 }\n Text { text: 1 }\n screen { Text { text: screenTop } } }").contains("screenTop is for apps"))
        Compiler.compile("face {\n meta { owl: 14 }\n edge: true\n Column { padding: [screenLeft, 0, screenRight, 0]\n Text { text: 1 } } }")
        // Without edge a face starts below the status bar and beside a cutout, so there's nothing for them to measure.
        assertTrue(compileError("face {\n meta { owl: 14 }\n Spacer { width: screenLeft } }").contains("screenLeft is for faces with edge"))
        assertTrue(compileError("face {\n meta { owl: 14 }\n Spacer { height: screenTop } }").contains("screenTop is for apps"))
        assertTrue(compileError("face {\n meta { owl: 14 }\n edge: false\n Spacer { height: screenTop } }").contains("screenTop is for apps"))
        assertTrue(compileError("app {\n meta { owl: 14 }\n Spacer { width: screenRight } }").contains("screenRight is for faces"))
    }

    @Test fun longPressIsForPagesAppsAndScreens() {
        Compiler.compile("face {\n meta { owl: 10 }\n Text { text: 1; onLongPress: close() }\n screen { Text { text: 2 } } }")
        Compiler.compile("app {\n meta { owl: 10 }\n state n = 0\n Text { text: n; onLongPress: n = x } }")
        Compiler.compile("widget {\n meta { owl: 10 }\n Text { text: 1; onTap: screen() }\n screen { Text { text: 2; onLongPress: close() } } }")
        // A component can go anywhere: on a tile the launcher leaves its long presses to the home screen.
        Compiler.compile("widget {\n meta { owl: 10 }\n component Hold() { Text { text: 1; onLongPress: run(\"true\") } }\n Hold { } }")
        assertTrue(compileError("widget {\n meta { owl: 10 }\n Text { text: 1; onLongPress: run(\"true\") } }").contains("long-press on a tile belongs to the home screen"))
        assertTrue(compileError("app {\n meta { owl: 9 }\n Text { text: 1; onLongPress: close() } }").contains("onLongPress needs owl 10"))
    }

    @Test fun facesAreWholeHomePages() {
        val face = Compiler.compile("face \"start\" {\n meta { owl: 10 }\n Text { text: 1 } }")
        assertEquals(OwlKind.Face, face.kind)
        assertEquals("start", face.name)
        assertTrue(face.isFace)
        assertNull(face.size)
        assertNull(face.statusBar)
        // Left of page 1 unless it says: 0, then -1, -2, ... further left.
        assertEquals(0, face.page)
        assertEquals(-2, Compiler.compile("face {\n meta { owl: 10 }\n page: -2\n Text { text: 1 } }").page)
        assertEquals(3, Compiler.compile("face {\n meta { owl: 10 }\n page: 3\n Text { text: 1 } }").page)
        assertEquals(false, Compiler.compile("face {\n meta { owl: 10 }\n statusBar: false\n Text { text: 1 } }").statusBar?.const?.truthy)
        // A tile can't go left of page 1.
        assertEquals(1, Compiler.compile("widget {\n size: 2x2\n page: 0\n Text { text: 1 } }").page)
        // Nor so far out that the pages beside it wrap around Int.
        assertEquals(-1000, Compiler.compile("face {\n meta { owl: 10 }\n page: -2147483648\n Text { text: 1 } }").page)
        assertEquals(1000, Compiler.compile("widget {\n page: 2147483647\n Text { text: 1 } }").page)
        // A bare / as the command compiles rather than throwing past the compile error.
        Compiler.compile("widget {\n source s = cmd(\"/\")\n Text { text: s } }")
        assertFalse(Compiler.compile("widget {\n Text { text: 1 } }").isFace)

        assertTrue(compileError("face {\n Text { text: 1 } }").contains("needs owl 10"))
        assertTrue(compileError("face {\n meta { owl: 10 }\n size: 2x2\n Text { text: 1 } }").contains("unknown face property 'size'"))
        // Read as it changes: a face can hide the status bar for a while.
        assertNotNull(Compiler.compile("face {\n meta { owl: 10 }\n state s = true\n statusBar: !s\n Text { text: 1; onTap: s = !s } }").statusBar)
        assertTrue(compileError("widget {\n statusBar: false\n Text { text: 1 } }").contains("unknown widget property"))
        // An app can hide it too, from owl 11.
        assertNotNull(Compiler.compile("app \"a\" {\n meta { owl: 11 }\n state s = true\n statusBar: !s\n Text { text: 1; onTap: s = !s } }").statusBar)
        assertTrue(compileError("app \"a\" {\n meta { owl: 10 }\n statusBar: false\n Text { text: 1 } }").contains("needs owl 11"))
        assertEquals(OwlKind.Face, OwlKind.of("// mine\nface \"x\" {"))
        // A face runs under the navigation bar, like an edge app; its screen { } doesn't.
        Compiler.compile("face {\n meta { owl: 10 }\n Spacer { height: screenInset } }")
        // Its components and lets too, which are compiled before the face's own nodes.
        Compiler.compile("face {\n meta { owl: 10 }\n let bottom = screenInset + 8\n component Foot() { Spacer { height: screenInset } }\n Column { Foot { }  Spacer { height: bottom } } }")
        assertTrue(compileError("face {\n meta { owl: 10 }\n Text { text: 1; onTap: screen() }\n screen { Text { text: screenInset } } }").contains("screenInset is for apps"))
        // A face has a screen's room: more nodes than a tile.
        val many = (1..600).joinToString("\n") { "Text { text: $it }" }
        Compiler.compile("face {\n meta { owl: 10 }\n Column {\n$many\n } }")
        assertTrue(compileError("widget {\n Column {\n$many\n } }").contains("more than 500 nodes"))
    }

    @Test fun textInPiecesAndGradientBackgroundsNeedOwl10() {
        Compiler.compile("widget {\n meta { owl: 10 }\n Text { spans: [\"15\", {text: \":\", color: accent, fontWeight: 300}, \"34\"]; fontWeight: 200; letterSpacing: -2 } }")
        Compiler.compile("face {\n meta { owl: 10 }\n background: linear(0, 0, 0, 1, [background, alpha(background, 0.3)])\n Column { background: true ? radial(0.5, 0.5, 0.5, [accent, surface]) : surface } }")
        assertTrue(compileError("widget {\n meta { owl: 9 }\n Text { spans: [\"a\"] } }").contains("Text.spans needs owl 10"))
        assertTrue(compileError("widget {\n meta { owl: 9 }\n Text { text: 1; fontWeight: 300 } }").contains("Text.fontWeight needs owl 10"))
        assertTrue(compileError("widget {\n meta { owl: 9 }\n Column { background: linear(0, 0, 1, 0, [red, blue]) } }").contains("a gradient background needs owl 10"))
        Compiler.compile("face {\n meta { owl: 10 }\n Column { frost: 24; background: alpha(surface, 0.4) } }")
        assertTrue(compileError("widget {\n meta { owl: 9 }\n Column { frost: 24 } }").contains("frost needs owl 10"))
        assertTrue(compileError("widget {\n meta { owl: 9 }\n component Card() { Column { } }\n Card { frost: 24 } }").contains("frost needs owl 10"))
        // A color background is as old as backgrounds.
        Compiler.compile("widget {\n Column { background: alpha(surface, 0.5) } }")
    }

    @Test fun fontsOfTheirOwnNeedOwl10() {
        val p =
            Compiler.compile(
                "face {\n meta { owl: 10 }\n asset display = base64\"AAEAAA==\"\n let base = \"https://x.test/fonts\"\n" +
                    " Text { text: 1; font: display }\n Text { text: 2; font: \"${'$'}{base}/Inter.ttf\" }\n Text { text: 3; font: \"~/.fonts/a.otf\" }\n" +
                    " Canvas { text([\"12\", {text: \":\", font: \"serif\"}], 10, 10, font: \"https://y.test/a.ttf\", fontWeight: 200, italic: true, letterSpacing: -1) } }",
            )
        assertEquals(listOf("font GET https://x.test/fonts/Inter.ttf", "font GET https://y.test/a.ttf"), p.commands)
        assertTrue(!p.needsLinux)
        // The words are as old as fonts, written as a word or as text.
        Compiler.compile("widget {\n Text { text: 1; font: serif }\n Text { text: 2; font: \"sans\" } }")
        // So is one picked at run time, as store widgets from before owl 10 do.
        Compiler.compile("widget {\n meta { owl: 8 }\n state f = \"mono\" persist\n Text { text: 1; font: f }\n Text { text: 2; font: f == \"mono\" ? \"mono\" : \"sans\" } }")
        assertTrue(compileError("widget {\n meta { owl: 9 }\n Text { text: 1; font: true ? \"mono\" : \"https://x.test/a.ttf\" } }").contains("a font of its own needs owl 10"))
        assertTrue(compileError("widget {\n meta { owl: 9 }\n asset f = base64\"AAEAAA==\"\n Text { text: 1; font: f } }").contains("a font of its own needs owl 10"))
        assertTrue(compileError("widget {\n meta { owl: 9 }\n Canvas { text(\"a\", 0, 0, fontWeight: 300) } }").contains("text(...) with font, fontWeight, italic or letterSpacing needs owl 10"))
        assertTrue(compileError("widget {\n meta { owl: 10 }\n Text { text: 1; font: \"http://x.test/a.ttf\" } }").contains("https only"))
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
        assertTrue(compileError("app {\n meta { owl: 8 }\n size: 2x2\n Text { text: 1 } }").contains("only takes title, icon, back, edge and statusBar"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n onTap: close()\n Text { text: 1 } }").contains("handlers go on the nodes"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n back: true\n Text { text: 1 } }").contains("back and onBack go together"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n onBack: close()\n Text { text: 1 } }").contains("back and onBack go together"))
        // What an app's title, back and onBack use counts toward the level it must declare.
        assertTrue(compileError("app {\n meta { owl: 8 }\n title: capitalize(\"x\")\n Text { text: 1 } }").contains("needs owl 9"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n back: true\n onBack: { if true { close() } }\n Text { text: 1 } }").contains("needs owl 9"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n screen { Text { text: 1 } } }").contains("is a screen already"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n Text { text: 1; onTap: screen() } }").contains("close() closes it"))
        assertTrue(compileError("app {\n meta { owl: 8 }\n }").contains("needs at least one node"))
        assertTrue(compileError("gadget {\n Text { text: 1 } }").contains("an owl file starts with"))
        assertEquals(OwlKind.App, OwlKind.of("// notes\n/* a\n b */ app \"x\" { Text { text: \"unterminated } }"))
        assertEquals(OwlKind.App, OwlKind.of("  app{"))
        assertEquals(OwlKind.Widget, OwlKind.of("sheet {"))
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

    /** Owl 13's phone controls: Android's panels, the other volume streams, and clearing, pressing and replying to notifications. */
    @Test fun phoneControlsOfOwl13() {
        val p =
            Compiler.compile(
                "widget {\n meta { owl: 13 }\n source n = android.notifications\n Text { text: 1; onTap: { settings(\"wifi\"); settings(\"output\"); volume(30, \"ring\"); notification(\"clear\"); " +
                    "notification(\"action\", n.items[0].key, 0); notification(\"reply\", n.items[0].key, \"on my way\") } } }",
            )
        assertTrue(p.commands.contains("reply to notifications as you: on my way"))
        assertTrue(p.commands.contains("press the buttons on notifications"))
        val risks = Review.risks(p.commands)
        assertTrue(risks.any { it.contains("sends messages as you") })
        assertTrue(risks.any { it.contains("presses buttons on other apps' notifications") })
        // Built while it runs, the text is what the reviewer can't see.
        val built = Compiler.compile("widget {\n meta { owl: 13 }\n state t = \"\"\n Input { onSubmit: notification(\"reply\", \"k\", value) } }")
        assertTrue(built.commands.contains("reply to notifications as you: text built from widget values"))
        // Each is owl 13, even on actions that are older.
        for (call in listOf("settings(\"wifi\")", "volume(30, \"ring\")", "notification(\"clear\")", "notification(\"reply\", \"k\", \"hi\")")) {
            compileError("widget {\n meta { owl: 12 }\n Text { text: 1; onTap: $call } }").let { assertTrue(it, it.contains("needs owl 13")) }
        }
        // A word not written out, with three values, is a word from owl 13 too.
        compileError("widget {\n meta { owl: 12 }\n state w = \"action\"\n Text { text: 1; onTap: notification(w, \"k\", 0) } }").let { assertTrue(it, it.contains("needs owl 13")) }
        // Each word takes its own number of values.
        compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: notification(\"clear\", \"k\") } }").let { assertTrue(it, it.contains("notification(\"clear\")")) }
        compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: notification(\"reply\", \"k\") } }").let { assertTrue(it, it.contains("notification(\"reply\", key, text)")) }
        compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: notification(\"open\") } }").let { assertTrue(it, it.contains("notification(\"open\", key)")) }
        assertTrue(compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: settings(\"hotspot\") } }").contains("settings takes"))
        assertTrue(compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: volume(30, \"call\") } }").contains("volume's stream is"))
    }

    /** drag: picks the one direction onDrag follows, and onDragEnd says how fast the finger let go (owl 13). */
    @Test fun dragsAlongOneDirection() {
        Compiler.compile("widget {\n meta { owl: 13 }\n state x = 0\n Box { offset: [x, 0]; drag: horizontal; onDrag: x = x + dx; onDragEnd: { if abs(vx) > 1000 { x = 0 } } } }")
        compileError("widget {\n meta { owl: 12 }\n state x = 0\n Box { drag: horizontal; onDrag: x = x + dx } }").let { assertTrue(it, it.contains("drag needs owl 13")) }
        compileError("widget {\n meta { owl: 13 }\n state x = 0\n Box { drag: sideways; onDrag: x = x + dx } }").let { assertTrue(it, it.contains("drag is horizontal or vertical")) }
        compileError("widget {\n meta { owl: 13 }\n Box { drag: vertical } }").let { assertTrue(it, it.contains("add onDrag")) }
        compileError("widget {\n meta { owl: 13 }\n state v = 0\n Box { onDragEnd: v = vx } }").let { assertTrue(it, it.contains("onDragEnd runs when an onDrag drag ends")) }
        compileError("widget {\n meta { owl: 13 }\n component C() { Box { } }\n C { drag: horizontal } }").let { assertTrue(it, it.contains("drag goes on the node inside C")) }
    }

    /** ripple: false turns off the press shown on a node that takes taps (owl 14). */
    @Test fun rippleOff() {
        Compiler.compile("widget {\n meta { owl: 14 }\n state n = 0\n Box { ripple: false; onTap: n = n + 1 } }")
        compileError("widget {\n meta { owl: 13 }\n state n = 0\n Box { ripple: false; onTap: n = n + 1 } }").let { assertTrue(it, it.contains("ripple needs owl 14")) }
        compileError("widget {\n meta { owl: 14 }\n Box { ripple: false } }").let { assertTrue(it, it.contains("add onTap")) }
        compileError("widget {\n meta { owl: 14 }\n component C() { Box { } }\n C { ripple: false } }").let { assertTrue(it, it.contains("ripple goes on the node inside C")) }
    }

    /** The SIM, Do Not Disturb switched, notifications snoozed, and offsets that ease back (owl 13). */
    @Test fun simDndSnoozeAndSpringBack() {
        Compiler.compile(
            "widget {\n meta { owl: 13 }\n source s = android.sim\n state x = 0\n" +
                " Box { offset: [x, 0]; animate offset: spring(0.8, 400); drag: horizontal; onDrag: x = x + dx; onDragEnd: x = 0\n" +
                "  Text { text: s.carrier ?? \"no sim\"; onTap: { dnd(\"toggle\"); dnd(true); dnd(\"alarms\"); dnd(\"priority\"); dnd(\"none\"); dnd(\"all\"); torch(40); torch(\"60\"); notification(\"snoozeAll\", 3600); notification(\"snooze\", \"k\", 600); notification(\"unsnooze\", \"k\"); notification(\"unsnoozeAll\") } } } }",
        )
        for (old in listOf("source s = android.sim\n Text { text: 1 }", "Text { text: 1; onTap: dnd(\"on\") }", "Text { text: 1; onTap: notification(\"snoozeAll\", 60) }", "Text { text: 1; onTap: notification(\"snooze\", \"k\", 60) }", "Text { text: 1; onTap: notification(\"unsnoozeAll\") }", "Box { offset: [1, 0]; animate offset: spring() }", "Text { text: 1; onTap: torch(40) }")) {
            compileError("widget {\n meta { owl: 12 }\n $old }").let { assertTrue(it, it.contains("needs owl 13")) }
        }
        assertTrue(compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: dnd(\"silence\") } }").contains("dnd takes"))
        // A forgotten length is caught, not a snooze that silently does nothing.
        compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: notification(\"snooze\", \"k\") } }").let { assertTrue(it, it.contains("notification(\"snooze\", key, seconds)")) }
        compileError("widget {\n meta { owl: 13 }\n Text { text: 1; onTap: notification(\"snoozeAll\") } }").let { assertTrue(it, it.contains("notification(\"snoozeAll\", seconds)")) }
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
        // More ways than are listed, given up on as they add up: 2^30 strings would fill memory first.
        val many = "${'$'}{n > 1 ? \"a\" : \"b\"}".repeat(30)
        assertEquals(listOf("http GET (a URL built from widget values)"), commands("\"https://a.b/$many\""))
    }

    @Test fun urlValuesNotThroughEncAreWarnedAboutWithTheFix() {
        fun warnings(url: String) = Compiler.compile("widget {\n meta { owl: 8 }\n state v = \"x\"\n source s = http($url) json\n Text { text: v } }").warnings
        assertEquals(listOf("4:35: a value in this URL isn't put through enc(), so the store's review can't show where it goes: write \${enc(lower(v))} (a number can go through int(n) or fixed(n, 4) instead; a path with / in it can't, so write its fixed parts out and enc() only the values between them)"), warnings("\"https://a.b/q=${'$'}{lower(v)}\""))
        assertTrue(warnings("\"https://a.b/q=\" + v").single().contains("write enc(v)"))
        assertTrue(warnings("\"https://a.b/${'$'}{sh(v)}\"").single().contains("enc(...) instead of sh(...)"))
        assertTrue(warnings("\"https://${'$'}{enc(v)}/x\"").single().contains("the server in this URL comes from a value"))
        assertEquals(emptyList<String>(), warnings("\"https://a.b/${'$'}{enc(v)}\""))
        // A query right after the host, with no path, is a fixed server too.
        assertEquals(emptyList<String>(), warnings("\"https://a.b?q=${'$'}{enc(v)}\""))
        // Too many ways to list isn't a server from a value.
        val many = "${'$'}{v == \"\" ? \"a\" : \"b\"}".repeat(4)
        assertTrue(warnings("\"https://a.b/$many\"").single().contains("more than 8 ways"))
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


    private val owl9 = "meta { owl: 9 }"

    private fun <T> inLocale(
        locale: Locale,
        f: () -> T,
    ): T {
        val was = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return f()
        } finally {
            Locale.setDefault(was)
        }
    }

    private fun widgetError(expr: String): String =
        try {
            text(expr, owl9)
            fail("expected an error")
            ""
        } catch (e: WidgetError) {
            e.message!!
        }

    @Test fun parseTimeReadsIsoDates() {
        assertEquals("1792231440", text("parseTime(\"2026-10-17T10:04:00Z\")", owl9))
        assertEquals("1792231440", text("parseTime(\"2026-10-17T12:04:00+02:00\")", owl9))
        assertEquals("1792231440", text("parseTime(\"2026-10-17T12:04+0200\")", owl9))
        assertEquals("1792231440", text("parseTime(\"2026-10-17 05:04:00-05\")", owl9))
        assertEquals("40.25", text("parseTime(\"2026-10-17T10:04:00.250Z\") % 100", owl9))
        assertEquals("1792231440", text("parseTime(\"20261017T100400Z\")", owl9))
        // Without a zone: in the one given, or the phone's.
        assertEquals("1792195200", text("parseTime(\"2026-10-17\", \"\", \"UTC\")", owl9))
        assertEquals("1792188000", text("parseTime(\"2026-10-17\", null, \"Europe/Berlin\")", owl9))
        assertEquals("1792195200", text("parseTime(\"20261017\", \"\", \"UTC\")", owl9))
        assertEquals("null", text("parseTime(\"tomorrow\")", owl9))
        assertEquals("null", text("parseTime(\"2026-13-40\")", owl9))
        assertEquals("null", text("parseTime(null)", owl9))
    }

    @Test fun parseTimeReadsFeedDates() {
        assertEquals("1792231440", text("parseTime(\"Sat, 17 Oct 2026 10:04:00 GMT\")", owl9))
        assertEquals("1792231440", text("parseTime(\"Sat, 17 Oct 2026 12:04:00 +0200\")", owl9))
        assertEquals("1792231440", text("parseTime(\"Sat, 17 Oct 2026 03:04:00 PDT\")", owl9))
        assertEquals("1792231440", text("parseTime(\"17 Oct 2026 10:04 UT\")", owl9))
    }

    @Test fun parseTimeTakesAPattern() {
        // English month names, whatever the phone's language: dates come from APIs.
        inLocale(Locale.GERMAN) {
            assertEquals("1792231440", text("parseTime(\"Oct 17, 2026 10:04 AM\", \"MMM d, yyyy h:mm a\", \"UTC\")", owl9))
            assertEquals("1792231440", text("parseTime(\"oct 17, 2026 10:04 am\", \"MMM d, yyyy h:mm a\", \"UTC\")", owl9))
        }
        assertEquals("1792231440", text("parseTime(\"17.10.2026 12:04 +02:00\", \"dd.MM.yyyy HH:mm XXX\")", owl9))
        assertEquals("1792195200", text("parseTime(\"17.10.2026\", \"dd.MM.yyyy\", \"UTC\")", owl9))
        // A time without a date is today's.
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay() * 86400
        assertEquals("${today + 10 * 3600 + 4 * 60}", text("parseTime(\"10:04\", \"HH:mm\", \"UTC\")", owl9))
        assertEquals("null", text("parseTime(\"10h04\", \"HH:mm\", \"UTC\")", owl9))
        assertTrue(widgetError("parseTime(\"x\", \"HH:mm{\")").contains("bad time pattern"))
    }

    @Test fun fmtTimeTakesAZoneAndALanguage() {
        assertEquals("05:30", text("fmtTime(0, \"HH:mm\", \"Asia/Kolkata\")", owl9))
        assertEquals("02:00", text("fmtTime(0, \"HH:mm\", \"+02:00\")", owl9))
        assertEquals("Sonntag", text("fmtTime(${3 * 86400 + 43200}, \"EEEE\", \"UTC\", \"de\")", owl9))
        inLocale(Locale.GERMAN) { assertEquals("Sunday", text("fmtTime(${3 * 86400 + 43200}, \"EEEE\", null, \"en\")", owl9)) }
        // A duration under a day, as a clock.
        assertEquals("3:07", text("fmtTime(187, \"m:ss\", \"UTC\")", owl9))
        assertTrue(compileError("widget {\n $owl9\n Text { text: fmtTime(0, \"HH\", \"Mars/Olympus\") } }").contains("unknown time zone 'Mars/Olympus'"))
        assertTrue(widgetError("fmtTime(0, \"HH\", str(\"Mars/Olympus\") + tileWidth)").contains("unknown time zone"))
        assertTrue(compileError("widget {\n meta { owl: 8 }\n Text { text: fmtTime(0, \"HH\", \"UTC\") } }").contains("needs owl 9"))
        // Two values still need no owl 9.
        assertEquals("00", text("fmtTime(0, \"mm\")", "meta { owl: 8 }"))
    }

    @Test fun fmtAgoIsShort() {
        assertEquals("now", text("fmtAgo(0, 59)", owl9))
        assertEquals("5m", text("fmtAgo(0, 300)", owl9))
        assertEquals("2h", text("fmtAgo(0, 7300)", owl9))
        assertEquals("3d", text("fmtAgo(0, 3 * 86400)", owl9))
        assertEquals("2w", text("fmtAgo(0, 20 * 86400)", owl9))
        assertEquals("1y", text("fmtAgo(0, 400 * 86400)", owl9))
        assertEquals("in 10m", text("fmtAgo(600, 0)", owl9))
        assertEquals("null", text("fmtAgo(null, 5)", owl9))
        assertEquals("null", text("fmtAgo(5, null)", owl9))
    }

    @Test fun fmtNumGroupsAndCompacts() {
        inLocale(Locale.ENGLISH) {
            assertEquals("1,234,568", text("fmtNum(1234567.891)", owl9))
            assertEquals("1,234.50", text("fmtNum(1234.5, 2)", owl9))
            assertEquals("-12", text("fmtNum(\"-12.4\")", owl9))
            assertEquals("0", text("fmtNum(-0.2)", owl9))
            assertEquals("950", text("fmtNum(950, {compact: true})", owl9))
            assertEquals("1.5k", text("fmtNum(1500, {compact: true})", owl9))
            assertEquals("2k", text("fmtNum(2000, {compact: true})", owl9))
            assertEquals("12k", text("fmtNum(12345, {compact: true})", owl9))
            assertEquals("1M", text("fmtNum(999999, {compact: true})", owl9))
            assertEquals("2.5B", text("fmtNum(2500000000, {compact: true})", owl9))
            assertEquals("-1.5k", text("fmtNum(-1500, {compact: true})", owl9))
            assertEquals("1.23k", text("fmtNum(1234, {compact: true, digits: 2})", owl9))
            assertEquals("4.5", text("fmtNum(4.5, {compact: true})", owl9))
            assertEquals("+3.2", text("fmtNum(3.2, {digits: 1, sign: true})", owl9))
            assertEquals("-3.2", text("fmtNum(-3.2, {digits: 1, sign: true})", owl9))
            assertEquals("0.0", text("fmtNum(-0.01, {digits: 1, sign: true})", owl9))
            assertEquals("+1.5k", text("fmtNum(1500, {compact: true, sign: true})", owl9))
            assertEquals("null", text("fmtNum(null)", owl9))
            assertTrue(widgetError("fmtNum(1, \"lots\")").contains("fmtNum takes a number of digits"))
        }
        inLocale(Locale.GERMAN) {
            assertEquals("1.234,50", text("fmtNum(1234.5, 2)", owl9))
            assertEquals("1,5k", text("fmtNum(1500, {compact: true})", owl9))
        }
        // Its own zero goes too: not ١٫٠k.
        inLocale(Locale.forLanguageTag("ar-EG")) {
            assertEquals("١k", text("fmtNum(1000, {compact: true})", owl9))
            assertEquals("١٫٥k", text("fmtNum(1500, {compact: true})", owl9))
        }
    }

    @Test fun hashAndSeededShuffleMatchTheQuickshellRuntime() {
        // The same numbers as runtimes/quickshell's JavaScript, which copies these two functions.
        assertEquals("0.10352621669881046", text("hash(\"a\")", owl9))
        assertEquals("0.47707845945842564", text("hash(7)", owl9))
        assertEquals("0.4710701135918498", text("hash(\"seed\", 3)", owl9))
        assertEquals("0.1301283857319504", text("hash(\"ab\", \"c\")", owl9))
        assertEquals("0.9770175418816507", text("hash(\"a\", \"bc\")", owl9))
        // Numbers, lists and maps hash as they read.
        assertEquals("0.20254519511945546", text("hash(1792231445.5)", owl9))
        assertEquals("0.31604075129143894", text("hash([1, 2.5])", owl9))
        assertEquals("0.5861964426003397", text("hash({a: 1})", owl9))
        assertEquals("[9, 3, 4, 1, 6, 8, 7, 5, 10, 2]", text("shuffle(range(1, 11), 42)", owl9))
        assertEquals("[b, c, d, a]", text("shuffle([\"a\", \"b\", \"c\", \"d\"], \"x\")", owl9))
        assertTrue(compileError("widget {\n meta { owl: 8 }\n Text { text: hash(1) } }").contains("hash needs owl 9"))
    }

    @Test fun randomOnlyInHandlersAndFirstValues() {
        val p = Compiler.compile("widget {\n $owl9\n state roll = random(1, 6)\n state order = []\n onTap: { roll = random(1, 6); order = shuffle(range(5)) }\n Text { text: roll } }")
        val host = TestHost(p)
        val seen = HashSet<Value>()
        repeat(200) {
            p.onTap!!.run(Env.root(host), emptyList())
            seen += host.slots[0]
            assertEquals((0 until 5).map { Num(it.toDouble()) }, (host.slots[1] as Lst).items.sortedBy { (it as Num).v })
        }
        assertEquals((1..6).map { Num(it.toDouble()) }.toSet(), seen)
        val e = compileError("widget {\n $owl9\n let r = random()\n Text { text: r } }")
        assertTrue(e, e.contains("random() gives a new number each time it's read"))
        assertTrue(compileError("widget {\n $owl9\n Text { text: shuffle([1, 2]) } }").contains("give it a seed"))
        assertTrue(compileError("widget {\n meta { owl: 8 }\n state r = 0\n onTap: r = random()\n Text { text: r } }").contains("random needs owl 9"))
    }

    @Test fun randomRanges() {
        val p = Compiler.compile("widget {\n $owl9\n state a = 0\n state b = 0\n state c = 0\n onTap: { a = random(); b = random(3); c = random(6, 4) }\n Text { text: a } }")
        val host = TestHost(p)
        repeat(100) {
            p.onTap!!.run(Env.root(host), emptyList())
            val a = (host.slots[0] as Num).v
            assertTrue(a >= 0 && a < 1)
            assertTrue((host.slots[1] as Num).v in setOf(0.0, 1.0, 2.0))
            assertTrue((host.slots[2] as Num).v in setOf(4.0, 5.0, 6.0))
        }
    }


    @Test fun setAndRemoveCopyListsAndMaps() {
        assertEquals("[1, 9, 3]", text("set([1, 2, 3], 1, 9)", owl9))
        assertEquals("[1, 2, 9]", text("set([1, 2, 3], -1, 9)", owl9))
        assertEquals("[1, 2, 3]", text("set([1, 2, 3], 5, 9)", owl9))
        assertEquals("{a: 1, b: 3}", text("set({a: 1, b: 2}, \"b\", 3)", owl9))
        assertEquals("{a: 1, c: 3}", text("set({a: 1}, \"c\", 3)", owl9))
        assertEquals("{a: 1}", text("set(null, \"a\", 1)", owl9))
        assertEquals("[1, 3]", text("remove([1, 2, 3], 1)", owl9))
        assertEquals("[1, 2]", text("remove([1, 2, 3], -1)", owl9))
        assertEquals("[1, 2, 3]", text("remove([1, 2, 3], \"x\")", owl9))
        assertEquals("{b: 2}", text("remove({a: 1, b: 2}, \"a\")", owl9))
        // In a handler, the edit a widget used to spell take(i) + [v] + drop(i + 1).
        val p = Compiler.compile("widget {\n $owl9\n state xs = [1, 2, 3]\n onTap: xs = set(xs, 0, xs[0] + 10)\n Text { text: xs } }")
        val host = TestHost(p)
        p.onTap!!.run(Env.root(host), emptyList())
        assertEquals("[11, 2, 3]", host.slots[0].toString())
    }

    @Test fun mergeReduceScanGroupBy() {
        assertEquals("{a: 1, b: 3, c: 4}", text("merge({a: 1, b: 2}, {b: 3}, null, {c: 4})", owl9))
        assertEquals("{a: 1, b: 3}", text("{a: 1, b: 2}.merge({b: 3})", owl9))
        assertEquals("24", text("reduce([2, 3, 4], (p, x) => p * x, 1)", owl9))
        assertEquals("3", text("[5, 9, 2].reduce((best, x, i) => x > 8 ? i + 2 : best, 0)", owl9))
        assertEquals("start", text("reduce([], (a, x) => a + x, \"start\")", owl9))
        assertEquals("[1, 3, 6, 10]", text("scan([1, 2, 3, 4], (sum, x) => sum + x, 0)", owl9))
        assertEquals("{odd: [1, 3], even: [2]}", text("groupBy([1, 2, 3], x => x % 2 == 1 ? \"odd\" : \"even\")", owl9))
        assertEquals("[]", text("keys(groupBy([], x => x))", owl9))
    }

    @Test fun toJsonCapitalizeFlattenAndInverseTrig() {
        assertEquals("{\"a\":[1,\"x\",null,true],\"b\":{\"c\":0.5}}", text("toJson({a: [1, \"x\", null, true], b: {c: 0.5}})", owl9))
        assertEquals("\"say \\\"hi\\\"\"", text("toJson(\"say \\\"hi\\\"\")", owl9))
        // A constant fails as the widget compiles, one read at run time when it's evaluated.
        assertTrue(compileError("widget {\n $owl9\n Text { text: toJson([accent]) } }").contains("toJson takes numbers"))
        assertTrue(widgetError("toJson([tileWidth, accent])").contains("toJson takes numbers"))
        assertEquals("Hello world", text("capitalize(\"hello world\")", owl9))
        assertEquals("", text("capitalize(\"\")", owl9))
        assertEquals("null", text("capitalize(null)", owl9))
        assertEquals("[1, 2, [3, [4]]]", text("flatten([1, [2, [3, [4]]]])", owl9))
        assertEquals("[1, 2, 3, [4]]", text("flatten([1, [2, [3, [4]]]], 2)", owl9))
        assertEquals("[1, 2, 3, 4]", text("flatten([1, [2, [3, [4]]]], 10)", owl9))
        assertTrue(compileError("widget {\n meta { owl: 8 }\n Text { text: flatten([], 2) } }").contains("flatten with a depth needs owl 9"))
        assertEquals("90", text("round(deg(asin(1)))", owl9))
        assertEquals("60", text("round(deg(acos(0.5)))", owl9))
        assertTrue(compileError("widget {\n meta { owl: 8 }\n Text { text: asin(1) } }").contains("asin needs owl 9"))
    }

    @Test fun onFirstRunsForTheFirstValueToo() {
        val p = Compiler.compile("widget {\n $owl9\n state n = 1\n state saved = 0\n on n first { saved = value }\n on n { saved = old }\n Text { text: n } }")
        assertEquals(listOf(true, false), p.triggers.map { it.first })
        // `first` is still a function everywhere else.
        assertEquals("1", text("first([1, 2])", owl9))
        val e = compileError("widget {\n meta { owl: 8 }\n state n = 1\n on n first { n = 2 }\n Text { text: n } }")
        assertTrue(e, e.contains("on … first needs owl 9"))
        assertTrue(compileError("widget {\n $owl9\n state n = 1\n on n later { n = 2 }\n Text { text: n } }").contains("expected '{'"))
    }


    /** Compiles an owl 9 widget with states a, b and c (0) and the handler [h], taps it, and gives the states and actions. */
    private fun tap(
        h: String,
        decls: String = "",
    ): String {
        val p = Compiler.compile("widget {\n $owl9\n state a = 0\n state b = 0\n state c = 0\n $decls\n onTap: $h\n Text { text: a } }")
        val host = TestHost(p)
        p.onTap!!.run(Env.root(host), listOf(Num(5.0), Num(7.0)))
        return "a=${host.slots[0]} b=${host.slots[1]} c=${host.slots[2]}" + host.actions.joinToString("") { "; $it" }
    }

    @Test fun ifInHandlersChooses() {
        assertEquals("a=1 b=0 c=0", tap("{ if x < 6 { a = 1 } else { b = 1 } }"))
        assertEquals("a=0 b=1 c=0", tap("{ if x > 6 { a = 1 } else { b = 1 } }"))
        assertEquals("a=0 b=0 c=1", tap("{ if x > 6 { a = 1 } else if y > 6 { c = 1 } else { b = 1 } }"))
        assertEquals("a=0 b=0 c=0", tap("{ if x > 6 { a = 1 } else if y > 9 { c = 1 } }"))
        // A handler that is just an if, and ifs inside ifs.
        assertEquals("a=2 b=0 c=0", tap("if x == 5 { if y == 7 { a = 2 } else { a = 3 } }"))
        // Skipped actions don't run: no `run("true")` to stand in for nothing.
        assertEquals("a=0 b=0 c=0; notify on", tap("{ if a == 1 { run(\"paplay x.wav\") }; if a == 0 { notify(\"on\") } }"))
        assertEquals(listOf("paplay x.wav"), Compiler.compile("widget {\n $owl9\n state s = false\n onTap: if s { run(\"paplay x.wav\") }\n Text { text: s } }").commands)
    }

    @Test fun letInHandlersNamesAValue() {
        // A let keeps the value it had: assignments after it don't change it.
        assertEquals("a=12 b=0 c=12", tap("{ let sum = x + y; a = sum; a = 100; c = sum; a = sum }"))
        assertEquals("a=1 b=5 c=0", tap("{ let old = a; a = 1; b = old + x }"))
        // Visible after it in its block and the blocks inside, not outside.
        assertEquals("a=2 b=0 c=0", tap("{ if x == 5 { let k = 2; if y == 7 { a = k } } }"))
        assertTrue(compileError("widget {\n $owl9\n state a = 0\n onTap: { if true { let k = 2 }; a = k }\n Text { text: a } }").contains("unknown name 'k'"))
        assertTrue(compileError("widget {\n $owl9\n state a = 0\n onTap: { a = k; let k = 2 }\n Text { text: a } }").contains("unknown name 'k'"))
        // Lambdas see it too.
        assertEquals("a=[6, 7] b=0 c=0", tap("{ let base = x; a = [1, 2].map(i => i + base) }"))
        // A state called let still assigns.
        assertEquals("a=0 b=0 c=0", tap("{ let = 3 }", "state let = 0"))
    }

    @Test fun handlersWithIfAndLetInTriggers() {
        val p = Compiler.compile("widget {\n $owl9\n state n = 3\n state out = \"\"\n on n { let d = value - old; if d > 0 { out = \"up ${'$'}{d}\" } else { out = \"down\" } }\n Text { text: n } }")
        val host = TestHost(p)
        p.triggers.single().action.run(Env.root(host), listOf(Num(5.0), Num(3.0)))
        assertEquals(Str("up 2"), host.slots[1])
    }

    @Test fun ifAndLetInHandlersSayWhatsWrong() {
        assertTrue(compileError("widget {\n meta { owl: 8 }\n state a = 0\n onTap: if a { a = 1 }\n Text { text: a } }").contains("if in a handler needs owl 9"))
        assertTrue(compileError("widget {\n meta { owl: 8 }\n state a = 0\n onTap: { let b = 1; a = b }\n Text { text: a } }").contains("let in a handler needs owl 9"))
        assertTrue(compileError("widget {\n $owl9\n state a = 0\n onTap: { let a = 1 }\n Text { text: a } }").contains("'a' is already a name here"))
        assertTrue(compileError("widget {\n $owl9\n state a = 0\n onTap: { let x = 1 }\n Text { text: a } }").contains("'x' is already a name here"))
        assertTrue(compileError("widget {\n $owl9\n state a = 0\n onTap: { let k = 1; k = 2 }\n Text { text: a } }").contains("'k' is a let or a value given to the handler"))
        assertTrue(compileError("widget {\n $owl9\n state a = 0\n onTap: if a a = 1\n Text { text: a } }").contains("expected '{'"))
    }

    /** Older widgets assign a state that shares its name with a handler value, a loop variable or a parameter. */
    @Test fun assigningAStateNamedLikeALocalStillWorks() {
        val p =
            Compiler.compile(
                "widget {\n meta { owl: 4 }\n state value = 50\n state x = 0\n" +
                    " component Pick(x) { Text { text: x; onTap: x = 1 } }\n" +
                    " Column {\n Slider { value: value; onChange: value = value }\n for x in [1, 2] { Text { text: x; onTap: x = 5 } }\n Pick { x: 3 } } }",
            )
        val host = TestHost(p)
        val slider = (p.root.single() as WNode.Element).children.first() as WNode.Element
        slider.handlers["onChange"]!!.run(Env.root(host), listOf(Num(70.0)))
        assertEquals(Num(70.0), host.slots[0])
    }

    /** A widget with strings, compiled for [language], showing [expr]. */
    private fun texts(
        expr: String,
        language: String,
        decls: String = "",
    ): String {
        val src = "widget \"t\" {\n meta { owl: 16 }\n strings {\n  en { feels: \"feels\"; today: \"today\"; items: n => n == 1 ? \"1 item\" : \"${'$'}{n} items\" }\n  id { feels: \"terasa\"; items: n => \"${'$'}{n} item\" }\n }\n$decls\nText { text: $expr }\n}"
        val p = Compiler.compile(src, language)
        return ((p.root.single() as WNode.Element).props["text"]!!(Env.root(TestHost(p)))).toString()
    }

    @Test fun stringsPickThePhonesLanguageAndFallBackToEnglish() {
        assertEquals("terasa", texts("str.feels", "id"))
        assertEquals("feels", texts("str.feels", "en"))
        assertEquals("feels", texts("str.feels", "de"))
        // id has no today: en's shows.
        assertEquals("today", texts("str.today", "id"))
        assertEquals("1 item", texts("str.items(1)", "en"))
        assertEquals("3 items", texts("str.items(n)", "en", "state n = 3"))
        assertEquals("3 item", texts("str.items(n)", "id", "state n = 3"))
        assertEquals("id", texts("lang()", "id"))
        // A built-in on the whole map, for a text picked by a value.
        assertEquals("terasa", texts("get(str, k)", "id", "state k = \"feels\""))
    }

    @Test fun stringsCatchWhatDoesntMatchEnglish() {
        fun error(strings: String, use: String = "str.a") = compileError("widget \"t\" {\n meta { owl: 16 }\n strings { $strings }\n Text { text: $use }\n}")
        assertTrue(error("en { a: \"a\" }", "str.b").contains("strings has no b"))
        assertTrue(error("id { a: \"a\" }").contains("strings needs en { }"))
        assertTrue(error("en { a: \"a\" } id { a: \"a\"; b: \"b\" }").contains("id has b, which en doesn't"))
        assertTrue(error("en { a: n => \"${'$'}{n}\" }").contains("str.a takes 1 value"))
        assertTrue(error("en { a: \"a\" }", "str.a(1)").contains("str.a is text"))
        assertTrue(error("en { a: n => \"${'$'}{n}\" } id { a: \"a\" }").contains("a takes 1 value in en, so it does in id too"))
        assertTrue(error("EN { a: \"a\" }").contains("its code in lowercase"))
        assertTrue(compileError("widget \"t\" {\n strings { en { a: \"a\" } }\n Text { text: str.a }\n}").contains("owl 16"))
        val warned = Compiler.compile("widget \"t\" {\n meta { owl: 16 }\n strings { en { a: \"a\"; b: \"b\" } id { a: \"x\" } }\n Text { text: str.a }\n}", "en").warnings
        assertEquals(listOf("3:2: id has no b, so those show in English"), warned)
    }
}
