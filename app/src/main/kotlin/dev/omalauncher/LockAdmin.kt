package dev.omalauncher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Makes the launcher the phone's device admin for one thing: locking the screen from a home gesture (double tap by
 * default). It takes no other policy, and a launcher that isn't admin asks Android to make it one when the screen is
 * first meant to lock, rather than at install.
 */
class LockAdmin : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context) = ComponentName(context, LockAdmin::class.java)

        /** Whether Android lets the launcher lock the screen. */
        fun active(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(context)) == true

        /** Locks the screen now; until the launcher is the phone's device admin, opens Android's screen to allow it. */
        fun lock(context: Context) {
            val policy = context.getSystemService(DevicePolicyManager::class.java)
            if (policy != null && policy.isAdminActive(component(context))) policy.lockNow() else allow(context)
        }

        /** Android's screen to make the launcher the phone's device admin, saying what it's for. */
        fun allow(context: Context) {
            context.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.lock_admin_why))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
