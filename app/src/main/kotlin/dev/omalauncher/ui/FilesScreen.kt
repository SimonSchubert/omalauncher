package dev.omalauncher.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.GuestEntry
import dev.omalauncher.linux.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A touch-first file manager for the guest filesystem, in the Omarchy theme. */
@Composable
fun FilesScreen(
    runtime: Runtime,
    startPath: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val fs = runtime.proot.guestFs
    val scope = rememberCoroutineScope()

    var path by remember(startPath) { mutableStateOf(fs.normalize(startPath)) }
    val history = remember(startPath) { mutableStateListOf<String>() }
    var showHidden by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var reload by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var dialog by remember { mutableStateOf<FileDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val entries by produceState<List<GuestEntry>?>(null, path, showHidden, reload) {
        value = withContext(Dispatchers.IO) { runCatching { fs.list(path, showHidden) }.getOrDefault(emptyList()) }
    }
    val shown = entries?.filter { e -> query.isNullOrBlank() || query!!.lowercase() in e.name.lowercase() }
    val listState = rememberLazyListState()
    LaunchedEffect(path) { listState.scrollToItem(0) }
    // Keys in a folder that's gone land on the row of the one they came out of, going up, else on the first row.
    var landing by remember { mutableStateOf<String?>(null) }
    var scrollTo by remember { mutableStateOf<String?>(null) }
    val land = remember { FocusRequester() }
    // Else, as with that row scrolled away, on a row that shows.
    val rows = remember { FocusRequester() }
    LaunchedEffect(shown) {
        val i = shown?.indexOfFirst { it.path == scrollTo } ?: -1
        if (i < 0) return@LaunchedEffect
        scrollTo = null
        listState.scrollToItem(i)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** Where keys land in [to], coming from [from]: the row on the way back down to it. */
    fun landIn(
        to: String,
        from: String,
    ) {
        val dir = to.trimEnd('/') + "/"
        landing = if (from.startsWith(dir)) dir + from.removePrefix(dir).substringBefore('/') else null
        scrollTo = landing
    }

    fun navigate(to: String) {
        landIn(to, path)
        history += path
        path = to
        query = null
        selected.clear()
    }

    /** Runs a file operation off the main thread, then reloads the listing. */
    fun operate(block: () -> Unit) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            if (error != null) toast(error.message ?: context.getString(R.string.files_failed))
            selected.clear()
            reload++
        }
    }

    fun openWithAndroid(file: File, share: Boolean = false) {
        // A file outside the folders in file_paths.xml (a link out of them) has no uri.
        val uri =
            try {
                FileProvider.getUriForFile(context, context.packageName + ".files", file)
            } catch (e: IllegalArgumentException) {
                return toast(context.getString(R.string.files_cant_hand_over, file.name))
            }
        val mime = mimeOf(file.name) ?: "*/*"
        val intent =
            if (share) {
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(context.getString(R.string.files_no_app, file.name))
        }
    }

    fun edit(entry: GuestEntry) = runtime.edit(entry.path)

    /**
     * The Android file for [entry]; null, with a toast, for a link that leads out of Linux, round in a loop or to
     * nothing, like one into /proc, which Linux sees and Files doesn't.
     */
    fun hostFile(entry: GuestEntry): File? =
        try {
            fs.host(entry.path).also { if (!it.exists()) throw IOException(context.getString(R.string.files_leads_to_nothing)) }
        } catch (e: IOException) {
            toast(context.getString(R.string.files_cant_open, entry.name, e.message))
            null
        }

    fun open(entry: GuestEntry) {
        if (entry.isDir) return navigate(fs.resolve(entry.path))
        val file = hostFile(entry) ?: return
        when {
            isImage(entry.name) -> viewing = file
            isText(entry.name, file) -> edit(entry)
            else -> openWithAndroid(file)
        }
    }

    BackHandler {
        when {
            viewing != null -> viewing = null
            selected.isNotEmpty() -> selected.clear()
            query != null -> query = null
            history.isNotEmpty() -> {
                landIn(history.last(), path)
                path = history.removeAt(history.lastIndex)
                selected.clear()
            }
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(PanelGap)) {
        val q = query
        // Keys start on the close mark, as in the launcher's panels.
        val close = remember { FocusRequester() }
        PanelFrame(
            modifier = Modifier.holdsKeys(start = close),
            closeFocus = close,
            // Like back, the close mark leaves the selection or the search first.
            onClose = {
                when {
                    selected.isNotEmpty() -> selected.clear()
                    q != null -> query = null
                    else -> onExit()
                }
            },
            title = {
                when {
                    selected.isNotEmpty() -> PanelTitle(stringResource(R.string.files_selected, selected.size))
                    q != null -> {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        BasicTextField(
                            value = q,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = Mono.copy(fontSize = TextSize.Title),
                            cursorBrush = SolidColor(Palette.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).leavesUpDown(),
                            decorationBox = { inner ->
                                Box {
                                    if (q.isEmpty()) BasicText(stringResource(R.string.files_filter_hint), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Title))
                                    inner()
                                }
                            },
                        )
                    }
                    else ->
                        PanelTitle(
                            when (path) {
                                "/" -> "/"
                                "/root" -> stringResource(R.string.files_home)
                                else -> path.substringAfterLast('/')
                            },
                        )
                }
            },
            actions = {
                if (selected.isEmpty() && q == null) {
                    PanelIcon(Icons.Search, stringResource(R.string.files_search)) { query = "" }
                    Box {
                        PanelIcon(Icons.More, stringResource(R.string.files_more)) { menuOpen = true }
                        if (menuOpen) {
                            MenuPopup(
                                listOf(
                                    stringResource(R.string.files_new_file) to { dialog = FileDialog.NewFile },
                                    stringResource(R.string.files_new_folder) to { dialog = FileDialog.NewFolder },
                                    stringResource(if (showHidden) R.string.files_hide_hidden else R.string.files_show_hidden) to { showHidden = !showHidden },
                                    stringResource(R.string.files_open_terminal) to { runtime.terminal("cd ${shellQuote(path)} && exec bash", "Terminal") },
                                ),
                                onDismiss = { menuOpen = false },
                            )
                        }
                    }
                }
            },
            contentWidth = ReadingWidth,
        ) {
            Breadcrumbs(path, onNavigate = ::navigate)
            Places(path, exists = fs::isDir, onNavigate = ::navigate)
            Spacer(Modifier.height(4.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    shown == null -> {}
                    shown.isEmpty() ->
                        BasicText(
                            stringResource(if (query.isNullOrBlank()) R.string.files_empty_folder else R.string.files_no_matches),
                            Modifier.align(Alignment.Center),
                            style = Mono.copy(color = Palette.muted),
                        )
                    else -> {
                        val landAt = shown.indexOfFirst { it.path == landing }.coerceAtLeast(0)
                        LazyColumn(Modifier.focusRequester(rows).focusGroup(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(shown, key = { _, e -> e.path }) { i, entry ->
                                FileRow(
                                    modifier = (if (i == landAt) Modifier.focusRequester(land).takesPassedKeys() else Modifier).passesKeys(land, rows),
                                    entry = entry,
                                    selected = entry.path in selected,
                                    onClick = {
                                        if (selected.isEmpty()) {
                                            open(entry)
                                        } else if (!selected.remove(entry.path)) {
                                            selected += entry.path
                                        }
                                    },
                                    onLongClick = { if (entry.path !in selected) selected += entry.path },
                                )
                            }
                        }
                    }
                }
            }

            val clip = FileClipboard.paths
            // Keys on a bar as it goes, after copy or cut, go to paste here, and after that to the list.
            val paste = remember { FocusRequester() }
            if (selected.isNotEmpty()) {
                val single = entries?.firstOrNull { it.path == selected.singleOrNull() }
                // Sniffing a file for text reads it: off the main thread, once per selection.
                val singleIsText by produceState(false, single?.path) {
                    value = single?.takeIf { !it.isDir }?.let { s -> withContext(Dispatchers.IO) { runCatching { isText(s.name, fs.host(s.path)) }.getOrDefault(false) } } == true
                }
                SelectionBar(
                    Modifier.passesKeys(paste, land, rows),
                    onSelectAll = {
                        selected.clear()
                        selected += shown.orEmpty().map { it.path }
                    },
                    onCopy = {
                        FileClipboard.paths = selected.toList()
                        FileClipboard.cut = false
                        selected.clear()
                    },
                    onCut = {
                        FileClipboard.paths = selected.toList()
                        FileClipboard.cut = true
                        selected.clear()
                    },
                    onDelete = { dialog = FileDialog.Delete(selected.toList()) },
                    onRename = single?.let { { dialog = FileDialog.Rename(it) } },
                    onShare = single?.takeIf { !it.isDir }?.let { { hostFile(it)?.let { f -> openWithAndroid(f, share = true) } } },
                    onOpenWith = single?.takeIf { !it.isDir }?.let { { hostFile(it)?.let(::openWithAndroid) } },
                    onEdit = single?.takeIf { singleIsText }?.let { { edit(it) } },
                )
            } else if (clip.isNotEmpty()) {
                PasteBar(
                    Modifier.passesKeys(land, rows),
                    paste,
                    count = clip.size,
                    cut = FileClipboard.cut,
                    onCancel = { FileClipboard.paths = emptyList() },
                    onPaste = {
                        val cut = FileClipboard.cut
                        FileClipboard.paths = emptyList()
                        operate { fs.paste(clip, path, move = cut) }
                    },
                )
            }
        }
    }

    viewing?.let { file -> ImageViewer(file, onClose = { viewing = null }) }

    when (val d = dialog) {
        null -> {}
        FileDialog.NewFile ->
            TextDialog(stringResource(R.string.files_new_file), "", stringResource(R.string.files_create), onDismiss = { dialog = null }) { name ->
                dialog = null
                scope.launch {
                    val created = withContext(Dispatchers.IO) { runCatching { fs.createFile(path, name) } }
                    created.exceptionOrNull()?.let { toast(it.message ?: context.getString(R.string.files_failed)) }
                    reload++
                    created.getOrNull()?.let { runtime.edit(it) }
                }
            }
        FileDialog.NewFolder ->
            TextDialog(stringResource(R.string.files_new_folder), "", stringResource(R.string.files_create), onDismiss = { dialog = null }) { name ->
                dialog = null
                operate { fs.mkdir(path, name) }
            }
        is FileDialog.Rename ->
            TextDialog(stringResource(R.string.files_rename), d.entry.name, stringResource(R.string.files_rename), onDismiss = { dialog = null }) { name ->
                dialog = null
                operate { fs.rename(d.entry.path, name) }
            }
        is FileDialog.Delete -> {
            val title =
                if (d.paths.size == 1) {
                    stringResource(R.string.files_delete_one, d.paths[0].substringAfterLast('/'))
                } else {
                    pluralStringResource(R.plurals.files_delete_items, d.paths.size, d.paths.size)
                }
            ConfirmDialog(title, stringResource(R.string.files_delete_message), stringResource(R.string.files_delete), onDismiss = { dialog = null }) {
                dialog = null
                operate { d.paths.forEach(fs::delete) }
            }
        }
    }
}

private sealed interface FileDialog {
    data object NewFile : FileDialog

    data object NewFolder : FileDialog

    data class Rename(
        val entry: GuestEntry,
    ) : FileDialog

    data class Delete(
        val paths: List<String>,
    ) : FileDialog
}
