package dev.omalauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.BuildConfig
import dev.omalauncher.openUrl
import dev.omalauncher.widgets.render.LocalHomeCovered
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Web pages in home-screen tiles (the widgets' `Browser` node), drawn by
 * Android's own WebView inside the tile: no guest, no window manager, no
 * Linux. Each tile keeps its view while its page is off screen, so switching
 * tabs comes back to the page where it was; the widget's removal, or a change
 * to its url, ends it.
 *
 * What a page may reach is decided here and nowhere else: script and DOM
 * storage only with `js: true`, no file or content access, no geolocation, no
 * popups, no media that plays on its own, and nothing but https in the page
 * itself. Its tile is inert until tapped, as a terminal's is: the tap, a tap
 * outside and Back are [TerminalTiles]'. Main thread only.
 */
class EmbeddedBrowser internal constructor(
    /** The home screen's input owner, which owns the tile's taps; null for a page no home tile draws, as a test's. */
    internal val tiles: TerminalTiles?,
    /** The https URL the node asked for; a tile whose url changes gets a new handle. */
    val url: String?,
    /** Whether the page may run script; with it, DOM storage too. */
    val js: Boolean,
    /** The page's text size as a multiple of the page's own. */
    val zoom: Double,
) {
    /** The page's title as it loads; a blank one is ignored, so the last real title stays. */
    var title by mutableStateOf<String?>(null)
        private set

    /** How much of the page has loaded, 0 to 1. */
    var progress by mutableStateOf(0f)
        private set

    /** The URL showing now, which follows a link the page opened. */
    var currentUrl by mutableStateOf(url.orEmpty())
        private set

    /** Whether the page has a history to go back to, kept in state so a Back can read it as it's drawn. */
    var canGoBack by mutableStateOf(false)
        private set

    /** Told when the page's title changes; the widget's onTitle. */
    var onTitle: ((String) -> Unit)? = null

    /** Told when the page goes somewhere else; the widget's onNavigate. */
    var onNavigate: ((String) -> Unit)? = null

    /** Told how much has loaded; the widget's onProgress. */
    var onProgress: ((Double) -> Unit)? = null

    /** The view, kept while the tile is off screen; null until the tile first shows or once closed. */
    private var view: TileWebView? = null

    /**
     * The holder that has the page now, one token per holder a tile is drawn in. A holder that lets go of the page
     * after another one has taken it leaves it where it is: taking it out of that holder would leave a page with no
     * size in the window, which never draws again.
     */
    private var holder: Any? = null

    private var closed = false

    /** Whether the tile can be seen: home is in front, nothing covers it, and its page of home is the one showing. */
    private var seen = true

    /** Where the tile is, in window pixels, as if the home screen weren't lifted. */
    internal var bounds = Rect.Zero

    /**
     * The WebView this tile draws in: the one this handle already has (a tab switched back to), or a new one set up
     * for this node. It goes into [host], the holder's own box, so the page is the only view that moves as a tile
     * comes and goes.
     */
    internal fun attach(
        host: ViewGroup,
        holder: Any,
    ): TileWebView? {
        // A page closed while its tile still shows (a third page evicted it) stays empty: a view made for it now would
        // belong to no widget, and nothing would ever destroy it.
        if (closed) return null
        val v =
            view ?: create(host.context)?.also {
                view = it
                BrowserPages.live += this
                load()
            } ?: return null
        if (this.holder === holder && v.parent === host) return v
        (v.parent as? ViewGroup)?.removeView(v)
        this.holder = holder
        // In once the box has been laid out: a view added while the tile is still being measured is drawn at the place
        // and size the tile had before the row above the page was measured, and goes on drawing there.
        host.post {
            if (this.holder !== holder || v.parent === host) return@post
            host.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Back on screen: a paused page's animations run again, unless home is covered or stopped.
            if (seen && BrowserPages.started) v.onResume()
        }
        return v
    }

    /**
     * A WebView set up for this node, or null while the System WebView can't make one: it throws while its package is
     * being updated, and a tile that can't show its page mustn't take the launcher down with it. The next attach tries again.
     */
    private fun create(context: Context): TileWebView? =
        try {
            TileWebView(context).also {
                configure(it)
                // Timers are paused for every WebView at once: one made after the last page went leaves them paused.
                if (BrowserPages.started) it.resumeTimers() else it.pauseTimers()
                it.webViewClient = Client()
                it.webChromeClient = Chrome()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "no WebView for a Browser tile", e)
            null
        }

    /**
     * The tile has gone off screen: the page leaves its box, so the next holder to draw it can take it.
     * It isn't destroyed, though: it keeps its page, and its animations wait; its script does not.
     */
    fun detach(holder: Any) {
        // A holder that let go after another one took the page leaves it running: it is on screen.
        if (this.holder !== holder) return
        this.holder = null
        val v = view ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        v.onPause()
    }

    /** Whether the tile can be seen: out of sight, the page's animations and media wait. */
    internal fun shown(seen: Boolean) {
        this.seen = seen
        refresh()
    }

    /**
     * The page as the tile and the launcher are now ([seen], [BrowserPages.started]). With the launcher stopped (the
     * screen off, another app open) its script waits too, which onPause alone doesn't do: a page with `js: true` would
     * otherwise go on running, and fetching, behind everything.
     */
    internal fun refresh() {
        val v = view ?: return
        // Global to every WebView in the app; each page says the same, as they share the launcher's lifecycle.
        if (BrowserPages.started) v.resumeTimers() else v.pauseTimers()
        if (seen && BrowserPages.started && holder != null) v.onResume() else v.onPause()
    }

    /** The tile was tapped: the page gets the focus, and with it the keyboard one of its fields asks for. */
    fun focus() {
        view?.requestFocus()
    }

    /** The page lets go (a tap outside, Back or another page): its field loses the keyboard with it. */
    fun clearFocus() {
        val v = view ?: return
        v.clearFocus()
        // A page's own field would otherwise leave the keyboard up over a tile that isn't typing any more.
        v.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Loads [link], or the node's own url with none; what [attach] does the first time. */
    fun load(link: String = url.orEmpty()) {
        if (link.startsWith("https://")) view?.loadUrl(link)
    }

    fun back() {
        view?.let { if (it.canGoBack()) it.goBack() }
    }

    fun forward() {
        view?.let { if (it.canGoForward()) it.goForward() }
    }

    fun reload() {
        view?.reload()
    }

    fun stop() {
        view?.stopLoading()
    }

    /** Ends the page (the widget was removed, or its url changed); a tab switch doesn't. */
    fun close() {
        closed = true
        BrowserPages.live -= this
        // The tile lets go of it first: a destroyed view can't be cleared.
        tiles?.unfocused(this)
        holder = null
        view?.let {
            // Out of its holder before it goes: destroy() on a view the window still holds is a crash of its own.
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        view = null
    }

    private fun configure(v: WebView) {
        // WebView's own debugging port is for debug builds only; the setting is the process's, so once is enough.
        if (!debuggingSet) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            debuggingSet = true
        }
        v.setBackgroundColor(Palette.background.toArgb())
        val s = v.settings
        // Storage and script go together: a page without script has nothing to keep in DOM storage.
        s.setJavaScriptEnabled(js)
        s.setDomStorageEnabled(js)
        // A tile shows a page, not the phone's files: no file:// and nothing a content:// provider holds.
        s.setAllowFileAccess(false)
        s.setAllowContentAccess(false)
        // A page never asks the phone where it is; this is the setting beside the permission a tile mustn't want.
        s.setGeolocationEnabled(false)
        // target="_blank" doesn't open a second window the tile has nowhere to draw.
        s.setSupportMultipleWindows(false)
        // A page's sound waits for a tap, as everything else on home does.
        s.setMediaPlaybackRequiresUserGesture(true)
        // Pinch to zoom; the on-screen zoom buttons would cover the page.
        s.setSupportZoom(true)
        s.setBuiltInZoomControls(true)
        s.setDisplayZoomControls(false)
        s.setTextZoom((zoom * 100).roundToInt().coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT))
        // An https page can't pull in http: that would be in the clear, and looks no different.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW)
        // Every Browser tile shares this profile, so a sign-in in one is a sign-in in the others. A third
        // party's cookie is a tracker with nowhere useful to go.
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, false)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val link = request.url
            val scheme = link.scheme.orEmpty().lowercase()
            // The page itself loads over https only.
            if (scheme == "https") return false
            // Nothing loads over these: http would be in the clear, and the rest reach the phone's files
            // or run script from the URL, which a tile's page has no business doing.
            if (scheme in BLOCKED) return true
            // A link another app owns (mailto:, tel:, market:) goes there, as the url(...) action does, but only when
            // the finger is what asked: a redirect or a meta refresh to tel: would open the dialer by itself, on load
            // and again at every start, with no tap anywhere.
            if (request.hasGesture() && request.isForMainFrame) view.context.openUrl(link.toString())
            return true
        }

        /**
         * Nothing goes out over http, whatever asks: the app allows cleartext for its own sake, and a form posted to an
         * http page never reaches [shouldOverrideUrlLoading]. Called off the main thread; it reads only the request.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? =
            if (request.url.scheme.equals("http", ignoreCase = true)) {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }

        /**
         * The page's renderer crashed or was killed for memory. Unhandled, that kills the launcher with it; handled,
         * the tile drops the dead view and comes back with a new one the next time it's drawn.
         */
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            if (view !== this@EmbeddedBrowser.view) return true
            BrowserPages.live -= this@EmbeddedBrowser
            tiles?.unfocused(this@EmbeddedBrowser)
            holder = null
            this@EmbeddedBrowser.view = null
            canGoBack = false
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            return true
        }

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            show(url.orEmpty())
        }

        override fun doUpdateVisitedHistory(
            view: WebView,
            url: String?,
            isReload: Boolean,
        ) {
            show(url.orEmpty())
            canGoBack = view.canGoBack()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onReceivedTitle(
            view: WebView,
            title: String?,
        ) {
            val t = title?.takeIf { it.isNotBlank() } ?: return
            this@EmbeddedBrowser.title = t
            onTitle?.invoke(t)
        }

        override fun onProgressChanged(
            view: WebView,
            newProgress: Int,
        ) {
            val p = (newProgress / 100f).coerceIn(0f, 1f)
            progress = p
            onProgress?.invoke(p.toDouble())
        }
    }

    /** The page is now at [url]; tells onNavigate once per change. */
    private fun show(url: String) {
        if (url.isEmpty() || url == currentUrl) return
        currentUrl = url
        onNavigate?.invoke(url)
    }

    private companion object {
        /** Whether WebView's debugging port has been set for this process; see [configure]. */
        var debuggingSet = false

        /** Schemes a tile's page may not load: another is a link, https or not at all. */
        val BLOCKED = setOf("http", "file", "content", "javascript", "data")

        const val MIN_ZOOM_PERCENT = 50
        const val MAX_ZOOM_PERCENT = 300

        const val TAG = "browser"
    }
}

/**
 * Every Browser page that has a view, for what WebView keeps for the whole process: its timers, which run only while the
 * launcher is started. Told by the launcher's own start and stop, not by a tile, so a page kept alive on a home page
 * that isn't composed waits too. Main thread only.
 */
internal object BrowserPages {
    val live = HashSet<EmbeddedBrowser>()

    /** Whether the launcher is started; while it's stopped every page's script waits. */
    var started = true
        private set

    fun started(on: Boolean) {
        if (on == started) return
        started = on
        live.forEach { it.refresh() }
    }
}

/**
 * A tile's page as a view: a WebView that takes no touch of its own until the tile is tapped, so the home screen's own
 * gestures still reach it: a swipe across the page turns the page, a long press lifts the tile, a tap outside lets go.
 * It has to be the view's own doing: an AndroidView's touches reach its view before Compose's pointer input does, and
 * a WebView consumes every one of them.
 */
internal class TileWebView(
    context: Context,
) : WebView(context) {
    /** Untapped: the page gets none of the touches, and [BrowserView]'s overlay takes the tap that gives it the tile. */
    var inert = true

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = !inert && super.dispatchTouchEvent(event)
}

/** The tile's content: the page, filling the tile. Its handlers are the node's, run as the page changes. */
@Composable
fun BrowserView(
    browser: EmbeddedBrowser,
    modifier: Modifier,
    onTitle: ((String) -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    onProgress: ((Double) -> Unit)? = null,
) {
    // The widget is rebuilt as its values change: the page's handlers follow it rather than the first ones.
    SideEffect {
        browser.onTitle = onTitle
        browser.onNavigate = onNavigate
        browser.onProgress = onProgress
    }
    val tiles = browser.tiles
    val active = tiles != null && tiles.browser === browser
    // Paused while the tile can't be seen: a page beside the one showing is still composed. The launcher stopping is
    // [BrowserPages]'.
    val covered = LocalHomeCovered.current
    LaunchedEffect(browser, covered) {
        snapshotFlow { !covered() }.distinctUntilChanged().collect(browser::shown)
    }
    // The tile's tab went away (its visible turned false) or the node did: out of sight, a page with the touches mustn't
    // keep them, or leave the home screen lifted for a tile that no longer shows.
    DisposableEffect(tiles, browser) { onDispose { if (tiles != null && tiles.browser === browser) tiles.deactivate() } }
    val background = Palette.background
    Box(modifier.onGloballyPositioned { browser.bounds = it.boundsInWindow().translate(0f, tiles?.lift ?: 0f) }) {
        // Keyed on the page itself: a tile rebuilt for a new url is a holder of its own, and the same page keeps
        // the view it already has (and the scroll and history in it) instead of a holder swapping views under it.
        key(browser) {
            // A box of this holder's own, and a token that tells it from the holder the page was in before: the page
            // moves between boxes, and is never the view Compose adds and takes away.
            val holder = remember { Any() }
            AndroidView(
                factory = { FrameLayout(it) },
                update = { host ->
                    browser.attach(host, holder)?.also {
                        it.setBackgroundColor(background.toArgb())
                        // Only a tapped page of a home tile takes touches; until then the overlay below is the tile's.
                        it.inert = tiles != null && !active
                    }
                },
                onRelease = { browser.detach(holder) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (tiles != null && !active) {
            // Over the WebView: the tap that gives the page its tile, as a terminal's overlay does.
            Box(
                Modifier.fillMaxSize().pointerInput(browser) {
                    detectTapGestures {
                        tiles.focused(browser)
                        browser.focus()
                    }
                },
            )
        }
    }
}
