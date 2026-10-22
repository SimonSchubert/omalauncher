package dev.omalauncher.wayland

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "wl.client"

/** libwayland's limit for one message; also our buffer sizes' unit. */
private const val MAX_MESSAGE = 4096

/** A protocol object owned by a client. */
abstract class Resource(
    val client: Client,
    val id: Int,
    val version: Int,
) {
    abstract val iface: String

    abstract fun request(
        opcode: Int,
        args: Args,
    )

    /** Called once when the object goes away, either by request or client disconnect. */
    open fun onDestroy() {}

    fun event(opcode: Int) = Event(id, opcode)

    fun send(event: Event) = client.send(event)

    /** Destroys the object in response to a client destructor request. */
    fun destroy() {
        if (client.unregister(id) != null) {
            client.zombie(this)
            onDestroy()
            // Ids we allocated aren't acknowledged: the client frees them itself.
            if (!isServerId(id)) client.send(Event(1, 1).uint(id)) // wl_display.delete_id
        }
    }
}

/** Ids from here up are allocated by the server, for objects it creates (data offers). */
private const val SERVER_ID_START = 0xff000000.toInt()

fun isServerId(id: Int) = Integer.compareUnsigned(id, SERVER_ID_START) >= 0

/**
 * Pixel work a request asked for, run after the server lock is released so
 * a large copy never stalls other clients, input or the UI.
 */
fun interface Deferred {
    fun run()
}

/** A connected Wayland client; reads and dispatches requests on its own thread. */
class Client(
    val server: WaylandServer,
    private val fd: Int,
) {
    val objects = HashMap<Int, Resource>()
    private val fds = ArrayDeque<Int>()

    /**
     * Interfaces of destroyed ids until they're reused. Requests already in
     * flight to them are dropped, but any fds they carry must still be taken
     * off the queue, or later requests would get the wrong ones.
     */
    private val zombies = HashMap<Int, String>()

    fun zombie(resource: Resource) {
        if (resource.iface in FD_REQUESTS) zombies[resource.id] = resource.iface
    }

    /** Set by a request handler that has pixel work to do outside the lock; see [Deferred]. */
    private var deferred: Deferred? = null

    fun defer(work: Deferred) {
        check(deferred == null) { "one deferred job per request" }
        deferred = work
    }

    /**
     * Events are queued and written by a dedicated thread: a client that stalls
     * (common under proot) must never block whoever holds the server lock.
     */
    private val outbox = LinkedBlockingQueue<Event>()

    private val open = AtomicBoolean(true)
    val alive get() = open.get()

    init {
        objects[1] = DisplayResource(this)
    }

    private var nextServerId = SERVER_ID_START

    fun newServerId() = nextServerId++

    inline fun <reified T : Resource> get(id: Int): T? = objects[id] as? T

    fun <T : Resource> register(resource: T): T {
        zombies.remove(resource.id)
        objects.put(resource.id, resource)?.let { typed[it.javaClass]?.remove(it) }
        typed.getOrPut(resource.javaClass) { ArrayList() } += resource
        return resource
    }

    fun unregister(id: Int): Resource? = objects.remove(id)?.also { typed[it.javaClass]?.remove(it) }

    /** Objects by class, so input and output events don't scan every object the client has. */
    private val typed = HashMap<Class<*>, ArrayList<Resource>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Resource> all(type: Class<T>): List<T> = (typed[type] ?: emptyList<Resource>()) as List<T>

    fun send(event: Event) {
        if (alive) outbox.put(event) else event.releaseFd()
    }

    fun postError(
        objectId: Int,
        code: Int,
        message: String,
    ) {
        Log.w(TAG, "protocol error on $objectId: $message")
        send(Event(1, 0).obj(objectId).uint(code).string(message))
        disconnect()
    }

    /** Stops reading and writing; the writer sends what's queued (an error message, say) first. */
    fun disconnect() {
        if (!open.compareAndSet(true, false)) return
        outbox.put(CLOSE)
    }

    /** Writes queued events in batches: one sendmsg for everything queued, fds included. */
    private fun writeLoop() {
        val buf = ByteBuffer.allocateDirect(MAX_MESSAGE * 8).order(ByteOrder.LITTLE_ENDIAN)
        val sendFds = IntArray(MAX_FDS)
        val batch = ArrayList<Event>()
        try {
            while (true) {
                batch += outbox.take()
                outbox.drainTo(batch)
                var nfds = 0
                for (event in batch) {
                    if (event === CLOSE) {
                        flush(buf, sendFds, nfds)
                        return
                    }
                    if (buf.position() + event.size > buf.capacity() || (event.fd >= 0 && nfds == MAX_FDS)) {
                        if (!flush(buf, sendFds, nfds)) return
                        nfds = 0
                    }
                    buf.put(event.bytes, 0, event.size)
                    if (event.fd >= 0) sendFds[nfds++] = event.fd
                    if (event.closeFd) sent += event
                }
                batch.clear()
                if (!flush(buf, sendFds, nfds)) return
            }
        } catch (_: InterruptedException) {
        } finally {
            // Wakes the reader, if it's still waiting on the socket.
            open.set(false)
            Native.shutdown(fd)
            // Events never sent: their fds are still ours.
            outbox.drainTo(batch)
            (sent + batch).forEach(Event::releaseFd)
        }
    }

    /** Events in the buffer whose fds are closed once it's sent: the client has its own copy then. */
    private val sent = ArrayList<Event>()

    // Declared after every field writeLoop uses: those declared below it would still be null when it starts.
    private val writer = thread(name = "wl-writer-$fd", isDaemon = true) { writeLoop() }

    private fun flush(
        buf: ByteBuffer,
        sendFds: IntArray,
        nfds: Int,
    ): Boolean {
        val len = buf.position()
        buf.clear()
        if (len == 0) return true
        val ok = Native.send(fd, buf, len, sendFds, nfds) >= 0
        sent.forEach(Event::releaseFd)
        sent.clear()
        if (ok) return true
        disconnect()
        return false
    }

    fun run() {
        val buf = ByteBuffer.allocateDirect(MAX_MESSAGE * 16).order(ByteOrder.LITTLE_ENDIAN)
        val fdTmp = IntArray(MAX_FDS + 1)
        val args = Args(buf, fds)
        var len = 0
        try {
            while (alive) {
                val n = Native.recv(fd, buf, len, fdTmp)
                if (n <= 0) break
                for (i in 1..fdTmp[0]) fds.addLast(fdTmp[i])
                len += n
                var pos = 0
                while (alive && len - pos >= 8) {
                    var work: Deferred? = null
                    synchronized(server) {
                        while (alive && len - pos >= 8) {
                            val objectId = buf.getInt(pos)
                            val word = buf.getInt(pos + 4)
                            val size = word ushr 16
                            if (size < 8 || size > MAX_MESSAGE) {
                                postError(1, 1, "bad message size")
                                break
                            }
                            if (len - pos < size) break
                            args.reset(pos + 8, pos + size)
                            dispatch(objectId, word and 0xffff, args)
                            pos += size
                            work = deferred
                            deferred = null
                            if (work != null) break
                        }
                    }
                    // Nothing after this request is handled before its pixels are in: ordering is as sent.
                    work?.run() ?: break
                }
                if (len - pos > 0) {
                    // Reads use absolute offsets, so only the bytes' place matters, not position or limit.
                    buf.limit(len).position(pos)
                    buf.compact()
                    buf.clear()
                }
                len -= pos
            }
        } finally {
            disconnect()
            writer.join(1000)
            // A writer still blocked on a client that stopped reading gets woken this way.
            Native.shutdown(fd)
            synchronized(server) {
                objects.values.toList().forEach {
                    runCatching { it.onDestroy() }.onFailure { e -> Log.w(TAG, "cleanup", e) }
                }
                objects.clear()
                typed.clear()
                server.clients.remove(this)
            }
            fds.forEach(Native::close)
            Native.close(fd)
            Log.i(TAG, "client disconnected")
        }
    }

    private fun dispatch(
        objectId: Int,
        opcode: Int,
        args: Args,
    ) {
        val obj = objects[objectId]
        if (obj == null) {
            // Requests on objects destroyed in flight are expected: drop them, but not their fds.
            val iface = zombies[objectId]
            if (iface != null && FD_REQUESTS[iface]?.contains(opcode) == true) args.fd().takeIf { it >= 0 }?.let(Native::close)
            return
        }
        try {
            obj.request(opcode, args)
        } catch (e: Exception) {
            Log.w(TAG, "error handling ${obj.iface}#$opcode", e)
        }
    }

    private companion object {
        const val MAX_FDS = 28

        /** Requests that carry an fd, by interface: the ones we implement. */
        val FD_REQUESTS = mapOf("wl_shm" to setOf(0), "wl_data_offer" to setOf(1))
    }
}

private val CLOSE = Event(0, 0)

class DisplayResource(
    client: Client,
) : Resource(client, 1, 1) {
    override val iface = "wl_display"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> { // sync
                val cb = args.newId()
                client.send(Event(cb, 0).uint(client.server.nextSerial()))
                client.send(event(1).uint(cb))
            }
            1 -> client.register(RegistryResource(client, args.newId())).announce()
        }
    }
}

class RegistryResource(
    client: Client,
    id: Int,
) : Resource(client, id, 1) {
    override val iface = "wl_registry"

    fun announce() {
        client.server.globals.forEachIndexed { i, g ->
            send(event(0).uint(i + 1).string(g.iface).uint(g.version))
        }
    }

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode != 0) return
        val name = args.uint()
        val iface = args.string()
        val version = args.uint()
        val newId = args.newId()
        val global = client.server.globals.getOrNull(name - 1)
        if (global == null || global.iface != iface) {
            client.postError(id, 0, "invalid global $name ($iface)")
            return
        }
        client.register(global.bind(client, newId, minOf(version, global.version)))
    }
}

class Global(
    val iface: String,
    val version: Int,
    val bind: (Client, Int, Int) -> Resource,
)
