package dev.omalauncher.wayland

import android.os.ParcelFileDescriptor
import android.content.ClipData
import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TEXT = "text/plain;charset=utf-8"
private const val IMAGE = "image/png"

private data class Request(val sourceId: Int, val mime: String?, val fd: Int)

private class Fixture {
    val server = WaylandServer()
    val clipboard = FakeClipboard()
    val client = Client(server)
    private val data = mutableMapOf<Int, ByteArray>()
    private var nextId = 1
    val requests = mutableListOf<Request>()
    var delayed: Request? = null
    var delayNext = false
    init {
        server.selection.system = clipboard
        server.input.keyboardFocus = Surface(client)
        client.register(DataDevice(client, 900, 3))
        client.onSend = { event ->
            val buffer = ByteBuffer.wrap(event.bytes, 0, event.size).order(ByteOrder.LITTLE_ENDIAN)
            val objectId = buffer.getInt(0)
            val opcode = buffer.getInt(4) and 65535
            if (client.resources[objectId] is DataSource && opcode == 1) {
                val args = Args(buffer, ArrayDeque())
                args.reset(8, event.size)
                val request = Request(objectId, args.string(), event.fd)
                requests += request
                if (delayNext) {
                    delayNext = false
                    delayed = request
                } else {
                    respond(request)
                }
            }
        }
    }
    fun source(text: String, mimes: List<String> = listOf(TEXT)): DataSource = source(text.toByteArray(), mimes)
    fun source(bytes: ByteArray, mimes: List<String>): DataSource {
        val source = client.register(DataSource(client, nextId++, 3))
        source.mimes.addAll(mimes)
        data[source.id] = bytes
        return source
    }
    fun set(source: DataSource, mirrorExpected: Boolean = true) {
        val previous = clipboard.writes
        synchronized(server) { server.selection.set(source) }
        if (mirrorExpected) await("Linux copy mirrored to Android") { clipboard.writes > previous }
    }
    fun offer(source: DataSource?): DataOffer = client.register(DataOffer(client, nextId++, 3, source, server.selection))
    fun respond(request: Request) {
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(request.fd)).use {
            it.write(data.getValue(request.sourceId))
        }
    }
    fun receive(offer: DataOffer, mime: String = TEXT): ByteArray {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val event = Event(offer.id, 1).string(mime)
        val args = Args(ByteBuffer.wrap(event.bytes).order(ByteOrder.LITTLE_ENDIAN), ArrayDeque(listOf(write.detachFd())))
        args.reset(8, event.size)
        try {
            synchronized(server) { offer.request(1, args) }
        } catch (error: Throwable) {
            ParcelFileDescriptor.closeFd(read.fd)
            ParcelFileDescriptor.closeFd(write.fd)
            throw error
        }
        val result = FutureTask { ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readBytes() } }
        thread(isDaemon = true, name = "test-paste-read") { result.run() }
        return result.get(3, TimeUnit.SECONDS)
    }
    fun receiveText(offer: DataOffer): String = String(receive(offer), Charsets.UTF_8)
}

private fun await(label: String, predicate: () -> Boolean) {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (!predicate()) {
        check(System.nanoTime() < end) { "timed out: $label" }
        Thread.sleep(2)
    }
}

private fun equal(expected: Any?, actual: Any?) {
    check(expected == actual) { "expected <$expected>, got <$actual>" }
}

private fun androidTest(test: (Context, AndroidClipboard) -> Unit) {
    val directory = Files.createTempDirectory("clipboard-regression-").toFile()
    try {
        val context = Context()
        val clipboard = AndroidClipboard(context, GuestImages(context, directory) { it.path }) {}
        test(context, clipboard)
    } finally {
        directory.deleteRecursively()
    }
}

private fun imageClip(context: Context, name: String, timestamp: Long, bytes: ByteArray): ClipData {
    val uri = Uri.parse("content://test/$name")
    context.contentResolver.types[uri] = "image/png"
    context.contentResolver.bytes[uri] = bytes
    return ClipData(ClipData.Item(uri = uri)).also { it.description.timestamp = timestamp }
}

private val cases = linkedMapOf<String, () -> Unit>(
    "Android-only offer reads each current value over 25 pastes" to {
        val f = Fixture()
        val offer = f.offer(null)
        repeat(25) { n ->
            val text = "Android copy $n — 你好 🦉\nsecond line\n"
            f.clipboard.external(text)
            equal(text, f.receiveText(offer))
        }
    },
    "ChatGPT command paste then terminal output copy then new command paste uses current Android text" to {
        val f = Fixture()
        val focusedTerminal = f.server.input.keyboardFocus
        val firstCommand = "printf 'first command output\\n'"
        val terminalOutput = "first command output\n"
        val nextCommand = "printf 'different command output\\n'"

        // ChatGPT Copy -> terminal Paste, before Linux owns the clipboard.
        f.clipboard.external(firstCommand)
        synchronized(f.server) { f.server.selection.offer(f.client) }
        equal(firstCommand, f.receiveText(f.client.all(DataOffer::class.java).last()))

        // Execution is represented by fixture output; no command or real terminal is run.
        // The terminal's Copy creates a Linux source and mirrors its output to Android.
        val copiedOutput = f.source(terminalOutput)
        f.set(copiedOutput)
        val retainedOffer = f.client.all(DataOffer::class.java).last()
        equal(terminalOutput, f.clipboard.text()) // What ChatGPT Paste reads.

        // Copy a DIFFERENT command in ChatGPT while the launcher is backgrounded.
        // No Android callback or Wayland focus change: return to the same terminal.
        f.clipboard.external(nextCommand)
        check(f.server.input.keyboardFocus === focusedTerminal)
        equal(nextCommand, f.receiveText(retainedOffer))
        equal(nextCommand, f.clipboard.text())
    },
    "Linux text copy mirrors and remains pasteable" to {
        val f = Fixture()
        val text = "Linux copy\n你好 🦉\n"
        val source = f.source(text)
        f.set(source)
        equal(text, f.clipboard.value)
        equal(text, f.receiveText(f.offer(source)))
        check(!source.cancelled)
    },
    "Linux nontext MIME survives unchanged" to {
        val f = Fixture()
        val data = byteArrayOf(0, 1, 2, -1, 0, 27, 10)
        val source = f.source(data, listOf(IMAGE))
        f.set(source, mirrorExpected = false)
        check(f.receive(f.offer(source), IMAGE).contentEquals(data))
        equal(0, f.clipboard.writes)
    },
    "Linux copy remains pasteable without Android backend" to {
        val f = Fixture()
        f.server.selection.system = null
        val source = f.source("Linux-only copy")
        f.set(source, mirrorExpected = false)
        equal("Linux-only copy", f.receiveText(f.offer(source)))
        check(!source.cancelled)
    },
    "missed Android change callback cannot serve stale Linux copy" to {
        val f = Fixture()
        val source = f.source("old Linux text")
        f.set(source)
        val offer = f.offer(source)
        f.clipboard.external("new Android text")
        equal("new Android text", f.receiveText(offer))
        check(source.cancelled)
    },
    "same old offer reads newest Android value over 25 pastes" to {
        val f = Fixture()
        val source = f.source("old Linux text")
        f.set(source)
        val offer = f.offer(source)
        repeat(25) { n ->
            val text = "new Android copy $n"
            f.clipboard.external(text)
            equal(text, f.receiveText(offer))
        }
    },
    "already delivered callback permits in-flight text paste" to {
        val f = Fixture()
        val source = f.source("old Linux text")
        f.set(source)
        val offer = f.offer(source)
        f.clipboard.external("new Android text")
        synchronized(f.server) { f.server.selection.systemChanged() }
        equal("new Android text", f.receiveText(offer))
    },
    "cleared Android clipboard never falls back to old Linux text" to {
        val f = Fixture()
        val source = f.source("old secret")
        f.set(source)
        val offer = f.offer(source)
        f.clipboard.external(null)
        equal("", f.receiveText(offer))
    },
    "unavailable Android clipboard closes pipe without stale fallback" to {
        val f = Fixture()
        f.clipboard.failReads = true
        equal("", f.receiveText(f.offer(null)))
    },
    "old offer replaced by newer Linux source closes without substitution" to {
        val f = Fixture()
        val first = f.source("old Linux text")
        f.set(first)
        val offer = f.offer(first)
        val second = f.source("new Linux text")
        f.set(second)
        equal("", f.receiveText(offer))
        equal("new Linux text", f.receiveText(f.offer(second)))
    },
    "destroyed Linux source closes old offer without Android substitution" to {
        val f = Fixture()
        val source = f.source("Linux copy")
        f.set(source)
        val offer = f.offer(source)
        synchronized(f.server) { source.destroy() }
        equal("", f.receiveText(offer))
    },
    "metadata read failure closes paste instead of leaking its descriptor" to {
        val f = Fixture()
        val source = f.source("Linux copy")
        f.set(source)
        val offer = f.offer(source)
        f.clipboard.failStamp = true
        equal("", f.receiveText(offer))
    },
    "stale binary offer does not receive Android text as image bytes" to {
        val f = Fixture()
        val source = f.source(byteArrayOf(0, 1, 2), listOf(IMAGE))
        f.set(source, mirrorExpected = false)
        val offer = f.offer(source)
        f.clipboard.external("new Android text")
        equal(0, f.receive(offer, IMAGE).size)
    },
    "delayed Linux mirror cannot overwrite newer Android copy" to {
        val f = Fixture()
        val source = f.source("old Linux text")
        f.delayNext = true
        f.set(source, mirrorExpected = false)
        f.clipboard.external("new Android text")
        f.respond(f.delayed!!)
        await("mirror handled") { source.cancelled || f.clipboard.writes > 0 }
        equal("new Android text", f.clipboard.value)
        equal(0, f.clipboard.writes)
    },
    "external copy during mirror write is not adopted as Linux ownership" to {
        val f = Fixture()
        val source = f.source("Linux text")
        f.clipboard.externalDuringWrite = "Android copy during write"
        f.set(source)
        equal("Android copy during write", f.clipboard.value)
        equal("Android copy during write", f.receiveText(f.offer(source)))
    },
    "Android replacement within same millisecond supersedes mirrored Linux text" to {
        val f = Fixture()
        val source = f.source("Linux text")
        f.set(source)
        val generation = f.clipboard.generation
        val offer = f.offer(source)
        f.clipboard.external("Android text with equal timestamp")
        f.clipboard.generation = generation
        equal("Android text with equal timestamp", f.receiveText(offer))
    },
    "delayed superseded Linux mirror cannot overwrite newer Linux copy" to {
        val f = Fixture()
        val first = f.source("old Linux text")
        f.delayNext = true
        f.set(first, mirrorExpected = false)
        val oldRequest = f.delayed!!
        val second = f.source("new Linux text")
        f.set(second)
        f.respond(oldRequest)
        await("old mirror pipe closed") { ParcelFileDescriptor.openCount() == 0 }
        equal("new Linux text", f.clipboard.value)
        equal(1, f.clipboard.writes)
    },
    "large Linux copy remains local without truncating Android clipboard" to {
        val f = Fixture()
        val text = "x".repeat(512 * 1024 + 1)
        val source = f.source(text)
        f.set(source, mirrorExpected = false)
        await("oversized mirror pipe closed") { ParcelFileDescriptor.openCount() == 0 }
        equal("initial Android text", f.clipboard.value)
        equal(text.length, f.receiveText(f.offer(source)).length)
        equal(0, f.clipboard.writes)
    },
    "twenty alternating Linux/Android copy cycles never reuse stale text" to {
        val f = Fixture()
        repeat(20) { n ->
            val source = f.source("Linux $n")
            f.set(source)
            val offer = f.offer(source)
            equal("Linux $n", f.receiveText(offer))
            f.clipboard.external("Android $n")
            equal("Android $n", f.receiveText(offer))
        }
    },
    "one-time insertion restores the Linux clipboard without copying inserted text" to {
        val f = Fixture()
        val source = f.source("existing Linux copy")
        f.set(source)
        synchronized(f.server) { f.server.selection.insert("temporary insertion\n你好") }
        val insertedOffer = f.client.all(DataOffer::class.java).last()
        equal("temporary insertion\n你好", f.receiveText(insertedOffer))
        val restoredOffer = f.client.all(DataOffer::class.java).last()
        equal("existing Linux copy", f.receiveText(restoredOffer))
        equal("existing Linux copy", f.clipboard.value)
        equal(1, f.clipboard.writes)
    },
    "one-time insertion restores current Android clipboard" to {
        val f = Fixture()
        synchronized(f.server) { f.server.selection.insert("temporary insertion") }
        f.clipboard.external("new Android copy")
        equal("temporary insertion", f.receiveText(f.client.all(DataOffer::class.java).last()))
        equal("new Android copy", f.receiveText(f.client.all(DataOffer::class.java).last()))
        equal(0, f.clipboard.writes)
    },
    "AndroidClipboard writes ownership metadata and preserves copied text" to {
        androidTest { context, clipboard ->
            clipboard.setText("Linux text\n你好 🦉")
            equal("Linux text\n你好 🦉", clipboard.text())
            check(context.clipboard.primaryClipDescription?.extras?.getString("dev.omalauncher.clipboard.owner") != null)
        }
    },
    "AndroidClipboard rejects an external copy between write and metadata read" to {
        androidTest { context, clipboard ->
            context.clipboard.afterWrite = { context.clipboard.clip = ClipData.newPlainText("external", "new Android copy") }
            val written: Any? = clipboard.setText("Linux text")
            equal(null, written)
            equal("new Android copy", clipboard.text())
        }
    },
    "AndroidClipboard distinguishes own copy replaced at the same timestamp" to {
        androidTest { context, clipboard ->
            clipboard.setText("Linux text")
            val previous: Any? = clipboard.stamp()
            val timestamp = context.clipboard.primaryClipDescription!!.timestamp
            context.clipboard.clip = ClipData.newPlainText("external", "new Android copy").also { it.description.timestamp = timestamp }
            check(previous != clipboard.stamp())
            equal("new Android copy", clipboard.text())
        }
    },
    "Android image paste saves original bytes and reuses the same file" to {
        androidTest { context, clipboard ->
            val bytes = byteArrayOf(-119, 80, 78, 71, 0, 10, 26, 10)
            context.clipboard.clip = imageClip(context, "photo", 100, bytes)
            val first = clipboard.text()!!
            equal(first, clipboard.text())
            check(File(first).readBytes().contentEquals(bytes))
            equal(1, context.contentResolver.opens)
        }
    },
    "image copied during paste cannot reuse the previous image file" to {
        androidTest { context, clipboard ->
            val firstBytes = byteArrayOf(1, 2, 3)
            val secondBytes = byteArrayOf(4, 5, 6)
            val first = imageClip(context, "first", 100, firstBytes)
            val second = imageClip(context, "second", 200, secondBytes)
            context.clipboard.clip = first
            context.contentResolver.afterTypeRead = {
                context.clipboard.clip = second
                context.contentResolver.afterTypeRead = null
            }
            val firstPath = clipboard.text()!!
            val secondPath = clipboard.text()!!
            check(File(firstPath).readBytes().contentEquals(firstBytes))
            check(File(secondPath).readBytes().contentEquals(secondBytes))
            check(firstPath != secondPath)
            equal(2, context.contentResolver.opens)
        }
    },
)

fun main() {
    var failed = 0
    for ((name, test) in cases) {
        try {
            test()
            println("PASS $name")
        } catch (error: Throwable) {
            failed++
            println("FAIL $name: ${error.message}")
        }
    }
    println("RESULT ${cases.size - failed}/${cases.size} passed; $failed failed")
    check(failed == 0) { "$failed regression test(s) failed" }
}
