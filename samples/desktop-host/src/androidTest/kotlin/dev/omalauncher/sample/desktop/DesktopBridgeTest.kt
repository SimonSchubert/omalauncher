package dev.omalauncher.sample.desktop

import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import dev.omalauncher.desktop.IDesktopHost
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real cross-process Binder + surface acceptance test, without any provider-private test hooks. */
@RunWith(AndroidJUnit4::class)
class DesktopBridgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun ui(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun await(description: String, check: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            ui { ready = check() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out: $description")
    }

    @Test fun consentSurfaceLaunchRevocationAndReconnect() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HostActivity::class.java).putExtra("captureDisplay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        try {
            await("provider binding") { activity.bridge != null }
            val bridge = activity.bridge!!
            bridge.revokeAccess()
            assertEquals("unsupported", bridge.negotiate(99).getString("status"))
            assertEquals("consent_required", bridge.negotiate(1).getString("status"))
            val callback = object : IDesktopHost.Stub() {
                override fun onAndroidLaunch(id: String, component: ComponentName, user: UserHandle, serial: Long) = Unit
                override fun onStatus(id: String, status: String, message: String) = Unit
            }
            assertEquals("consent_required", bridge.openSession(1, Bundle(), Binder(), callback).getString("status"))
            ui { activity.performAppLaunch = false; activity.connect() }
            val allow = UiDevice.getInstance(instrumentation).findObject(UiSelector().textMatches("(?i)allow"))
            assertTrue("provider consent dialog", allow.waitForExists(10_000))
            assertTrue(allow.click())
            await("authorized surface") { activity.session != null }
            await("rendered desktop pixels") { activity.frameCount > 0 && activity.frameColors > 8 }
            val firstId = activity.sessionId!!
            val session = activity.session!!
            val beforeHide = activity.frameSignature
            session.resize(1024, 640)
            session.setVisible(false)
            await("hidden surface is cleared") { activity.frameSignature != beforeHide && activity.frameColors <= 8 }
            session.setVisible(true)
            await("surface renders after showing") { activity.frameColors > 8 }
            session.search("settings", true)
            await("Android launch callback") { activity.lastLaunch != null }
            assertEquals(firstId, activity.lastLaunch!!.first)
            assertEquals("com.android.settings", activity.lastLaunch!!.second.packageName)
            assertEquals(Process.myUserHandle(), activity.lastLaunch!!.third)
            assertNotNull(activity.lastSerial)
            session.action("widgets")
            session.action("dismiss")
            bridge.revokeAccess()
            await("revoked session") { activity.lastStatus == "revoked" && activity.session == null }
            assertEquals("consent_required", bridge.negotiate(1).getString("status"))
            assertThrows(RuntimeException::class.java) { session.search("settings", true) }
            ui { activity.disconnect(); activity.connect() }
            assertTrue(allow.waitForExists(10_000))
            assertTrue(allow.click())
            await("reconnected surface") { activity.session != null }
            await("reconnected surface renders") { activity.frameColors > 8 }
            assertNotEquals(firstId, activity.sessionId)
            activity.session!!.close()
            await("closed session") { activity.session == null }
            assertEquals("ready", bridge.negotiate(1).getString("status"))
        } finally { ui { activity.finish() } }
    }

    @Test fun denyingConsentKeepsTheStandaloneHostUsable() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HostActivity::class.java).putExtra("captureDisplay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        try {
            await("provider binding") { activity.bridge != null }
            activity.bridge!!.revokeAccess()
            ui { activity.connect() }
            val deny = UiDevice.getInstance(instrumentation).findObject(UiSelector().textMatches("(?i)deny"))
            assertTrue(deny.waitForExists(10_000))
            assertTrue(deny.click())
            assertEquals("consent_required", activity.bridge!!.negotiate(1).getString("status"))
            instrumentation.waitForIdleSync()
            assertNull(activity.session)
            assertFalse("denial must not reopen consent", deny.waitForExists(1_000))
        } finally { ui { activity.finish() } }
    }

    @Test fun providerDeathInvalidatesItsSurfaceAndReconnectsWithoutNewConsent() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HostActivity::class.java).putExtra("captureDisplay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        try {
            await("provider binding") { activity.bridge != null }
            val consentRequired = activity.bridge!!.negotiate(1).getString("status") == "consent_required"
            ui { activity.performAppLaunch = false; activity.connect() }
            if (consentRequired) {
                val allow = UiDevice.getInstance(instrumentation).findObject(UiSelector().textMatches("(?i)allow"))
                assertTrue(allow.waitForExists(10_000))
                assertTrue(allow.click())
            }
            await("desktop surface") { activity.session != null && activity.frameColors > 8 }
            val old = activity.session!!
            val oldId = activity.sessionId
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am force-stop ${BuildConfig.PROVIDER_PACKAGE}")).use { it.readBytes() }
            await("provider disconnected") { activity.bridge == null && activity.session == null }
            assertThrows(android.os.RemoteException::class.java) { old.getSurface() }
            ui { activity.connect() }
            await("provider reconnect and fresh pixels") { activity.session != null && activity.frameColors > 8 }
            assertNotEquals(oldId, activity.sessionId)
            assertEquals("ready", activity.bridge!!.negotiate(1).getString("status"))
        } finally { ui { activity.finish() } }
    }

    @Test fun workProfileLaunchKeepsItsUserAndSerial() {
        val serial = InstrumentationRegistry.getArguments().getString("workSerial")?.toLong()
        org.junit.Assume.assumeNotNull(serial)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HostActivity::class.java).putExtra("captureDisplay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        try {
            await("provider binding") { activity.bridge != null }
            val consentRequired = activity.bridge!!.negotiate(1).getString("status") == "consent_required"
            ui { activity.performAppLaunch = false; activity.connect() }
            if (consentRequired) {
                val allow = UiDevice.getInstance(instrumentation).findObject(UiSelector().textMatches("(?i)allow"))
                assertTrue(allow.waitForExists(10_000))
                assertTrue(allow.click())
            }
            await("authorized surface") { activity.session != null }
            // Android intentionally hides Settings' launcher activity in managed profiles; Clock has both copies.
            activity.session!!.search("clock work", true)
            await("work launch callback") { activity.lastLaunch != null }
            assertEquals("com.google.android.deskclock", activity.lastLaunch!!.second.packageName)
            assertNotEquals(Process.myUserHandle(), activity.lastLaunch!!.third)
            assertEquals(serial, activity.lastSerial)
            assertEquals(activity.sessionId, activity.lastLaunch!!.first)
        } finally { ui { activity.finish() } }
    }
}
