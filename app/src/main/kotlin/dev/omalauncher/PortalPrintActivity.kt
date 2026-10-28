package dev.omalauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.omalauncher.linux.AndroidPortals
import dev.omalauncher.linux.PortalPrintSession
import java.lang.ref.WeakReference

/** Android's PrintManager requires an Activity, not a background service. The
 * session itself survives configuration changes and watches the real PrintJob. */
class PortalPrintActivity : ComponentActivity() {
    private var request: AndroidPortals.Request? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = (application as App).runtime.portals.get(intent.getStringExtra("request"))
        if (r == null || r.finished || r.fields["operation"] != "print") { finish(); return }
        request = r; r.activity = WeakReference(this)
        if (r.printSession == null) {
            val session = PortalPrintSession(r)
            r.printSession = session
            session.start(this)
        } else if (!r.printSession!!.attachHost(this)) r.finish(2)
    }
    override fun onDestroy() {
        request?.printSession?.hostDestroyed(this)
        super.onDestroy()
    }
}
