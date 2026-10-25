package dev.omalauncher.wayland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.MimeTypeMap
import dev.omalauncher.readUpTo
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

private const val TAG = "wl.clipboard"

/** Text mime types, most preferred first: what we ask a source for, and offer Android's clipboard as. */
private val TEXT_MIMES = listOf("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "TEXT", "STRING")

/** Android's clipboard limit is a binder transaction; larger copies stay inside Linux. */
private const val MAX_MIRROR_BYTES = 512 * 1024

/** The system clipboard the Wayland selection is mirrored to. */
interface SystemClipboard {
    fun text(): String?

    fun setText(text: String)

    /** Changes whenever the clip does, whoever set it. */
    fun stamp(): Long
}

/**
 * The clipboard selection, shared by all clients and mirrored both ways with
 * Android's. A copy in Linux keeps its [DataSource] as the selection, so pastes
 * within Linux get every type it offers, and its text goes to Android. A copy
 * in Android replaces it, offered as text. All methods run with the server locked.
 */
class Selection(
    private val server: WaylandServer,
) {
    var system: SystemClipboard? = null

    private var source: DataSource? = null

    /** The system clip's [SystemClipboard.stamp] while [source] was current: a different one means Android copied since. */
    private var stamp = 0L

    fun set(source: DataSource?) {
        if (source === this.source) return
        this.source?.cancel()
        this.source = source
        stamp = system?.stamp() ?: 0L
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
        refresh()
        offer(client)
    }

    /** Drops [source] if Android's clipboard changed since; returns whether the selection is unchanged. */
    private fun refresh(): Boolean {
        val src = source ?: return false
        if (system?.stamp() == stamp) return true
        source = null
        src.cancel()
        return false
    }

    fun offerToFocus() {
        server.input.keyboardFocus?.let { offer(it.client) }
    }

    fun offer(client: Client) {
        val devices = client.all(DataDevice::class.java)
        if (devices.isEmpty()) return
        val src = source
        // Android's clipboard is always offered: whether it has text can't be told reliably
        // here (Android hides it from a window that's just getting focus), so a paste finds out.
        val mimes =
            when {
                src != null -> src.mimes.toList()
                system != null -> TEXT_MIMES
                else -> null
            }
        for (d in devices) {
            if (mimes == null) {
                d.send(d.event(5).obj(0))
                continue
            }
            val offer = client.register(DataOffer(client, client.newServerId(), d.version, src, this))
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
                system.setText(String(bytes, Charsets.UTF_8))
                stamp = system.stamp()
            }
        }
    }

    /** Writes Android's clipboard text to [fd] for a paste, then closes it. */
    fun paste(fd: Int) {
        val system = system
        thread(name = "wl-clip-write", isDaemon = true) {
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(fd)).use { out ->
                val text = runCatching { system?.text() }.getOrNull() ?: return@thread
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

    /** No longer the selection, or gone: offers made from it serve nothing. */
    var cancelled = false
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

    fun cancel() {
        if (cancelled) return
        cancelled = true
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

/** The selection as offered to one client: [source]'s data, or Android's clipboard text when it's null. */
class DataOffer(
    client: Client,
    id: Int,
    version: Int,
    private val source: DataSource?,
    private val selection: Selection,
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
                when {
                    source == null -> selection.paste(fd)
                    source.cancelled -> Native.close(fd)
                    else -> source.send(source.event(1).string(mime).fd(fd, close = true))
                }
            }
            2 -> destroy()
        }
    }
}

/** The largest copied image saved for a paste; a bigger one pastes as its content:// link, as before. */
private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

/**
 * Android's clipboard; [onChange] runs on the main thread when another app (or we) copy. A copied image (a
 * screenshot's "copy", an image copied in the browser) pastes as the path of a copy of it in [imageDir], which
 * Linux sees as [guestImageDir]: terminal agents like Claude Code attach an image whose path is pasted.
 */
class AndroidClipboard(
    private val context: Context,
    private val imageDir: File,
    private val guestImageDir: String,
    onChange: () -> Unit,
) : SystemClipboard {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    init {
        manager.addPrimaryClipChangedListener { onChange() }
    }

    override fun text(): String? {
        val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return imagePath(item) ?: item.coerceToText(context)?.toString()
    }

    /** [item]'s image saved in [imageDir], once per copy, as Linux sees it; null when it isn't an image or can't be read. */
    private fun imagePath(item: ClipData.Item): String? {
        val uri = item.uri ?: return null
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: manager.primaryClipDescription?.getMimeType(0)
        if (mime == null || !mime.startsWith("image/")) return null
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
        val file = File(imageDir, "clipboard-${stamp()}.$ext")
        if (!file.exists()) {
            val bytes =
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_IMAGE_BYTES + 1) }
                } catch (e: Exception) {
                    Log.w(TAG, "reading the copied image failed", e)
                    null
                } ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            // Written beside it and renamed, so a paste racing this one never finds half a file.
            imageDir.mkdirs()
            val part = File(imageDir, file.name + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) return null
        }
        return "$guestImageDir/${file.name}"
    }

    override fun setText(text: String) = manager.setPrimaryClip(ClipData.newPlainText("Linux", text))

    override fun stamp() = manager.primaryClipDescription?.timestamp ?: 0L
}
