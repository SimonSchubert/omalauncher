package dev.omalauncher.desktop

/** A separate test-APK app, so launch acceptance never opens or closes the user's apps. */
class DesktopLaunchFixtureActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text = "Android desktop launch fixture" })
    }
}
