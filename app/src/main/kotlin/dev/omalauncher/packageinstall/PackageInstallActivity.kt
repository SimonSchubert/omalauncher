package dev.omalauncher.packageinstall

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.App
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.PackageInstallScreen
import dev.omalauncher.ui.SystemBarsEffect
import dev.omalauncher.ui.applySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-owned approval screen for an APK requested by the Linux guest. */
class PackageInstallActivity : androidx.activity.ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private var request by mutableStateOf<PendingPackageInstall?>(null)
    private var allowed by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        request = requestId?.let(runtime.packageInstalls::pending)
        if (request == null) {
            finish()
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = reject()
        })
        allowed = runtime.packageInstalls.canRequestPackageInstalls()
        applySystemBars(this, Palette.isLight)
        setContent {
            SystemBarsEffect(this@PackageInstallActivity)
            PackageInstallScreen(
                request = request!!,
                allowed = allowed,
                error = error,
                onApprove = ::approve,
                onReject = ::reject,
                onAllowSource = ::allowSource,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        allowed = runtime.packageInstalls.canRequestPackageInstalls()
    }

    private fun approve() {
        if (!allowed) {
            allowSource()
            return
        }
        val id = requestId ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runtime.packageInstalls.approve(id) }
            when (result) {
                PackageInstallApproval.Started -> finish()
                is PackageInstallApproval.Rejected -> error = result.message
            }
        }
    }

    private fun reject() {
        requestId?.let(runtime.packageInstalls::reject)
        finish()
    }

    private fun allowSource() {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
