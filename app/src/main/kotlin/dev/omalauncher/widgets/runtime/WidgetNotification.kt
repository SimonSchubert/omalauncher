package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.MAX_NOTIFICATION_BUTTONS
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value

/**
 * A widget's `notification:` as the launcher shows it: [until] counts down to a Unix time and [since] up from one,
 * both on a clock Android runs, so it keeps time on the lock screen without the widget redrawing.
 */
data class WidgetNotification(
    val title: String,
    val text: String?,
    val until: Double?,
    val since: Double?,
    val buttons: List<Button>,
) {
    /** A button's words, and the `value` onNotification gets when it's pressed. */
    data class Button(
        val text: String,
        val value: Value,
    )

    /** What a widget's notification: works out to: one to show, or none, and what's wrong with it. */
    data class Parsed(
        val notification: WidgetNotification?,
        val error: String? = null,
    )

    companion object {
        private val FIELDS = listOf("title", "text", "until", "since", "buttons")
        private val BUTTON_FIELDS = listOf("text", "value")

        /** [v] as a notification: a map shows one, titled [name] unless it says otherwise; null or false shows none. */
        fun of(
            v: Value,
            name: String,
        ): Parsed {
            if (!v.truthy && v !is Obj) return Parsed(null)
            if (v !is Obj) return Parsed(null, "it's a map like {title: \"tea\", until: endsAt}, or null for none, not $v")
            v.fields.keys.firstOrNull { it !in FIELDS }?.let { return Parsed(null, "it has no '$it'; it takes ${FIELDS.joinToString()}") }
            if (notTime(v, "until")) return Parsed(null, "until is a Unix time in seconds, like endsAt, not ${v.fields["until"]}")
            if (notTime(v, "since")) return Parsed(null, "since is a Unix time in seconds, like startedAt, not ${v.fields["since"]}")
            val until = time(v, "until")
            val since = time(v, "since")
            if (until != null && since != null) return Parsed(null, "until counts down to a time and since up from one: give one of them")
            val buttons =
                when (val b = v.fields["buttons"] ?: Null) {
                    Null -> emptyList()
                    is Lst -> b.items
                    else -> return Parsed(null, "buttons is a list, like [\"pause\", \"stop\"], not $b")
                }
            if (buttons.size > MAX_NOTIFICATION_BUTTONS) return Parsed(null, "it shows up to $MAX_NOTIFICATION_BUTTONS buttons, not ${buttons.size}")
            val made =
                buttons.map { b ->
                    when {
                        b is Obj -> {
                            b.fields.keys.firstOrNull { it !in BUTTON_FIELDS }?.let { return Parsed(null, "a button has no '$it'; it takes ${BUTTON_FIELDS.joinToString()}") }
                            val text = b.fields["text"]?.takeIf { it != Null }?.toString()?.takeIf { it.isNotEmpty() } ?: return Parsed(null, "a button needs its text, like {text: \"stop\", value: \"stop\"}")
                            Button(text, b.fields["value"]?.takeIf { it != Null } ?: Str(text))
                        }
                        b == Null || b.toString().isEmpty() -> return Parsed(null, "a button needs its text, like \"stop\"")
                        else -> Button(b.toString(), b)
                    }
                }
            val title = v.fields["title"]?.takeIf { it != Null }?.toString()?.takeIf { it.isNotEmpty() } ?: name
            val text = v.fields["text"]?.takeIf { it != Null }?.toString()?.takeIf { it.isNotEmpty() }
            return Parsed(WidgetNotification(title, text, until, since, made))
        }

        /** A time field's seconds; absent, null or 0 is none. */
        private fun time(
            v: Obj,
            field: String,
        ) = (v.fields[field] as? Num)?.v?.takeIf { it > 0 && it.isFinite() }

        /** A time field that holds something other than a time: text, a list, a negative number. */
        private fun notTime(
            v: Obj,
            field: String,
        ) = when (val t = v.fields[field] ?: Null) {
            Null -> false
            is Num -> !t.v.isFinite() || t.v < 0
            else -> true
        }
    }
}
