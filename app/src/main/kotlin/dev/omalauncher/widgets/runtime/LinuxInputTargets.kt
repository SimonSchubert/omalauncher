package dev.omalauncher.widgets.runtime

import androidx.compose.runtime.mutableStateMapOf

/** Names are local to one widget instance; view copies may refer to the same session. */
internal class LinuxInputTargets<T : Any> {
    private data class Binding<T>(val name: String, val target: T)
    private val bindings = mutableStateMapOf<Any, Binding<T>>()

    fun bind(token: Any, name: String, target: T) { bindings[token] = Binding(name, target) }
    fun unbind(token: Any) { bindings.remove(token) }

    /** Ambiguous names fail closed instead of choosing whichever window rendered first. */
    fun target(name: String): T? {
        val matches = bindings.values.filter { it.name == name }.map { it.target }
        val first = matches.firstOrNull() ?: return null
        return first.takeIf { matches.all { target -> target === first } }
    }

    fun clear() = bindings.clear()
}
