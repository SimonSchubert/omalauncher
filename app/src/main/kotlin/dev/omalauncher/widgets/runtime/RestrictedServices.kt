package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.FALSE
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The capabilities available to a shared or marketplace widget before the user
 * explicitly allows Linux access. Android-side data and HTTP remain available,
 * but the widget cannot reach the guest or use the phone as a bridge into it.
 */
internal class RestrictedServices(
    private val delegate: Services,
    private val onDenied: () -> Unit,
) : Services by delegate {
    private var reported = false

    private fun denied() {
        if (!reported) {
            reported = true
            onDenied()
        }
    }

    override suspend fun cmd(
        command: String,
        timeoutSeconds: Int,
    ): ShellResult {
        denied()
        return ShellResult("", LINUX_DISABLED, -1)
    }

    override suspend fun stream(
        command: String,
        onLine: (String) -> Unit,
    ) {
        denied()
    }

    override fun run(
        command: String,
        done: (ShellResult?) -> Unit,
    ) {
        denied()
        done(null)
    }

    override fun android(name: String): Value =
        if (name == "notifications") {
            denied()
            emptyNotifications()
        } else {
            delegate.android(name)
        }

    override fun androidUpdates(name: String): Flow<Value>? =
        if (name == "notifications") {
            denied()
            null
        } else {
            delegate.androidUpdates(name)
        }

    override fun act(
        name: String,
        args: List<Value>,
        done: () -> Unit,
    ) {
        if (name in BLOCKED_ACTIONS) {
            denied()
            done()
        } else {
            delegate.act(name, args, done)
        }
    }

    override fun guestFile(path: String): java.io.File? {
        denied()
        return null
    }

    override fun fileUpdates(path: String): Flow<String> =
        flow {
            denied()
            emit("")
        }

    override fun terminal(
        command: String?,
        fontSize: Double?,
    ): dev.omalauncher.ui.EmbeddedTerminal? {
        denied()
        return null
    }

    private fun emptyNotifications(): Value =
        Obj(
            mapOf(
                "available" to FALSE,
                "count" to Num(0.0),
                "items" to Lst(emptyList()),
            ),
        )

    companion object {
        private const val LINUX_DISABLED = "Linux access is disabled for this widget"

        /** Actions that can execute commands indirectly, disclose data, or control another app. */
        private val BLOCKED_ACTIONS =
            setOf(
                "run",
                "terminal",
                "app",
                "ask",
                "open",
                "url",
                "share",
                "copy",
                "notify",
                "notification",
                "launcher",
                "androidApp",
                "appAction",
                "workApps",
                "pressKey",
                "typeText",
                "latch",
            )
    }
}
