package dev.omalauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A Linux app asking for a file (through the file chooser portal): the Files
 * app picks one instead of opening it, and the answer is guest paths.
 */
data class FilePick(
    val mode: Mode,
    /** Open only: more than one file may be picked. */
    val multiple: Boolean = false,
    /** What the app calls the dialog ("Open G-code", "Export"); empty for ours. */
    val title: String = "",
    /** Save only: the name it suggests. */
    val name: String = "",
) {
    enum class Mode { Open, Folder, Save }

    /** The panel's title while nothing is selected. */
    val heading get() = title.ifBlank { when (mode) { Mode.Open -> "open"; Mode.Folder -> "choose folder"; Mode.Save -> "save as" } }

    companion object {
        /**
         * The request the portal backend writes after the bridge's prefix: tab-separated fields
         * mode, multiple (1 or 0), start folder, suggested name, title. Null for anything else.
         */
        fun parse(fields: List<String>): Pair<FilePick, String>? {
            if (fields.size < 5) return null
            val mode = when (fields[0]) {
                "open" -> Mode.Open
                "folder" -> Mode.Folder
                "save" -> Mode.Save
                else -> return null
            }
            val folder = fields[2].takeIf { it.startsWith('/') } ?: "/root"
            return FilePick(mode, multiple = fields[1] == "1", title = fields[4], name = fields[3]) to folder
        }
    }
}

/** Opening several files: how many are picked, and the button that hands them over. */
@Composable
internal fun PickFilesBar(
    count: Int,
    onOpen: () -> Unit,
) {
    BottomBar {
        BasicText(
            if (count == 0) "tap files to pick them" else if (count == 1) "1 file" else "$count files",
            Modifier.weight(1f).padding(start = 6.dp),
            style = Mono.copy(color = Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) BracketAction("open", Palette.accent, onClick = onOpen)
    }
}

/** Choosing a folder: the one being shown. */
@Composable
internal fun PickFolderBar(
    folder: String,
    onChoose: () -> Unit,
) {
    BottomBar {
        BasicText(
            folder,
            Modifier.weight(1f).padding(start = 6.dp),
            style = Mono.copy(color = Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BracketAction("use this folder", Palette.accent, onClick = onChoose)
    }
}

/** Saving: the file's name, in the folder being shown. Tapping a file takes its name. */
@Composable
internal fun SaveBar(
    name: String,
    onName: (String) -> Unit,
    onSave: () -> Unit,
) {
    BottomBar {
        BasicTextField(
            value = name,
            onValueChange = { onName(it.replace("/", "")) },
            singleLine = true,
            textStyle = Mono,
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave() }),
            modifier = Modifier.weight(1f).padding(start = 6.dp),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) BasicText("file name", style = Mono.copy(color = Palette.muted))
                    inner()
                }
            },
        )
        if (name.isNotBlank()) BracketAction("save", Palette.accent, onClick = onSave)
    }
}
