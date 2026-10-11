package dev.omalauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.ThemeStore
import kotlinx.coroutines.launch

/** The home menu's wallpaper choice: the current theme's backgrounds, photos the user adds to them, or Android's wallpaper. */
@Composable
fun WallpaperPicker(
    store: ThemeStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val system = store.useSystemWallpaper

    /** Hands the wallpaper to Android: the launcher shows whatever its picker sets. */
    fun pickInSystem() {
        scope.launch { store.setSystemWallpaper(true) }
        runCatching { context.startActivity(store.systemWallpaperIntent()) }
    }
    val current = store.current
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped when a background is added or removed; the list is read from disk.
    var changes by remember { mutableIntStateOf(0) }
    val backgrounds = remember(current, changes) { current?.backgrounds().orEmpty() }
    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                scope.launch {
                    error = store.addBackground(uri)
                    if (error == null) store.setSystemWallpaper(false)
                    changes++
                }
            }
        }
    OverlayPanel(
        "wallpaper",
        onDismiss,
        actions = {
            val shown = store.background
            if (!system && shown != null && store.isOwnBackground(shown)) {
                BracketAction("remove", Palette.red) {
                    scope.launch {
                        store.removeBackground(shown)
                        changes++
                    }
                }
            }
            if (current != null) {
                BracketAction("add photo", Palette.accent) {
                    error = null
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        },
    ) {
        if (system) {
            BasicText("✓ android's wallpaper is showing", Modifier.padding(vertical = 4.dp), style = Mono.copy(color = Palette.green))
            BasicText(
                "[ change it in android ]",
                Modifier.clickable(onClick = ::pickInSystem).padding(vertical = 8.dp),
                style = Mono.copy(color = Palette.accent),
            )
        } else {
            BasicText(
                "[ use android's wallpaper picker ]",
                Modifier.clickable(onClick = ::pickInSystem).padding(vertical = 8.dp),
                style = Mono.copy(color = Palette.accent),
            )
        }
        if (current == null) {
            BasicText("The themes' wallpapers aren't downloaded yet.", Modifier.padding(vertical = 8.dp), style = Mono.copy(color = Palette.muted))
            return@OverlayPanel
        }
        BasicText("${current.title.lowercase()} backgrounds; photos you add stay with this theme", Modifier.padding(vertical = 4.dp), style = Mono.copy(color = Palette.muted))
        error?.let { BasicText("couldn't add it: $it", Modifier.padding(vertical = 4.dp), style = Mono.copy(color = Palette.red)) }
        LazyVerticalGrid(
            GridCells.Adaptive(100.dp),
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(backgrounds, key = { it.path }) { file ->
                Thumbnail(
                    file,
                    selected = !system && file == store.background,
                    modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
                    onClick = {
                        scope.launch {
                            store.setBackground(file)
                            store.setSystemWallpaper(false)
                        }
                    },
                )
            }
        }
    }
}
