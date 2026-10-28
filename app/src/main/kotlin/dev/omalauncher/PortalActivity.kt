package dev.omalauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.omalauncher.linux.AndroidPortals
import dev.omalauncher.linux.PortalWire
import java.lang.ref.WeakReference

/** Non-exported trampoline: only a live, UID-checked socket request can open UI. */
class PortalActivity : ComponentActivity() {
    private val portals get() = (application as App).runtime.portals
    private var request: AndroidPortals.Request? = null
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Selection is acknowledged separately, as soon as Android's chooser
        // calls its IntentSender; this result is the chooser being cancelled.
        request?.let { if (!it.finished) it.finish(1) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val r = portals.get(intent.getStringExtra("request")) ?: return finish()
        request = r; r.activity = WeakReference(this)
        if (state != null) return // Do not launch the chooser twice after rotation.
        try {
            when (r.fields["operation"]) {
                "open-uri" -> {
                    val value = r.fields["uri"].orEmpty()
                    require(PortalWire.safeUri(value)) { "Unsupported URI scheme" }
                    launch(r, AndroidPortals.viewIntent(value), r.fields["ask"] == "1", "Open with")
                }
                "open-file" -> {
                    // There is no document-portal export authority here. The
                    // writable hint alone must not manufacture a write grant.
                    val uri = r.share(r.fds.single())
                    val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    open.clipData = ClipData.newRawUri("Document", uri)
                    launch(r, open, r.fields["ask"] == "1", "Open document")
                }
                "email" -> composeEmail(r)
                else -> r.finish(2)
            }
        } catch (e: Exception) {
            android.util.Log.w("portal", "cannot launch portal action", e)
            r.finish(2)
        }
    }

    private fun composeEmail(r: AndroidPortals.Request) {
        val to = PortalWire.emailRecipients(r.fields)
        val uris = ArrayList(r.fds.map { r.share(it) })
        val mail = if (uris.isEmpty()) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) else {
            Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri("Attachments", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        mail.putExtra(Intent.EXTRA_EMAIL, to)
        mail.putExtra(Intent.EXTRA_CC, PortalWire.emailAddresses(r.fields, "cc"))
        mail.putExtra(Intent.EXTRA_BCC, PortalWire.emailAddresses(r.fields, "bcc"))
        r.fields["subject"]?.let { mail.putExtra(Intent.EXTRA_SUBJECT, it) }
        r.fields["body"]?.let { mail.putExtra(Intent.EXTRA_TEXT, it) }
        launch(r, mail, true, "Compose email")
    }

    private fun launch(r: AndroidPortals.Request, action: Intent, ask: Boolean, title: String) {
        if (!ask) { startActivity(action); r.finish(0); return }
        val callback = PendingIntent.getBroadcast(this, 0,
            Intent(this, PortalChoiceReceiver::class.java).setData(Uri.parse("portal-choice://${r.id}")).putExtra("request", r.id),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        r.chooserCallback = callback
        chooser.launch(Intent.createChooser(action, title, callback.intentSender))
    }

    override fun onDestroy() {
        val r = request
        if (!isChangingConfigurations && r?.activity?.get() === this && !r.finished) r.cancel()
        super.onDestroy()
    }
}

/** Explicit PendingIntent target; not callable by other apps. */
class PortalChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val portals = (context.applicationContext as App).runtime.portals
        if (intent.action == "portal-dismiss") portals.dismissNotification(intent.getStringExtra("request"))
        else portals.selected(intent.getStringExtra("request"))
    }
}

/** User-started activity PendingIntent, not a forbidden notification trampoline
 * broadcast. Keeps Linux window activation associated with a real user click. */
class PortalNotificationActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        (application as App).runtime.portals.notificationAction(intent.getStringExtra("request"), intent.getIntExtra("index", 0))
        finish()
    }
}
