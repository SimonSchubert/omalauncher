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

    @Test fun oneApprovedHostCanUsePrimaryAndSecondaryWindowsWithIndependentState() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HostActivity::class.java)
            .putExtra("captureDisplay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        var primary: dev.omalauncher.desktop.IDesktopSession? = null
        var phoneSurface: android.view.SurfaceView? = null
        try {
            await("provider binding") { activity.bridge != null }
            val needsConsent = activity.bridge!!.negotiate(1).getString("status") == "consent_required"
            ui { activity.performAppLaunch = false; activity.connect() }
            if (needsConsent) {
                val allow = UiDevice.getInstance(instrumentation).findObject(UiSelector().textMatches("(?i)allow"))
                assertTrue(allow.waitForExists(10_000)); assertTrue(allow.click())
            }
            await("external home") { activity.session != null && activity.frameColors > 8 }
            val external = activity.session!!
            ui {
                phoneSurface = android.view.SurfaceView(activity)
                activity.addContentView(phoneSurface, android.view.ViewGroup.LayoutParams(640, 480))
            }
            await("primary window token") { phoneSurface?.hostToken != null }
            val callback = object : IDesktopHost.Stub() {
                override fun onAndroidLaunch(id: String, component: ComponentName, user: UserHandle, serial: Long) = Unit
                override fun onStatus(id: String, status: String, message: String) = Unit
            }
            val metrics = Bundle().apply {
                putInt("displayId", 0); putInt("width", 640); putInt("height", 480); putString("profile", "primary_window")
            }
            val result = activity.bridge!!.openSession(1, metrics, phoneSurface!!.hostToken, callback)
            assertEquals("connected", result.getString("status"))
            primary = dev.omalauncher.desktop.IDesktopSession.Stub.asInterface(result.getBinder("session"))
            val child = primary!!.getSurface()
            ui { phoneSurface!!.setChildSurfacePackage(child) }
            assertEquals(0, primary!!.describe().getInt("displayId"))
            assertEquals("host", primary!!.describe().getString("launchMode"))
            external.search("external-only", false)
            primary!!.search("primary-only", false)
            assertEquals("external-only", external.describe().getString("query"))
            assertEquals("primary-only", primary!!.describe().getString("query"))
            assertEquals("busy", activity.bridge!!.openSession(1, metrics, phoneSurface!!.hostToken, callback).getString("status"))
            primary!!.setVisible(false)
            assertTrue(external.describe().getBoolean("overlay"))
            assertFalse(primary!!.describe().getBoolean("overlay"))
        } finally {
            runCatching { primary?.close() }
            ui { (phoneSurface?.parent as? android.view.ViewGroup)?.removeView(phoneSurface); activity.finish() }
        }
    }

    @Test fun systemLaunchModeUsesAndroidOnTheSessionDisplayWithoutHostEmbedding() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HostActivity::class.java)
            .putExtra("captureDisplay", true).putExtra("trustedDisplay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        var system: dev.omalauncher.desktop.IDesktopSession? = null
        try {
            await("provider binding") { activity.bridge != null }
            val needsConsent = activity.bridge!!.negotiate(1).getString("status") == "consent_required"
            ui { activity.performAppLaunch = false; activity.connect() }
            if (needsConsent) {
                val allow = UiDevice.getInstance(instrumentation).findObject(UiSelector().textMatches("(?i)allow"))
                assertTrue(allow.waitForExists(10_000)); assertTrue(allow.click())
            }
            await("external home") { activity.session != null && activity.frameColors > 8 }
            val displayId = activity.session!!.describe().getInt("displayId")
            val target = activity.javaClass.getDeclaredField("surface").apply { isAccessible = true }.get(activity) as android.view.SurfaceView
            var hostLaunch = false
            val callback = object : IDesktopHost.Stub() {
                override fun onAndroidLaunch(id: String, component: ComponentName, user: UserHandle, serial: Long) { hostLaunch = true }
                override fun onStatus(id: String, status: String, message: String) = Unit
            }
            val metrics = Bundle().apply {
                putInt("displayId", displayId); putInt("width", 1280); putInt("height", 720)
                putString("profile", "system_window"); putString("launchMode", "system")
            }
            val result = activity.bridge!!.openSession(1, metrics, target.hostToken, callback)
            assertEquals("connected", result.getString("status"))
            system = dev.omalauncher.desktop.IDesktopSession.Stub.asInterface(result.getBinder("session"))
            assertEquals("system", system!!.describe().getString("launchMode"))
            system!!.search("desktop bridge launch fixture", true)
            assertNull("System launch must report Android refusal rather than silently reroute", system!!.describe().getString("message"))
            await("Android launches the separate fixture app on the desktop display") {
                val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities"))
                    .use { String(it.readBytes()) }
                dump.substringAfter("Display #$displayId ", "").substringBefore("Display #").contains("DesktopLaunchFixtureActivity")
            }
            assertFalse("System routing must not also ask the host to launch", hostLaunch)
        } finally {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am force-stop --user 0 ${BuildConfig.PROVIDER_PACKAGE}.test")).use { it.readBytes() }
            runCatching { system?.close() }; ui { activity.finish() }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
