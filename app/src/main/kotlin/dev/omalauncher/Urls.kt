package dev.omalauncher

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens [url] in whatever app handles it. False when none does (no browser, an unknown scheme). */
fun Context.openUrl(url: String): Boolean =
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

/** Puts [text] on the clipboard, [label] saying what it is. */
fun Context.copyText(
    label: String,
    text: String,
) = getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

/** Android's share sheet for [text], to send it to any app. */
fun Context.shareText(
    text: String,
    subject: String? = null,
    chooserTitle: String? = null,
) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
    startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
