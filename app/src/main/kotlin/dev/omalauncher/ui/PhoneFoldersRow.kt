package dev.omalauncher.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.PhoneFolders
import dev.omalauncher.PhoneStorage
import dev.omalauncher.R
import dev.omalauncher.showingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android's folder picker; the folder picked is kept as one of the [PhoneFolders]. */
@Composable
internal fun rememberAddPhoneFolder(folders: PhoneFolders): () -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val pick =
        rememberLauncherForActivityResult(
            object : ActivityResultContracts.OpenDocumentTree() {
                override fun createIntent(
                    context: Context,
                    input: Uri?,
                ) = super.createIntent(context, input).showingStorage()
            },
        ) { tree ->
            if (tree != null) {
                runCatching { folders.add(tree) }.onFailure {
                    Toast.makeText(context, resources.getString(R.string.permissions_folders_cant_keep), Toast.LENGTH_SHORT).show()
                }
            }
        }
    return {
        try {
            pick.launch(null)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, resources.getString(R.string.permissions_folders_no_picker), Toast.LENGTH_SHORT).show()
        }
    }
}

/** The phone folders Linux reaches, on top of the permissions screen: each to remove, and one to add. */
@Composable
internal fun PhoneFoldersRow(folders: PhoneFolders) {
    val add = rememberAddPhoneFolder(folders)
    val list by produceState(emptyList<PhoneFolders.Folder>(), folders.changes) {
        value = withContext(Dispatchers.IO) { runCatching { folders.folders() }.getOrDefault(emptyList()) }
    }
    Column(Modifier.card()) {
        BasicText(stringResource(R.string.permissions_folders_title), style = Mono.copy(fontSize = TextSize.Item))
        val small = Mono.copy(color = Palette.muted, fontSize = TextSize.Small)
        BasicText(stringResource(R.string.permissions_folders_about), style = small)
        if (PhoneStorage.supported()) {
            Spacer(Modifier.height(4.dp))
            BasicText(stringResource(R.string.permissions_folders_sdcard, PhoneStorage.GUEST), style = small)
        }
        for (folder in list) {
            Spacer(Modifier.height(6.dp))
            ActionsRow(actions = { BracketAction(stringResource(R.string.permissions_folders_remove), Palette.red) { folders.remove(folder) } }) {
                BasicText(folder.name, style = Mono.copy(color = Palette.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(4.dp))
        BracketAction(stringResource(R.string.permissions_folders_add), Palette.accent, onClick = add)
    }
}
