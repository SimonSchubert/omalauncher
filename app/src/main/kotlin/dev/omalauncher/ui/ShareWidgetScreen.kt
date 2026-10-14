package dev.omalauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.omalauncher.BuildConfig
import dev.omalauncher.widgets.Market
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.Review
import dev.omalauncher.widgets.market.Paste
import dev.omalauncher.widgets.market.Share
import dev.omalauncher.widgets.market.ShareError
import dev.omalauncher.widgets.market.Submit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares a widget, the two ways there are:
 *
 * - as a QR code and a link (widgets/market/Share.kt): another phone scans the code and its launcher
 *   shows what the widget runs before adding it; Omarchy's Widgets app takes the same link. The whole
 *   file is in the link, after the `#`, so nothing is uploaded anywhere.
 * - to the widget store, for everyone: as an issue from the browser (widgets/market/Submit.kt),
 *   or [onPublish] hands it to the default agent, which opens a pull request and can add previews too.
 *   Null for a widget that isn't the user's own (from the store, or shared with them).
 *
 * [onCheck] has the default agent look for keys, tokens and anything personal in it first.
 */
@Composable
fun ShareWidgetScreen(
    spec: WidgetSpec,
    onPublish: (() -> Unit)?,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = spec.file
    val name = spec.name
    // Reading, minifying, compressing and encoding take a moment on a big widget: off the main thread.
    val prepared by produceState<Prepared?>(null, file) { value = withContext(Dispatchers.Default) { prepare(file) } }
    // Too big for one code: uploaded on request, the code holds a short link to it.
    var pasted by remember(file) { mutableStateOf<BitMatrix?>(null) }
    var uploading by remember(file) { mutableStateOf(false) }
    var uploadError by remember(file) { mutableStateOf<String?>(null) }
    var copied by remember(file) { mutableStateOf(false) }
    var showSource by remember(file) { mutableStateOf(false) }

    OverlayPanel("share $name", onDismiss, contentWidth = ReadingWidth) {
        val prepared = prepared ?: return@OverlayPanel
        val text = prepared.text
        val link = prepared.link
        val code = pasted ?: prepared.code
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // What goes out, first: keys or anything personal in it go along. Like the screen that receives it.
            if (text != null) {
                FlowRow {
                    BracketAction(if (showSource) "hide the file" else "show the whole file", Palette.accent) { showSource = !showSource }
                    // The agent's check comes with your own look, not in place of it.
                    if (showSource) BracketAction("check for secrets with agent", Palette.accent, onCheck)
                }
                if (showSource) {
                    BasicText(
                        text,
                        Modifier.fillMaxWidth().background(Palette.surface).horizontalScroll(rememberScrollState()).padding(6.dp),
                        style = Mono.copy(fontSize = TextSize.Small),
                    )
                }
            }
            when {
                text == null -> BasicText("can't read ${file.name}", style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
                link == null -> BasicText("${file.name} is over ${Review.MAX_BYTES / 1024} KB, more than a launcher takes", style = Mono.copy(color = Palette.red, fontSize = TextSize.Body))
                else -> {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val square = Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)
                        if (code != null) {
                            // Black on white whatever the theme: what cameras read best.
                            QrCode(code, square)
                        } else {
                            // Where the code goes once it's uploaded: a stand-in, faded, under the one thing to do.
                            Box(square, contentAlignment = Alignment.Center) {
                                QrCode(prepared.placeholder!!, Modifier.fillMaxSize().alpha(0.12f), dark = Palette.foreground, light = Color.Transparent)
                                Column(
                                    Modifier
                                        .clip(LauncherStyle.shape(8.dp))
                                        .background(Palette.surface)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    BracketAction(if (uploading) "uploading…" else "get QR code", Palette.accent) {
                                        if (uploading) return@BracketAction
                                        uploading = true
                                        uploadError = null
                                        scope.launch {
                                            runCatching {
                                                val id = withContext(Dispatchers.IO) { Paste.upload(Share.minify(text), userAgent()) }
                                                withContext(Dispatchers.Default) { qrCode(Share.pasteLink(id)) }
                                            }.onSuccess { pasted = it }
                                                .onFailure { uploadError = (it as? ShareError)?.message ?: "something went wrong" }
                                            uploading = false
                                        }
                                    }
                                    BasicText("uploads it, public, to ${Paste.HOST} for ${Paste.DAYS} days", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                                    uploadError?.let { BasicText("✗ $it", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
                                }
                            }
                        }
                    }
                    BasicText(
                        when {
                            code == null -> "Or send the link: permanent, the whole widget is in it."
                            pasted != null -> "Links to ${Paste.HOST}, gone in ${Paste.DAYS} days. The link below is permanent."
                            else -> "Permanent: the whole widget is in the image."
                        },
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow {
                        if (code != null) {
                            BracketAction("share QR code", Palette.accent) {
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) { qrImage(context, code, Submit.slug(name)) }
                                    val send =
                                        Intent(Intent.ACTION_SEND)
                                            .setType("image/png")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .putExtra(Intent.EXTRA_SUBJECT, "$name widget")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    // The chooser's preview reads the image through the clip.
                                    send.clipData = ClipData.newRawUri("$name QR code", uri)
                                    context.startActivity(Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                        BracketAction("share link", Palette.accent) {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link).putExtra(Intent.EXTRA_SUBJECT, "$name widget")
                            context.startActivity(Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        BracketAction(if (copied) "copied" else "copy link", Palette.foreground) {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("$name widget", link))
                            copied = true
                        }
                    }
                    BasicText(
                        "Omarchy: omarchy-widgets import '<link>'",
                        style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }

            if (onPublish != null) {
                Spacer(Modifier.height(16.dp))
                BasicText("in the widget store", style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title))
                BasicText(
                    "An issue on github.com/${Market.REPO}; once reviewed, anyone can install it.",
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
                if (text != null) StoreIssue(spec, text)
                BasicText("Or a pull request from your agent, which can add a preview too:", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                BracketAction("create pull request with agent", Palette.accent, onPublish)
            }
        }
    }
}

/** What the share screen shows, worked out off the main thread; nulls past the first that can't be had. */
private class Prepared(
    val text: String?,
    val link: String?,
    /** The code holding the whole widget, when it fits in one. */
    val code: BitMatrix?,
    /** When it doesn't: a stand-in the size a pasted link's code comes out at, for what will replace it. */
    val placeholder: BitMatrix?,
)

private fun prepare(file: File): Prepared {
    // Without page and order: where it sits on this home screen isn't part of the widget.
    val text = runCatching { Share.prepare(file.readText()) }.getOrNull() ?: return Prepared(null, null, null, null)
    if (text.toByteArray(Charsets.UTF_8).size > Review.MAX_BYTES) return Prepared(text, null, null, null)
    // Without comments or indentation, the smallest it gets; the launcher it's shared with lays it out again.
    val link = Share.link(Share.minify(text))
    return if (Share.fits(link)) Prepared(text, link, qrCode(link), null) else Prepared(text, link, null, qrCode(Share.pasteLink("XXXXXXXXX")))
}

/**
 * The store from the browser, for someone without an agent or a GitHub username at hand: the file goes to
 * [Paste] (with its comments, for whoever reviews it) and GitHub's new-issue page opens linking to it.
 * Points out first what the store's review would turn down.
 */
@Composable
private fun StoreIssue(
    spec: WidgetSpec,
    text: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The folder is the maintainer's to pick; only the rest of the review applies.
    val review by produceState<Review?>(null, text) {
        value = withContext(Dispatchers.Default) { Review("widgets/you/${Submit.slug(spec.name)}/widget.owl", text) }
    }
    var uploading by remember(text) { mutableStateOf(false) }
    var error by remember(text) { mutableStateOf<String?>(null) }

    val problems = (review ?: return).errors.filterNot { it.startsWith("widgets go at") }
    for (p in problems) BasicText("✗ $p", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
    if (problems.isNotEmpty()) return
    BracketAction(if (uploading) "uploading…" else "open GitHub issue", Palette.accent) {
        if (uploading) return@BracketAction
        uploading = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Paste.upload(text, userAgent(), Paste.STORE_DAYS) } }
                .onSuccess { id ->
                    val url = Submit.issueUrl(Market.REPO, spec.name, Catalog.metaText(spec.program?.meta?.get("description")).orEmpty(), id)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { error = (it as? ShareError)?.message ?: "something went wrong" }
            uploading = false
        }
    }
    BasicText("uploads it, public, to ${Paste.HOST} for review", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
    error?.let { BasicText("✗ couldn't upload: $it", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small)) }
}

/** How the launcher names itself to [Paste]'s host, which asks clients to. */
internal fun userAgent() = "Omalauncher/${BuildConfig.VERSION_NAME} (Android; +https://play.google.com/store/apps/details?id=dev.omalauncher)"

/** [code] as a PNG in the cache, 8 pixels a module, for other apps to read through the FileProvider. */
private fun qrImage(
    context: Context,
    code: BitMatrix,
    slug: String,
): Uri {
    val scale = 8
    val bitmap = Bitmap.createBitmap(code.width * scale, code.height * scale, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(bitmap.width * bitmap.height)
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            pixels[y * bitmap.width + x] = if (code[x / scale, y / scale]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "$slug-qr.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return FileProvider.getUriForFile(context, context.packageName + ".files", file)
}

/** [link] as a QR code at error correction L, the setting [Share.MAX_LINK] is measured for, with a 2-module margin. */
internal fun qrCode(link: String): BitMatrix =
    QRCodeWriter().encode(
        link,
        BarcodeFormat.QR_CODE,
        0,
        0,
        // No CHARACTER_SET: naming one, even the default, adds an ECI header, a byte less than MAX_LINK. Links are ASCII.
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2),
    )

@Composable
private fun QrCode(
    code: BitMatrix,
    modifier: Modifier = Modifier,
    dark: Color = Color.Black,
    light: Color = Color.White,
) {
    Canvas(modifier.background(light)) {
        val module = size.width / code.width
        for (y in 0 until code.height) {
            for (x in 0 until code.width) {
                if (code[x, y]) drawRect(dark, Offset(x * module, y * module), Size(module + 0.5f, module + 0.5f))
            }
        }
    }
}
