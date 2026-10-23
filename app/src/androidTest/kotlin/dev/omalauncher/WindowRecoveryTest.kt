package dev.omalauncher

import android.app.ActivityManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import dev.omalauncher.ui.HomeMenu
import dev.omalauncher.ui.LauncherState
import dev.omalauncher.wayland.Args
import dev.omalauncher.wayland.Client
import dev.omalauncher.wayland.Surface
import dev.omalauncher.wayland.XdgSurface
import dev.omalauncher.wayland.XdgToplevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Uses real compositor windows and Android document tasks, not task-manager mocks. */
class WindowRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var runtime: Runtime
    private val peers = mutableListOf<ParcelFileDescriptor>()
    private val readers = mutableListOf<Thread>()

    @Before fun prepare() {
        context.getSharedPreferences("launcher", 0).edit().putBoolean("home_role_asked", true).commit()
        runtime = onMain { (context.applicationContext as App).runtime }
    }

    @After fun disconnectWindows() {
        peers.forEach { it.close() }
        readers.forEach { it.join(5000) }
        instrumentation.waitForIdleSync()
    }

    @Test fun selectingOneOfTwoIdenticalWindowsReusesItsTask() {
        val first = window("Tern")
        val second = window("Tern")
        val firstHandle = handle(first)
        val secondHandle = handle(second)
        await { tasks(firstHandle).size == 1 && tasks(secondHandle).size == 1 && onMain { runtime.wm.focused === second } }
        val firstTask = checkNotNull(tasks(firstHandle).single().taskInfo).taskId
        val secondTask = checkNotNull(tasks(secondHandle).single().taskInfo).taskId
        assertNotEquals(firstTask, secondTask)

        assertTrue(onMain { runtime.openWindow(first) })
        await { onMain { runtime.wm.focused === first } }
        assertEquals(firstTask, checkNotNull(tasks(firstHandle).single().taskInfo).taskId)
        assertEquals(secondTask, checkNotNull(tasks(secondHandle).single().taskInfo).taskId)
        assertSame(first, onMain { runtime.wm.windowByHandle(firstHandle) })
    }

    @Test fun missingTaskReattachesOnceEvenWhenTitleChangesDuringLaunch() {
        val window = window("Terminal", openTask = false)
        val handle = handle(window)
        assertTrue(tasks(handle).isEmpty())
        onMain {
            assertTrue(runtime.openWindow(window))
            // This changes the app alias selection if it isn't kept stable for this live window.
            title(window, "btop")
            repeat(3) { assertTrue(runtime.openWindow(window)) }
        }
        await { tasks(handle).size == 1 && onMain { runtime.wm.focused === window } }
        instrumentation.waitForIdleSync()
        val taskId = checkNotNull(tasks(handle).single().taskInfo).taskId
        assertTrue(onMain { runtime.openWindow(window) })
        instrumentation.waitForIdleSync()
        assertEquals(taskId, checkNotNull(tasks(handle).single().taskInfo).taskId)
        assertSame(window, onMain { runtime.wm.windowByHandle(handle) })
    }

    @Test fun closedAndEmbeddedWindowsCannotOpenTasks() {
        val closed = window("Closed", openTask = false)
        val oldHandle = handle(closed)
        onMain { runtime.wm.toplevelDestroyed(closed) }
        instrumentation.waitForIdleSync()
        assertFalse(onMain { runtime.openWindow(closed) })
        assertNull(onMain { runtime.wm.windowByHandle(oldHandle) })
        assertTrue(tasks(oldHandle).isEmpty())

        val embedded = window("Embedded", openTask = false, embedded = true)
        assertFalse(onMain { runtime.openWindow(embedded) })
        assertNull(onMain { runtime.wm.handleOf(embedded) })
    }

    @Test fun homeMenuShowsLiveTitlesAndOpensTheChosenWindow() {
        val first = window("Tern", openTask = false)
        val second = window("Tern", openTask = false)
        val firstNumber = handle(first).substringAfterLast('-')
        val secondNumber = handle(second).substringAfterLast('-')
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val state = onMain { LauncherState().apply { homeMenuOpen = true } }
        try {
            onMain { activity.setContent { HomeMenu(runtime, state) } }
            await { node("#$firstNumber · Tern") != null && node("#$secondNumber · Tern") != null }
            onMain { title(first, "k3s dotfiles") }
            await { node("#$firstNumber · k3s dotfiles") != null }
            var target = node("#$firstNumber · k3s dotfiles")!!
            while (!target.isClickable) target = target.parent ?: error("Window row is not clickable")
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { onMain { runtime.wm.focused === first && !state.homeMenuOpen } }
            assertEquals(1, tasks(handle(first)).size)
            assertTrue(tasks(handle(second)).isEmpty())
        } finally {
            onMain { activity.finish() }
        }
    }

    /** A real Wayland client socket and protocol objects; disconnect drives normal window teardown. */
    private fun window(name: String, openTask: Boolean = true, embedded: Boolean = false): XdgToplevel {
        val sockets = ParcelFileDescriptor.createSocketPair()
        peers += sockets[1]
        val client = onMain { Client(runtime.server, sockets[0].detachFd()) }
        readers += thread(name = "recovery-test-client") { client.run() }
        val window = onMain {
            synchronized(runtime.server) {
                val surface = client.register(Surface(client, 2, 4))
                val xdg = client.register(XdgSurface(client, 3, 1, surface))
                val top = client.register(XdgToplevel(client, 4, 1, xdg))
                xdg.toplevel = top
                title(top, name)
                val open = runtime.wm.openAppWindow
                val claim = runtime.wm.claim
                if (!openTask) runtime.wm.openAppWindow = {}
                if (embedded) runtime.wm.claim = { it === top || claim(it) }
                xdg.onCommit()
                // The window manager posts creation to main; restore callbacks after that post.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runtime.wm.openAppWindow = open
                    runtime.wm.claim = claim
                }
                top
            }
        }
        instrumentation.waitForIdleSync()
        return window
    }

    private fun title(window: XdgToplevel, value: String) {
        val text = value.toByteArray()
        val size = (text.size + 1 + 3) and 3.inv()
        val bytes = ByteBuffer.allocate(4 + size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(text.size + 1).put(text)
        window.request(2, Args(bytes, ArrayDeque()).apply { reset(0, bytes.capacity()) })
    }

    private fun handle(window: XdgToplevel) = onMain { runtime.wm.handleOf(window)!! }

    private fun tasks(handle: String): List<ActivityManager.AppTask> =
        context.getSystemService(ActivityManager::class.java).appTasks.filter {
            runCatching { it.taskInfo?.baseIntent?.data?.toString() == "omarchy://window/$handle" }.getOrDefault(false)
        }

    private fun node(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Recovery did not reach the expected window/task state" }
            SystemClock.sleep(50)
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(Callable(action))
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
