package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class SecretsTest {
    @get:Rule val dir = TemporaryFolder()

    private fun widget(
        host: String = "pc.test",
        token: String = "state token = null secret",
    ) = Compiler.compile(
        "widget \"pc\" {\n meta { owl: 12 }\n $token\n state host = \"\" persist\n" +
            " source s = http(\"https://$host/a\", {Authorization: \"Bearer ${'$'}{token}\"})\n Text { text: host } }",
    )

    private val program = widget()

    /** Services that only seal, by reversing the bytes, so the file doesn't hold the token as it is. */
    private val services =
        Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, method, args ->
            when (method.name) {
                "seal", "unseal" -> (args[0] as ByteArray).reversedArray()
                else -> null
            }
        } as Services

    private val saved get() = File(dir.root, "pc.owl.json")

    private val sealed get() = File(dir.root, "pc.owl.secret")

    private fun instance(
        p: Program = program,
        errors: MutableList<String> = ArrayList(),
    ) = WidgetInstance(p, services, { errors += it }, saved, sealed)

    private fun WidgetInstance.token() = read(program.globals.indexOfFirst { it.name == "token" })

    /** What the user typed into the widget's Input. */
    private fun WidgetInstance.typeToken(t: String) = write(program.globals.indexOfFirst { it.name == "token" }, Str(t))

    /** A secret survives a restart in its own sealed file, never in the persisted values; unset, the file goes. */
    @Test fun keptSealedApartFromPersistedValues() {
        val a = instance()
        a.typeToken("s3cr3t")
        assertEquals(null, a.setState("host", Str("pc.lan")))
        a.stop()
        assertTrue(sealed.isFile)
        assertFalse(String(sealed.readBytes()).contains("s3cr3t"))
        assertFalse(saved.readText().contains("s3cr3t"))

        val b = instance()
        assertEquals(Str("s3cr3t"), b.token())
        b.write(program.globals.indexOfFirst { it.name == "token" }, Null)
        b.stop()
        assertFalse(sealed.exists())
    }

    /** A file changed to send the secret to another server starts without it, after a restart or a reload. */
    @Test fun lostWhenItWouldGoElsewhere() {
        val a = instance()
        a.typeToken("s3cr3t")
        a.stop()
        assertEquals(Null, instance(widget(host = "evil.test")).token())
        assertEquals(Str("s3cr3t"), instance().token())

        val running = instance()
        for (edit in listOf(widget(host = "evil.test"), widget(token = "state token = null"))) {
            val reloaded = instance(edit)
            reloaded.adopt(running)
            assertEquals(Null, reloaded.token())
        }
        instance().also { it.adopt(running) }.let { assertEquals(Str("s3cr3t"), it.token()) }

        // Nor does a plain state, set from outside, become the secret when the file goes back.
        val plain = instance(widget(token = "state token = null"))
        assertEquals(null, plain.setState("token", Str("theirs")))
        instance().also { it.adopt(plain) }.let { assertEquals(Str("s3cr3t"), it.token()) }
    }

    /** One that can't be read, like another install's, is kept until a new one is typed in, and the widget says why it asks. */
    @Test fun keptWhenItCantBeRead() {
        sealed.writeBytes(byteArrayOf(1, 2, 3))
        val errors = ArrayList<String>()
        val a = instance(errors = errors)
        assertEquals(Null, a.token())
        a.stop()
        assertTrue(sealed.isFile)
        assertTrue(errors.toString(), errors.single().contains("couldn't read the secrets"))
        instance().also { it.typeToken("s3cr3t") }.stop()
        assertEquals(Str("s3cr3t"), instance().token())
    }

    /** owl-send can't swap in a token, and an error never quotes one, as it is or URL-encoded. */
    @Test fun notSetFromOutsideNorReported() {
        val errors = ArrayList<String>()
        val a = instance(errors = errors)
        assertTrue(a.setState("token", Str("theirs"))!!.contains("is a secret"))
        a.typeToken("s3cr3t")
        a.report("unknown time zone 's3cr3t'")
        // As enc(token) put it in a URL, too.
        a.typeToken("a b/c")
        a.report("http GET https://pc.test/?k=a%20b%2Fc failed")
        assertEquals(listOf("unknown time zone '•••'", "http GET https://pc.test/?k=••• failed"), errors)
    }
}
