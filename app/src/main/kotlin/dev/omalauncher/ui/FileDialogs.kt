package dev.omalauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.omalauncher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun DialogFrame(
    onDismiss: () -> Unit,
    /** Where keys start, rather than on the first thing in it. */
    start: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .frame(2.dp, Palette.accent, Palette.surface, radius = 24.dp)
                // Keys start in it, and Esc dismisses it as Back does: its own window doesn't pass Esc to the activity's.
                .onKeyEvent { e ->
                    (e.key == Key.Escape).also { if (it && e.type == KeyEventType.KeyUp) onDismiss() }
                }.holdsKeys(start)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
internal fun TextDialog(
    title: String,
    initial: String,
    confirm: String,
    hint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // The cursor at the end, to type on after the folder given.
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    DialogFrame(onDismiss) {
        BasicText(title, style = Mono.copy(fontSize = TextSize.Title))
        hint?.let { BasicText(it, style = Mono.copy(color = Palette.muted)) }
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = Mono.copy(fontSize = TextSize.Large),
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text.trim()) }),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(LauncherStyle.shape(12.dp))
                    .background(Palette.background)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction(stringResource(R.string.files_cancel), Palette.muted, onClick = onDismiss)
            BracketAction(confirm, Palette.accent) { if (text.isNotBlank()) onConfirm(text.trim()) }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onDismiss: () -> Unit,
    /** Red by default: most confirmations are for something that can't be undone. */
    confirmColor: Color = Palette.red,
    onConfirm: () -> Unit,
) {
    DialogFrame(onDismiss) {
        BasicText(title, style = Mono.copy(fontSize = TextSize.Title))
        BasicText(message, style = Mono.copy(color = Palette.muted))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction(stringResource(R.string.files_cancel), Palette.muted, onClick = onDismiss)
            BracketAction(confirm, confirmColor, onClick = onConfirm)
        }
    }
}

@Composable
internal fun ImageViewer(
    file: File,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { decodeForScreen(file, context)?.asImageBitmap() }.getOrNull() }
    }
    // Keys close it as a tap does: Enter, or Back.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
            .holdsKeys()
            .keyActivated(onClose, null),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        BasicText(
            file.name,
            Modifier.align(Alignment.TopStart).systemBarsPadding().padding(16.dp),
            style = Mono.copy(color = Color.White),
        )
    }
}
