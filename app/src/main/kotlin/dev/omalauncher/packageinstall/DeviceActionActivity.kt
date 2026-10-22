package dev.omalauncher.packageinstall

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.App
import dev.omalauncher.ui.DeviceActionScreen
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.SystemBarsEffect
import dev.omalauncher.ui.applySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-owned approval screen for a guest-requested Android action. */
class DeviceActionActivity : androidx.activity.ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private var request by mutableStateOf<PendingDeviceAction?>(null)
    private var error by mutableStateOf<String?>(null)
    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        request = requestId?.let(runtime.deviceActions::pending)
        if (request == null) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = reject()
        })
        applySystemBars(this, Palette.isLight)
        setContent {
            SystemBarsEffect(this@DeviceActionActivity)
            DeviceActionScreen(
                request = request!!,
                error = error,
                onApprove = ::approve,
                onReject = ::reject,
            )
        }
    }

    private fun approve() {
        val id = requestId ?: return
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) { runtime.deviceActions.approve(id) }) {
                DeviceActionResult.Started -> finish()
                is DeviceActionResult.Rejected -> error = result.message
            }
        }
    }

    private fun reject() {
        requestId?.let(runtime.deviceActions::reject)
        finish()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
