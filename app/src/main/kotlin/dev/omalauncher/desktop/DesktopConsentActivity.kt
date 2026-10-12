package dev.omalauncher.desktop

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.App
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.OverlayPanel
import dev.omalauncher.ui.Palette

/** Provider-owned approval UI. The immutable PendingIntent carries only an expiring server-side request. */
class DesktopConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val access = (application as App).desktopAccess
        val token = intent.data?.takeIf { it.scheme == "omalauncher-consent" }?.schemeSpecificPart
        if (token == null) {
            setContent { ApprovedDesktops(access, ::finish) }
            return
        }
        val identity = access.request(token)
        if (identity == null) {
            Toast.makeText(this, "Desktop request expired. Connect again from the desktop host.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(identity.packageName, 0)) }.getOrDefault(identity.packageName)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Allow external desktop?")
            .setMessage("$label\n${identity.packageName}\n\nThis app can display your widgets and themes, interact with widget data and actions, and request app launches. Linux and agent windows are unavailable through this bridge. You can revoke access in settings → external desktops.\n\nSigning certificate: ${identity.certificate}")
            .setPositiveButton("Allow") { _, _ ->
                if (!access.grant(token)) Toast.makeText(this, "Desktop request expired. Connect again.", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton("Deny") { _, _ -> access.deny(token); finish() }
            .setOnCancelListener { access.deny(token); finish() }
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true }
        dialog.show()
    }
}

@Composable
private fun ApprovedDesktops(access: DesktopAccess, dismiss: () -> Unit) {
    OverlayPanel("external desktops", dismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (access.approvedPackages.isEmpty()) BasicText("No approved desktop hosts", style = Mono)
            for (packageName in access.approvedPackages.toList()) {
                BasicText("$packageName  [revoke]", Modifier.fillMaxWidth().clickable { access.revoke(packageName) }.padding(12.dp), style = Mono.copy(color = Palette.accent))
            }
        }
    }
}
