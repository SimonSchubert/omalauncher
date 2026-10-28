package dev.omalauncher.linux

import java.lang.ref.WeakReference

/** Adapter ownership is not retained by retaining its Kotlin session. A lost
 * pre-handoff host must fail, while an already-finished adapter is never
 * submitted again just to attach a replacement Activity. Main thread only. */
internal class PortalPrintHost<T : Any> {
    private var host: WeakReference<T>? = null
    private var adapterFinished = false
    private var closed = false

    fun attach(value: T): Boolean {
        if (closed || host?.get()?.let { it !== value } == true) return false
        host = WeakReference(value)
        return true
    }

    fun owns(value: T): Boolean = !closed && host?.get() === value

    fun adapterFinished() { adapterFinished = true }

    /** True means the live adapter/preparation has lost its only valid host. */
    fun destroyed(value: T): Boolean {
        if (!owns(value)) return false
        host = null
        if (adapterFinished) return false
        closed = true
        return true
    }

    fun close() { closed = true; host = null }
}
