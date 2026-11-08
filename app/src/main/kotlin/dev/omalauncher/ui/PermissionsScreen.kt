package dev.omalauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.omalauncher.LocalNetwork
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.hasPermission

/** One thing Android lets the launcher do, as a row: whether it may, and the way to switch it. */
private class Permission(
    @StringRes val title: Int,
    val uses: String,
    val granted: Boolean,
    val turnOn: () -> Unit,
    val turnOff: () -> Unit,
)

/**
 * Every permission the launcher has, on or off, from settings. Android keeps the switches: turning one on asks with
 * its dialog or opens its page, and turning one off always opens its page, as an app can't give one back itself.
 * Only what this Android version has is listed. Each is read again as the screen comes back, since that's where
 * they change.
 */
@Composable
fun PermissionsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Bumped to read every grant again: back from Android's settings, or a dialog answered.
    var tick by remember { mutableIntStateOf(0) }
    val changed = {
        tick++
        runtime.location.recheck()
        runtime.calendar.recheck()
        runtime.widgets.airpods.recheck()
    }
    LifecycleResumeEffect(Unit) {
        changed()
        onPauseOrDispose {}
    }
    val answered = { _: Boolean -> changed() }
    val appDetails: () -> Unit = { runCatching { context.startActivity(appDetails(context)) } }
    val notificationSettings =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val askMic = rememberAskPermission(Manifest.permission.RECORD_AUDIO, then = answered)
    val askLocation = rememberAskPermission(Manifest.permission.ACCESS_COARSE_LOCATION, then = answered)
    val askCalendar = rememberAskPermission(Manifest.permission.READ_CALENDAR, then = answered)

    @SuppressLint("InlinedApi")
    val askBluetooth = rememberAskPermission(Manifest.permission.BLUETOOTH_CONNECT, then = answered)

    @SuppressLint("InlinedApi")
    val askNotifications = rememberAskPermission(Manifest.permission.POST_NOTIFICATIONS, notificationSettings, answered)
    val askLocalNetwork = rememberAskPermission(LocalNetwork.PERMISSION, then = answered)

    OverlayPanel(stringResource(R.string.permissions_title), onDismiss, contentWidth = ReadingWidth) {
        tick
        val sdk = Build.VERSION.SDK_INT
        val notifications = context.getSystemService(NotificationManager::class.java)
        val rows =
            listOfNotNull(
                Permission(
                    R.string.permissions_mic,
                    stringResource(R.string.permissions_mic_uses),
                    context.hasPermission(Manifest.permission.RECORD_AUDIO),
                    askMic,
                    appDetails,
                ),
                Permission(
                    R.string.permissions_location,
                    stringResource(R.string.permissions_location_uses),
                    context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
                    askLocation,
                    appDetails,
                ),
                Permission(
                    R.string.permissions_calendar,
                    stringResource(R.string.permissions_calendar_uses),
                    context.hasPermission(Manifest.permission.READ_CALENDAR),
                    askCalendar,
                    appDetails,
                ),
                // Before Android 12, Bluetooth is an install-time permission without a switch.
                if (sdk >= Build.VERSION_CODES.S) {
                    Permission(
                        R.string.permissions_bluetooth,
                        stringResource(R.string.permissions_bluetooth_uses),
                        context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT),
                        askBluetooth,
                        appDetails,
                    )
                } else {
                    null
                },
                // Before Android 13 there's no dialog, only the launcher's notification page.
                Permission(
                    R.string.permissions_notifications,
                    stringResource(R.string.permissions_notifications_uses),
                    notifications.areNotificationsEnabled(),
                    turnOn = {
                        @SuppressLint("InlinedApi")
                        val ask = sdk >= Build.VERSION_CODES.TIRAMISU && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                        if (ask) askNotifications() else runCatching { context.startActivity(notificationSettings) }
                    },
                    turnOff = { runCatching { context.startActivity(notificationSettings) } },
                ),
                if (sdk >= Build.VERSION_CODES.CINNAMON_BUN) {
                    Permission(
                        R.string.settings_local_network,
                        stringResource(R.string.settings_local_network_subtitle),
                        !LocalNetwork.missing(context),
                        askLocalNetwork,
                        appDetails,
                    )
                } else {
                    null
                },
                Permission(
                    R.string.permissions_notification_access,
                    stringResource(R.string.permissions_notification_access_uses),
                    runtime.widgets.media.granted,
                    runtime.widgets.media::openAccessSettings,
                    runtime.widgets.media::openAccessSettings,
                ),
            )
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // For Files and Linux, so only with Linux there.
            if (runtime.bootstrap.wanted) PhoneFoldersRow(runtime.phoneFolders)
            BasicText(stringResource(R.string.permissions_note), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            for (row in rows) PermissionRow(row)
        }
    }
}

@Composable
private fun PermissionRow(row: Permission) {
    SwitchRow(stringResource(row.title), stringResource(if (row.granted) R.string.permissions_on else R.string.permissions_off, row.uses), row.granted, row.turnOn, row.turnOff)
}

/** Something to turn on or off, as a card: [title], [status] in green while [on], and the button for the other way. */
@Composable
internal fun SwitchRow(
    title: String,
    status: String,
    on: Boolean,
    turnOn: () -> Unit,
    turnOff: () -> Unit,
) {
    ActionsRow(
        Modifier.card(),
        actions = {
            if (on) {
                BracketAction(stringResource(R.string.linux_gpu_turn_off), Palette.red, onClick = turnOff)
            } else {
                BracketAction(stringResource(R.string.linux_gpu_turn_on), Palette.accent, onClick = turnOn)
            }
        },
    ) {
        Column {
            BasicText(title, style = Mono.copy(fontSize = TextSize.Item))
            BasicText(status, style = Mono.copy(color = if (on) Palette.green else Palette.muted, fontSize = TextSize.Small))
        }
    }
}
