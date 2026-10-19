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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.omalauncher.Runtime
import dev.omalauncher.linux.GuestEntry
import dev.omalauncher.linux.GuestFs
import dev.omalauncher.linux.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A touch-first file manager for the guest filesystem, in the Omarchy theme. */
@Composable
fun FilesScreen(
    runtime: Runtime,
    startPath: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val fs = remember { GuestFs(runtime.proot::hostFile) }
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

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun navigate(to: String) {
        history += path
        path = to
        query = null
        selected.clear()
    }

    /** Runs a file operation off the main thread, then reloads the listing. */
    fun operate(block: () -> Unit) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(block).exceptionOrNull() }
            if (error != null) toast(error.message ?: "failed")
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
                return toast("can't hand ${file.name} to other apps")
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
            toast("no app can open ${file.name}")
        }
    }

    fun edit(entry: GuestEntry) = runtime.edit(entry.path)

    fun open(entry: GuestEntry) {
        if (entry.isDir) return navigate(fs.resolve(entry.path))
        val file = fs.host(entry.path)
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
                path = history.removeAt(history.lastIndex)
                selected.clear()
            }
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(PanelGap)) {
        val q = query
        PanelFrame(
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
                    selected.isNotEmpty() -> PanelTitle("${selected.size} selected")
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
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            decorationBox = { inner ->
                                Box {
                                    if (q.isEmpty()) BasicText("filter this folder", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Title))
                                    inner()
                                }
                            },
                        )
                    }
                    else ->
                        PanelTitle(
                            when (path) {
                                "/" -> "/"
                                "/root" -> "home"
                                else -> path.substringAfterLast('/')
                            },
                        )
                }
            },
            actions = {
                if (selected.isEmpty() && q == null) {
                    PanelIcon(Icons.Search, "search") { query = "" }
                    Box {
                        PanelIcon(Icons.More, "more") { menuOpen = true }
                        if (menuOpen) {
                            MenuPopup(
                                listOf(
                                    "new file" to { dialog = FileDialog.NewFile },
                                    "new folder" to { dialog = FileDialog.NewFolder },
                                    (if (showHidden) "hide hidden files" else "show hidden files") to { showHidden = !showHidden },
                                    "open terminal here" to { runtime.terminal("cd ${shellQuote(path)} && exec bash", "Terminal") },
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
                            if (query.isNullOrBlank()) "empty folder" else "nothing matches",
                            Modifier.align(Alignment.Center),
                            style = Mono.copy(color = Palette.muted),
                        )
                    else ->
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shown, key = { it.path }) { entry ->
                                FileRow(
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

            val clip = FileClipboard.paths
            if (selected.isNotEmpty()) {
                val single = entries?.firstOrNull { it.path == selected.singleOrNull() }
                // Sniffing a file for text reads it: off the main thread, once per selection.
                val singleIsText by produceState(false, single?.path) {
                    value = single?.takeIf { !it.isDir }?.let { s -> withContext(Dispatchers.IO) { isText(s.name, fs.host(s.path)) } } == true
                }
                SelectionBar(
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
                    onShare = single?.takeIf { !it.isDir }?.let { { openWithAndroid(fs.host(it.path), share = true) } },
                    onOpenWith = single?.takeIf { !it.isDir }?.let { { openWithAndroid(fs.host(it.path)) } },
                    onEdit = single?.takeIf { singleIsText }?.let { { edit(it) } },
                )
            } else if (clip.isNotEmpty()) {
                PasteBar(
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
            TextDialog("new file", "", "create", onDismiss = { dialog = null }) { name ->
                dialog = null
                scope.launch {
                    val created = withContext(Dispatchers.IO) { runCatching { fs.createFile(path, name) } }
                    created.exceptionOrNull()?.let { toast(it.message ?: "failed") }
                    reload++
                    created.getOrNull()?.let { runtime.edit(it) }
                }
            }
        FileDialog.NewFolder ->
            TextDialog("new folder", "", "create", onDismiss = { dialog = null }) { name ->
                dialog = null
                operate { fs.mkdir(path, name) }
            }
        is FileDialog.Rename ->
            TextDialog("rename", d.entry.name, "rename", onDismiss = { dialog = null }) { name ->
                dialog = null
                operate { fs.rename(d.entry.path, name) }
            }
        is FileDialog.Delete -> {
            val what = if (d.paths.size == 1) d.paths[0].substringAfterLast('/') else "${d.paths.size} items"
            ConfirmDialog("delete $what?", "This can't be undone.", "delete", onDismiss = { dialog = null }) {
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
