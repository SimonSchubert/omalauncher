package dev.omalauncher.desktop

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NativeDesktopTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun ui(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun await(message: String, check: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            ui { ready = check() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out: $message")
    }

    @Test fun secondaryHomeAndDesktopWindowsUseIndependentAdaptiveSessions() {
        val context = instrumentation.targetContext
        val homes = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory("android.intent.category.SECONDARY_HOME").setPackage(context.packageName), 0)
        val info = homes.single().activityInfo
        assertEquals(NativeDesktopActivity::class.java.name, info.name)
        assertEquals("Secondary home must permit an instance on each display", ActivityInfo.LAUNCH_MULTIPLE, info.launchMode)
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val images = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2)
        var colors = 0
        images.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { image ->
            val plane = image.planes[0]
            val buffer = plane.buffer ?: return@use
            val unique = HashSet<Int>()
            for (y in 80 until image.height - 80 step 16) for (x in 16 until image.width - 16 step 16) {
                val at = y * plane.rowStride + x * plane.pixelStride
                unique += (buffer.get(at).toInt() and 255) or ((buffer.get(at + 1).toInt() and 255) shl 8) or ((buffer.get(at + 2).toInt() and 255) shl 16)
            }
            colors = unique.size
        } }, Handler(Looper.getMainLooper()))
        val virtual = context.getSystemService(DisplayManager::class.java).createVirtualDisplay("Android desktop acceptance", 1920, 1080, 160,
            images.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 10))
        var phone: NativeDesktopActivity? = null
        var external: NativeDesktopActivity? = null
        try {
            val intent = Intent(context, NativeDesktopActivity::class.java).setAction("dev.omalauncher.action.DESKTOP_HOME")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            phone = instrumentation.startActivitySync(intent) as NativeDesktopActivity
            external = instrumentation.startActivitySync(intent, ActivityOptions.makeBasic().apply { launchDisplayId = virtual.display.displayId }.toBundle()) as NativeDesktopActivity
            val primary = phone
            val desktop = external
            assertNotSame(primary, desktop)
            await("native desktop renders widgets") { desktop.desktopSession!!.widgets.specs.isNotEmpty() && colors > 12 }
            assertEquals(0, primary.display!!.displayId)
            assertEquals(virtual.display.displayId, desktop.sessionDisplayId)
            await("wide desktop shows adjacent pages") { desktop.desktopSession!!.pagesSideBySide }
            ui {
                primary.desktopSession!!.search("phone-only", false)
                val now = android.os.SystemClock.uptimeMillis()
                assertTrue(desktop.window.callback.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_META_ON)))
            }
            await("native keyboard shortcut opens the drawer") { desktop.desktopSession!!.drawer }
            instrumentation.waitForIdleSync()
            ui {
                desktop.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S))
                desktop.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S))
            }
            await("ordinary hardware typing reaches the native search field") { desktop.desktopSession!!.query == "s" }
            assertEquals("phone-only", primary.desktopSession!!.query)
            ui { desktop.desktopSession!!.dismiss() }
            virtual.resize(640, 900, 160)
            await("resizing a desktop window adapts to a single paged home") { !desktop.desktopSession!!.pagesSideBySide }
            assertEquals("Resizing must retain the phone session", "phone-only", primary.desktopSession!!.query)
            val previous = desktop.desktopSession
            val primarySession = primary.desktopSession
            ui {
                desktop.startActivity(Intent(desktop, NativeDesktopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    ActivityOptions.makeBasic().apply { launchDisplayId = 0 }.toBundle())
            }
            await("moving the same window recreates its display-scoped session") {
                desktop.display!!.displayId == 0 && desktop.sessionDisplayId == 0 && desktop.desktopSession !== previous
            }
            assertSame("Moving another window must not replace the primary session", primarySession, primary.desktopSession)
        } finally {
            ui {
                external?.finish(); phone?.finish()
                images.setOnImageAvailableListener(null, null)
                virtual.release()
                images.close()
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
