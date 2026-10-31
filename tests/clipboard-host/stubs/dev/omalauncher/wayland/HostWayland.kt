package dev.omalauncher.wayland

import android.os.ParcelFileDescriptor

class WaylandServer {
    val input = Input()
    val selection = Selection(this)
}

class Input {
    var keyboardFocus: Surface? = null
}

class Surface(val client: Client)

class Client(val server: WaylandServer) {
    val resources = mutableMapOf<Int, Resource>()
    var onSend: (Event) -> Unit = {}
    private var nextId = 1000
    fun <T : Resource> register(resource: T): T = resource.also { resources[it.id] = it }
    fun newServerId(): Int = nextId++
    fun <T : Resource> all(type: Class<T>): List<T> = resources.values.filter(type::isInstance).map(type::cast)
    inline fun <reified T : Resource> get(id: Int): T? = resources[id] as? T
}

abstract class Resource(val client: Client, val id: Int, val version: Int) {
    abstract val iface: String
    abstract fun request(opcode: Int, args: Args)
    open fun onDestroy() {}
    fun destroy() {
        client.resources.remove(id)
        onDestroy()
    }
    fun event(opcode: Int) = Event(id, opcode)
    fun send(event: Event) = client.onSend(event)
}

object Native {
    fun close(fd: Int) = ParcelFileDescriptor.closeFd(fd)
}
