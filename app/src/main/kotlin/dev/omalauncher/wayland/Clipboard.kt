package dev.omalauncher.wayland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.util.Log
import android.webkit.MimeTypeMap
import dev.omalauncher.readUpTo
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.concurrent.thread

private const val TAG = "wl.clipboard"

/** Text mime types, most preferred first: what we ask a source for, and offer Android's clipboard as. */
private val TEXT_MIMES = listOf("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "TEXT", "STRING")

/** Android's clipboard limit is a binder transaction; larger copies stay inside Linux. */
private const val MAX_MIRROR_BYTES = 512 * 1024

/** The system clipboard the Wayland selection is mirrored to. */
interface SystemClipboard {
    /** The owner distinguishes our mirrored copy from another copy in the same millisecond. */
    data class Stamp(val timestamp: Long, val owner: String? = null)

    fun text(): String?

    /** Returns this write's stamp, or null if another copy already replaced it or it can't be read. */
    fun setText(text: String): Stamp?

    /** Identity metadata from one Android clip description; null when no clip is accessible. */
    fun stamp(): Stamp?
}

/**
 * The clipboard selection, shared by all clients and mirrored both ways with
 * Android's. A copy in Linux keeps its [DataSource] as the selection, so pastes
 * within Linux get every type it offers, and its text goes to Android. A copy
 * in Android replaces it, offered as text: an image's text is the path it was
 * saved to in Linux, see [AndroidClipboard]. All methods run with the server locked.
 */
class Selection(
    private val server: WaylandServer,
) {
    var system: SystemClipboard? = null

    private var source: DataSource? = null

    /** Text offered ahead of the clipboard for one paste, see [insert]. */
    private var once: String? = null

    /** The system clip's [SystemClipboard.stamp] while [source] was current: a different one means Android copied since. */
    private var stamp: SystemClipboard.Stamp? = null

    fun set(source: DataSource?) {
        if (source === this.source) return
        once = null
        this.source?.cancel()
        this.source = source
        stamp = system?.stamp()
        if (source != null) mirror(source)
        offerToFocus()
    }

    fun sourceDestroyed(source: DataSource) {
        if (this.source !== source) return
        this.source = null
        offerToFocus()
    }

    /** Android's clipboard may have changed: it wins over a Linux selection older than it. */
    fun systemChanged() {
        if (!refresh()) offerToFocus()
    }

    /** Sends the selection to [client], which is about to get keyboard focus. */
    fun focusChanged(client: Client) {
        once = null
        refresh()
        offer(client)
    }

    /** Drops [source] if Android's clipboard changed since; returns whether the selection is unchanged. */
    private fun refresh(): Boolean {
        val src = source ?: return false
        val system = system ?: return true
        if (system.stamp() == stamp) return true
        source = null
        src.cancel(supersededBySystem = true)
        return false
    }

    /**
     * Offers [text] to the focused window for its next paste, leaving the clipboard as it is:
     * a paste key right after pastes it, then the clipboard is offered again.
     */
    fun insert(text: String) {
        once = text
        offerToFocus()
    }

    /** The paste [insert] was for has asked for its text. */
    fun inserted() {
        once = null
        offerToFocus()
    }

    fun offerToFocus() {
        server.input.keyboardFocus?.let { offer(it.client) }
    }

    fun offer(client: Client) {
        val devices = client.all(DataDevice::class.java)
        if (devices.isEmpty()) return
        val once = once
        val src = source.takeIf { once == null }
        // Android's clipboard is always offered: whether it has text can't be told reliably
        // here (Android hides it from a window that's just getting focus), so a paste finds out.
        val mimes =
            when {
                once != null -> TEXT_MIMES
                src != null -> src.mimes.toList()
                system != null -> TEXT_MIMES
                else -> null
            }
        for (d in devices) {
            if (mimes == null) {
                d.send(d.event(5).obj(0))
                continue
            }
            val offer = client.register(DataOffer(client, client.newServerId(), d.version, src, this, once))
            d.send(d.event(0).int(offer.id))
            for (m in mimes) offer.send(offer.event(0).string(m))
            d.send(d.event(5).obj(offer.id))
        }
    }

    /** Reads [source]'s text and puts it on Android's clipboard. */
    private fun mirror(source: DataSource) {
        val system = system ?: return
        val mime = TEXT_MIMES.firstOrNull { it in source.mimes } ?: return
        val (read, write) = ParcelFileDescriptor.createPipe()
        source.send(source.event(1).string(mime).fd(write.detachFd(), close = true))
        thread(name = "wl-clip-read", isDaemon = true) {
            val bytes =
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readUpTo(MAX_MIRROR_BYTES + 1) }
                } catch (e: IOException) {
                    Log.w(TAG, "reading the selection failed", e)
                    return@thread
                }
            if (bytes.isEmpty() || bytes.size > MAX_MIRROR_BYTES) return@thread
            synchronized(server) {
                if (this.source !== source) return@thread
                // Reading the Linux source can take time. An Android copy made meanwhile
                // must not be overwritten, even if its change callback hasn't reached us.
                if (!runCatching { refresh() }.getOrDefault(false)) {
                    source.cancel(supersededBySystem = true)
                    this.source = null
                    offerToFocus()
                    return@thread
                }
                // Use only the stamp belonging to our write. A separate stamp() after
                // setText() could adopt a newer Android copy as if it were this source's.
                val written = runCatching { system.setText(String(bytes, Charsets.UTF_8)) }.getOrNull()
                if (written == null) {
                    source.cancel(supersededBySystem = true)
                    this.source = null
                    offerToFocus()
                } else {
                    stamp = written
                }
            }
        }
    }

    /**
     * Receives an offer at paste time. Android can withhold clipboard callbacks while the
     * launcher is in the background, and returning to the same terminal needn't change its
     * Wayland focus. Revalidate even an existing Linux offer before asking its source.
     */
    fun receive(
        offeredSource: DataSource?,
        mime: String?,
        fd: Int,
    ) {
        // We own fd now: if Android's service is unavailable, close it so the client
        // sees EOF, rather than hanging or receiving an unverified old selection.
        val previous = source
        val unchanged = runCatching { refresh() }.getOrElse {
            Native.close(fd)
            return
        }
        if (previous != null && !unchanged) offerToFocus()
        when {
            offeredSource == null -> paste(fd)
            !offeredSource.cancelled -> offeredSource.send(offeredSource.event(1).string(mime).fd(fd, close = true))
            // The client may already have requested the old offer when we cancelled it.
            // Fulfil that text paste from Android too; binary offers cannot become text.
            source == null && offeredSource.supersededBySystem && mime in TEXT_MIMES -> paste(fd)
            else -> Native.close(fd)
        }
    }

    /** Writes [text], or else Android's clipboard text, to [fd] for a paste, then closes it. */
    fun paste(
        fd: Int,
        text: String? = null,
    ) {
        val system = system
        thread(name = "wl-clip-write", isDaemon = true) {
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(fd)).use { out ->
                val text = text ?: runCatching { system?.text() }.getOrNull() ?: return@thread
                // The client may stop reading early (EPIPE): nothing to do about it.
                runCatching { out.write(text.toByteArray()) }
            }
        }
    }
}

class DataDeviceManager(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_data_device_manager"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> client.register(DataSource(client, args.newId(), version))
            1 -> {
                client.register(DataDevice(client, args.newId(), version))
                // A device made while its client has focus gets the selection right away.
                val selection = client.server.selection
                if (client.server.input.keyboardFocus?.client === client) selection.offer(client)
            }
        }
    }
}

class DataSource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_data_source"

    val mimes = LinkedHashSet<String>()

    /** No longer the selection, or gone: never request more data from it. */
    var cancelled = false
        private set

    /** Old text offers may still be in flight when Android replaces this source. */
    var supersededBySystem = false
        private set

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> args.string()?.let { mimes += it }
            1 -> destroy()
        }
    }

    fun cancel(supersededBySystem: Boolean = false) {
        if (cancelled) return
        cancelled = true
        this.supersededBySystem = supersededBySystem
        send(event(2))
    }

    override fun onDestroy() {
        cancelled = true
        client.server.selection.sourceDestroyed(this)
    }
}

class DataDevice(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_data_device"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            // Drag and drop isn't supported: end the drag before it starts.
            0 -> client.get<DataSource>(args.obj())?.cancel()
            1 -> {
                val source = args.obj().takeIf { it != 0 }?.let { client.get<DataSource>(it) ?: return }
                client.server.selection.set(source)
            }
            2 -> destroy()
        }
    }
}

/** The selection as offered to one client: [text] from [Selection.insert], else [source]'s data, or Android's clipboard text when it's null. */
class DataOffer(
    client: Client,
    id: Int,
    version: Int,
    private val source: DataSource?,
    private val selection: Selection,
    private val text: String? = null,
) : Resource(client, id, version) {
    override val iface = "wl_data_offer"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            1 -> {
                val mime = args.string()
                val fd = args.fd()
                if (fd < 0) return
                if (text != null) {
                    selection.paste(fd, text)
                    selection.inserted()
                } else {
                    selection.receive(source, mime, fd)
                }
            }
            2 -> destroy()
        }
    }
}

/**
 * Images from Android saved where Linux can read them, in the guest's /tmp, which starts
 * empty with each run. They're pasted as their guest path, which agents like opencode and
 * Claude Code attach as an image.
 */
class GuestImages(
    private val context: Context,
    private val dir: File,
    private val guestPath: (File) -> String?,
) {
    /** Copies the image at [uri] into Linux, of type [mime] or else the one its app says; null if it can't be read. */
    fun save(
        uri: Uri,
        mime: String? = null,
    ): File? {
        val type = mime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val ext = type?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType) ?: "img"
        // Several picked at once share a second: numbered after the first.
        val name = "clipboard-${LocalDateTime.now().format(NAME_TIME)}"
        val file = generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) "$name.$ext" else "$name-$it.$ext") }.first { !it.exists() }
        try {
            dir.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "saving the image failed", e)
            file.delete()
            return null
        }
        return file
    }

    fun guestPath(file: File) = guestPath.invoke(file)

    private companion object {
        val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

/**
 * Android's clipboard; [onChange] runs on the main thread when another app (or we) copy.
 * A copied image (Chrome's Copy image) pastes as the guest path [images] saves it to.
 */
class AndroidClipboard(
    private val context: Context,
    private val images: GuestImages,
    onChange: () -> Unit,
) : SystemClipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    /** The clip [saved] was saved from, by its timestamp: pasting it again reuses the file. */
    private var savedStamp = 0L
    private var saved: File? = null

    init {
        manager.addPrimaryClipChangedListener { onChange() }
    }

    override fun text(): String? {
        val clip = manager.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        val item = clip.getItemAt(0)
        return imagePath(item, clip.description.timestamp) ?: item.coerceToText(context)?.toString()
    }

    /** Saves [item]'s image for Linux and returns its guest path; null if it's no image or can't be read. */
    @Synchronized
    private fun imagePath(
        item: ClipData.Item,
        stamp: Long,
    ): String? {
        val uri = item.uri ?: return null
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("image/") } ?: return null
        val file = saved?.takeIf { stamp == savedStamp && it.exists() } ?: images.save(uri, mime) ?: return null
        savedStamp = stamp
        saved = file
        return images.guestPath(file)
    }

    override fun setText(text: String): SystemClipboard.Stamp? {
        val owner = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("Linux", text)
        clip.description.extras = PersistableBundle().apply { putString(OWNER_KEY, owner) }
        manager.setPrimaryClip(clip)
        return stamp()?.takeIf { it.owner == owner }
    }

    override fun stamp(): SystemClipboard.Stamp? =
        manager.primaryClipDescription?.let { SystemClipboard.Stamp(it.timestamp, it.extras?.getString(OWNER_KEY)) }

    private companion object {
        const val OWNER_KEY = "dev.omalauncher.clipboard.owner"
    }
}
