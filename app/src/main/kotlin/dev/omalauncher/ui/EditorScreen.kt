package dev.omalauncher.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.GuestFs
import dev.omalauncher.linux.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The editor's word wrap and recent files; recents leave out git's messages and /tmp, which are gone once used. */
internal class EditorPrefs(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("editor", Context.MODE_PRIVATE)

    var wrap: Boolean
        get() = prefs.getBoolean("wrap", true)
        set(value) = prefs.edit().putBoolean("wrap", value).apply()

    val recent: List<String>
        get() = prefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun opened(path: String) {
        if (path.startsWith("/tmp/") || path.substringAfterLast('/') in GIT_MESSAGES) return
        val list = (listOf(path) + recent.filter { it != path }).take(MAX_RECENT)
        prefs.edit().putString("recent", list.joinToString("\n")).apply()
    }

    private companion object {
        const val MAX_RECENT = 20
    }
}

/**
 * A touch-first text editor for one guest file, in the Omarchy theme: Android's
 * own selection, clipboard and keyboard (autocorrect included, for prose), with
 * the keys a phone keyboard lacks above it. [onFallback] runs for files it
 * can't edit (too big, binary), which micro gets instead.
 */
@Composable
fun EditorScreen(
    runtime: Runtime,
    path: String,
    onFallback: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val fs = runtime.proot.guestFs
    val loaded by produceState<Result<EditorFile>?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { readEditorFile(fs.host(path)) } }
    }
    LaunchedEffect(path) { EditorPrefs(context).opened(path) }
    when (val file = loaded?.getOrNull()) {
        null ->
            loaded?.exceptionOrNull()?.let { error ->
                LaunchedEffect(error) {
                    Toast.makeText(context, "can't read ${path.substringAfterLast('/')}: ${error.message}", Toast.LENGTH_LONG).show()
                    onExit()
                }
            }
        is EditorFile.Text -> EditorBody(runtime, fs, path, file, onExit)
        EditorFile.TooBig, EditorFile.Binary -> LaunchedEffect(file) { onFallback() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorBody(
    runtime: Runtime,
    fs: GuestFs,
    path: String,
    file: EditorFile.Text,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { EditorPrefs(context) }
    val name = path.substringAfterLast('/')

    val state = remember { TextFieldState(file.text) }
    var saved by remember { mutableStateOf(file.text) }
    val dirty by remember { derivedStateOf { !state.text.contentEquals(saved) } }
    val indent = remember { indentUnit(file.text) }
    var wrap by remember { mutableStateOf(prefs.wrap) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val editorFocus = remember { FocusRequester() }

    var query by remember { mutableStateOf<String?>(null) }
    var match by remember { mutableStateOf<TextRange?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    suspend fun save(): Boolean {
        val text = state.text.toString()
        val error =
            withContext(Dispatchers.IO) {
                runCatching {
                    // Written in place, so links, permissions and hard links stay as they were.
                    val host = fs.host(path)
                    host.parentFile?.mkdirs()
                    host.writeBytes(encodeEditorText(text, file.crlf))
                }.exceptionOrNull()
            }
        if (error != null) {
            toast("can't save $name: ${error.message}")
            return false
        }
        saved = text
        return true
    }

    fun close() {
        if (dirty) confirmClose = true else onExit()
    }

    /** Scrolls [offset]'s line a third of the way down the view, if it's off screen. */
    fun reveal(offset: Int) {
        val l = layout ?: return
        if (offset > l.layoutInput.text.length) return
        val top = l.getLineTop(l.getLineForOffset(offset))
        if (top >= scroll.value && l.getLineBottom(l.getLineForOffset(offset)) <= scroll.value + scroll.viewportSize) return
        scope.launch { scroll.animateScrollTo((top - scroll.viewportSize / 3f).toInt().coerceIn(0, scroll.maxValue)) }
    }

    fun find(backward: Boolean = false) {
        val q = query ?: return
        val from = if (backward) match?.start ?: state.selection.min else match?.end ?: state.selection.max
        val hit = findMatch(state.text, q, from, backward)
        if (hit == null) {
            match = null
            if (q.isNotEmpty()) toast("not found")
            return
        }
        match = TextRange(hit, hit + q.length).also { state.edit { selection = it } }
        reveal(hit)
    }

    fun closeFind() {
        query = null
        match = null
        editorFocus.requestFocus()
    }

    BackHandler {
        if (query != null) closeFind() else close()
    }

    Column(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(PanelGap)) {
        val q = query
        PanelFrame(
            onClose = { if (q != null) closeFind() else close() },
            modifier = Modifier.weight(1f),
            title = {
                if (q != null) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    BasicTextField(
                        value = q,
                        onValueChange = {
                            query = it
                            // Searching as you type, from where the last match began.
                            match = match?.let { m -> TextRange(m.start) }
                            find()
                        },
                        singleLine = true,
                        textStyle = Mono.copy(fontSize = TextSize.Title),
                        cursorBrush = SolidColor(Palette.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSearch = { find() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        decorationBox = { inner ->
                            Box {
                                if (q.isEmpty()) BasicText("find in file", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Title))
                                inner()
                            }
                        },
                    )
                } else {
                    PanelTitle(if (dirty) "$name *" else name)
                }
            },
            actions = {
                if (q != null) {
                    PanelIcon(Icons.Chevron, "previous match") { find(backward = true) }
                    PanelIcon(Icons.Chevron, "next match", rotation = 180f) { find() }
                } else {
                    if (dirty) BracketAction("save", Palette.accent) { scope.launch { save() } }
                    PanelIcon(Icons.Search, "find") { query = "" }
                    Box {
                        PanelIcon(Icons.More, "more") { menuOpen = true }
                        if (menuOpen) {
                            MenuPopup(
                                listOf(
                                    (if (wrap) "don't wrap lines" else "wrap lines") to {
                                        wrap = !wrap
                                        prefs.wrap = wrap
                                    },
                                    "show in files" to { runtime.openFiles(fs.parent(path)) },
                                    "open in micro" to {
                                        scope.launch {
                                            if (!dirty || save()) {
                                                runtime.terminal("micro ${shellQuote(path)}", name)
                                                onExit()
                                            }
                                        }
                                    },
                                ),
                                onDismiss = { menuOpen = false },
                            )
                        }
                    }
                }
            },
        ) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Gutter(state, layout, scroll, Modifier.fillMaxHeight())
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val viewport = maxWidth
                    val hScroll = rememberScrollState()
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .then(if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(hScroll))
                            .drawBehind {
                                // The find match, marked even while the find field has the focus.
                                val m = match ?: return@drawBehind
                                val l = layout ?: return@drawBehind
                                if (m.max > l.layoutInput.text.length) return@drawBehind
                                translate(top = -scroll.value.toFloat()) {
                                    drawPath(l.getPathForRange(m.min, m.max), Palette.accent.copy(alpha = 0.35f))
                                }
                            },
                    ) {
                        val prose = isProse(name)
                        BasicTextField(
                            state = state,
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = viewport))
                                    .focusRequester(editorFocus),
                            textStyle = Mono.copy(fontSize = TextSize.Large),
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = if (prose) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                                    autoCorrectEnabled = prose,
                                ),
                            lineLimits = TextFieldLineLimits.MultiLine(),
                            onTextLayout = { result -> layout = result() },
                            cursorBrush = SolidColor(Palette.accent),
                            scrollState = scroll,
                        )
                    }
                }
            }
        }
        if (extraKeysWanted()) {
            EditorKeys(state, { layout }, indent, Modifier.padding(top = PanelGap)) { reveal(state.selection.end) }
        }
    }

    if (confirmClose) {
        DialogFrame(onDismiss = { confirmClose = false }) {
            BasicText("save $name?", style = Mono.copy(fontSize = TextSize.Title))
            BasicText("You have changes that aren't saved yet.", style = Mono.copy(color = Palette.muted))
            // Cancel is a tap outside or back; three actions don't fit a phone's dialog.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BracketAction("discard", Palette.red) {
                    confirmClose = false
                    onExit()
                }
                BracketAction("save", Palette.accent) {
                    confirmClose = false
                    scope.launch { if (save()) onExit() }
                }
            }
        }
    }
}

/** Line numbers, kept level with the text as it scrolls; the cursor's line in the accent color. */
@Composable
private fun Gutter(
    state: TextFieldState,
    layout: TextLayoutResult?,
    scroll: ScrollState,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = Mono.copy(color = Palette.muted, fontSize = TextSize.Large)
    val text = state.text
    val starts = remember(text) { lineStarts(text) }
    // The widest number, then the gap to the text.
    val width = with(density) { measurer.measure("0".repeat(starts.size.toString().length), style).size.width.toDp() } + 8.dp
    val cursor = state.selection.start
    Canvas(modifier.width(width).reachLeft(GutterReach).clipToBounds()) {
        val l = layout ?: return@Canvas
        // The layout trails an edit by a frame; draw only lines it already has.
        val length = l.layoutInput.text.length
        val top = scroll.value.toFloat()
        val first = l.getLineStart(l.getLineForVerticalPosition(top))
        var i = starts.binarySearch(first).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        val current = starts.binarySearch(cursor).let { if (it >= 0) it else -it - 2 }
        while (i < starts.size && starts[i] <= length) {
            val y = l.getLineTop(l.getLineForOffset(starts[i])) - top
            if (y > size.height) break
            val number = measurer.measure((i + 1).toString(), if (i == current) style.copy(color = Palette.accent) else style)
            drawText(number, topLeft = Offset(size.width - number.size.width - 8.dp.toPx(), y))
            i++
        }
    }
}

/** How far the line numbers reach into the panel's padding, so they don't take more room than they need. */
private val GutterReach = 4.dp

/** Draws the element [by] further left, into its parent's padding, taking that much less room. */
private fun Modifier.reachLeft(by: Dp) =
    layout { measurable, constraints ->
        val reach = by.roundToPx()
        val placeable = measurable.measure(constraints)
        layout((placeable.width - reach).coerceAtLeast(0), placeable.height) { placeable.place(-reach, 0) }
    }

/** One key above the keyboard: a label, or [icon] turned [rotation] degrees. */
private class EditorKey(
    val label: String,
    val icon: ImageVector? = null,
    val rotation: Float = 0f,
    val repeats: Boolean = false,
    val action: () -> Unit,
)

/**
 * The keys a phone keyboard lacks for code: tab, line start and end, arrows
 * (fine cursor moves are fiddly by touch), undo and redo. Like the terminal's
 * extra keys they never take the focus, so the keyboard stays up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorKeys(
    state: TextFieldState,
    layout: () -> TextLayoutResult?,
    indent: String,
    modifier: Modifier,
    /** After a move, to keep the cursor in view. */
    onMoved: () -> Unit,
) {
    fun moveTo(offset: Int) {
        state.edit { selection = TextRange(offset.coerceIn(0, length)) }
        onMoved()
    }

    fun vertical(down: Boolean) {
        val l = layout() ?: return
        val at = state.selection.end
        if (at > l.layoutInput.text.length) return
        val line = l.getLineForOffset(at)
        val target = line + if (down) 1 else -1
        when {
            target < 0 -> moveTo(0)
            target >= l.lineCount -> moveTo(state.text.length)
            else -> {
                val x = l.getHorizontalPosition(at, usePrimaryDirection = true)
                val y = (l.getLineTop(target) + l.getLineBottom(target)) / 2
                moveTo(l.getOffsetForPosition(Offset(x, y)))
            }
        }
    }

    val keys =
        listOf(
            EditorKey("tab") {
                val sel = state.selection
                state.edit {
                    replace(sel.min, sel.max, indent)
                    selection = TextRange(sel.min + indent.length)
                }
                onMoved()
            },
            EditorKey("home") {
                // To the line's first non-blank character, then to column 0.
                val text = state.text
                val at = state.selection.end
                val start = text.lastIndexOf('\n', at - 1) + 1
                var content = start
                while (content < text.length && (text[content] == ' ' || text[content] == '\t')) content++
                moveTo(if (at == content) start else content)
            },
            EditorKey("left", Icons.Chevron, -90f, repeats = true) {
                val sel = state.selection
                if (!sel.collapsed) moveTo(sel.min) else moveTo(sel.start - if (sel.start >= 2 && state.text[sel.start - 1].isLowSurrogate()) 2 else 1)
            },
            EditorKey("up", Icons.Chevron, 0f, repeats = true) { vertical(down = false) },
            EditorKey("down", Icons.Chevron, 180f, repeats = true) { vertical(down = true) },
            EditorKey("right", Icons.Chevron, 90f, repeats = true) {
                val sel = state.selection
                if (!sel.collapsed) moveTo(sel.max) else moveTo(sel.end + if (sel.end < state.text.length && state.text[sel.end].isHighSurrogate()) 2 else 1)
            },
            EditorKey("end") {
                val text = state.text
                val at = state.selection.end
                moveTo(text.indexOf('\n', at).let { if (it < 0) text.length else it })
            },
            EditorKey("undo", Icons.Undo, repeats = true) {
                state.undoState.undo()
                onMoved()
            },
            EditorKey("redo", Icons.Redo, repeats = true) {
                state.undoState.redo()
                onMoved()
            },
        )
    Row(
        modifier
            .fillMaxWidth()
            .frame(FrameWidth, Palette.border, Palette.background, radius = 12.dp)
            .padding(KeyGap)
            .height(KeyRowHeight),
    ) {
        for (key in keys) RepeatingKeyCap(key.label, key.icon, key.rotation, key.repeats, Modifier.weight(1f).fillMaxHeight().padding(KeyGap), key.action)
    }
}

/**
 * The editor without a file (its drawer entry): recent files, a new one, or Files
 * to find one. A pick opens in an editor of its own, like any file.
 */
@Composable
fun EditorHome(
    runtime: Runtime,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val fs = runtime.proot.guestFs
    val recent by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) { EditorPrefs(context).recent.filter { runCatching { fs.host(it).isFile }.getOrDefault(false) } }
    }
    var newFile by remember { mutableStateOf(false) }

    fun open(path: String) {
        runtime.edit(path)
        onExit()
    }

    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(PanelGap)) {
        PanelFrame(
            onClose = onExit,
            title = { PanelTitle("editor") },
            actions = {
                BracketAction("new", Palette.accent) { newFile = true }
                BracketAction("browse", Palette.muted) {
                    runtime.openFiles()
                    onExit()
                }
            },
            contentWidth = ReadingWidth,
        ) {
            SectionLabel("recent")
            if (recent.isEmpty()) {
                BasicText("Files you edit show up here.", Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { path ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .frame(0.dp, Palette.accent, Palette.surface, radius = 12.dp)
                            .clickable { open(path) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(Icons.TextFile, null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Palette.muted))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            BasicText(path.substringAfterLast('/'), style = Mono.copy(fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            BasicText(
                                fs.parent(path)?.replaceFirst(Regex("^/root(?=/|$)"), "~").orEmpty(),
                                style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (newFile) {
        TextDialog("new file", "/root/", "create", hint = "a path in Linux", onDismiss = { newFile = false }) { path ->
            newFile = false
            if (path.endsWith('/')) return@TextDialog
            open(fs.normalize(if (path.startsWith('/')) path else "/root/$path"))
        }
    }
}
