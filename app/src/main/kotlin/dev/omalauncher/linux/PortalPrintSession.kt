package dev.omalauncher.linux

import android.app.Activity
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintJob
import android.print.PrintManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One completed CUPS-PDF document -> Android's real print services. No job is
 * reported successful just because the print chooser opened or spooler queued it. */
internal class PortalPrintSession(private val request: AndroidPortals.Request) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val host = PortalPrintHost<Activity>()
    private var source: ParcelFileDescriptor? = null
    private var job: PrintJob? = null
    private var poll: Runnable? = null

    fun start(activity: Activity) {
        if (!attachHost(activity)) { request.finish(2); return }
        scope.launch {
            var preparing: ParcelFileDescriptor? = null
            try {
                require(request.fds.size == 1) { "Print needs one PDF descriptor" }
                val prepared = PortalFiles.readOnly(request.fds.single())
                preparing = prepared
                val dimensions = PdfRenderer(PortalFiles.readOnly(prepared)).use { pdf ->
                    require(pdf.pageCount > 0) { "Empty PDF" }
                    pdf.openPage(0).use { page -> Triple(pdf.pageCount, page.width, page.height) }
                }
                handler.post {
                    if (closed.get() || request.finished || !host.owns(activity)) { prepared.close(); return@post }
                    source = prepared
                    try {
                        val title = request.fields["title"].orEmpty().ifBlank { "Linux document" }
                        val info = PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(dimensions.first).build()
                        val adapter = object : PrintDocumentAdapter() {
                            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
                                if (closed.get() || cancellationSignal.isCanceled) callback.onLayoutCancelled()
                                else callback.onLayoutFinished(info, oldAttributes != newAttributes)
                            }
                            override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
                                scope.launch {
                                    try {
                                        ParcelFileDescriptor.AutoCloseInputStream(PortalFiles.readOnly(requireNotNull(source))).use { input ->
                                            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                                                val buffer = ByteArray(64 * 1024)
                                                while (!closed.get() && !cancellationSignal.isCanceled) {
                                                    val count = input.read(buffer)
                                                    if (count < 0) break
                                                    output.write(buffer, 0, count)
                                                }
                                            }
                                        }
                                        handler.post {
                                            if (closed.get() || cancellationSignal.isCanceled) callback.onWriteCancelled()
                                            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                                        }
                                    } catch (e: Exception) {
                                        runCatching { destination.close() }
                                        handler.post {
                                            if (closed.get() || cancellationSignal.isCanceled) callback.onWriteCancelled()
                                            else callback.onWriteFailed("Cannot read the Linux PDF")
                                        }
                                    }
                                }
                            }
                            // onFinish means the adapter is no longer needed, not
                            // that the physical/service print job succeeded.
                            override fun onFinish() { host.adapterFinished(); checkJob() }
                        }
                        val media = PrintAttributes.MediaSize("linux-pdf", "Linux document", (dimensions.second * 1000L / 72).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(), (dimensions.third * 1000L / 72).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
                        val attributes = PrintAttributes.Builder().setMediaSize(media).setColorMode(PrintAttributes.COLOR_MODE_COLOR).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
                        job = activity.getSystemService(PrintManager::class.java).print(title, adapter, attributes)
                            ?: error("Android print service did not create a job")
                        checkJob()
                    } catch (e: Exception) { Log.w("PortalPrint", "Cannot open Android print service", e); request.finish(2) }
                }
                preparing = null // The main-thread callback now owns this descriptor.
            } catch (e: Exception) {
                runCatching { preparing?.close() }
                Log.w("PortalPrint", "Cannot read completed CUPS-PDF document", e)
                handler.post { if (!request.finished) request.finish(2) }
            }
        }
    }

    fun attachHost(activity: Activity): Boolean = host.attach(activity)

    fun hostDestroyed(activity: Activity) {
        if (host.destroyed(activity) && !request.finished) request.finish(2)
    }

    private fun checkJob() {
        if (closed.get() || request.finished) return
        val current = job ?: return
        when {
            current.isCompleted -> request.finish(0)
            current.isCancelled -> request.finish(1)
            current.isFailed -> request.finish(2)
            else -> {
                poll?.let(handler::removeCallbacks)
                poll = Runnable { checkJob() }.also { handler.postDelayed(it, 500) }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        host.close()
        poll?.let(handler::removeCallbacks); poll = null
        runCatching { job?.let { if (!it.isCompleted && !it.isCancelled && !it.isFailed) it.cancel() } }
        scope.cancel()
        runCatching { source?.close() }; source = null
        job = null
    }
}
