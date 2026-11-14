package dev.omalauncher.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.apps.AndroidApp
import dev.omalauncher.apps.IconAppGroups
import dev.omalauncher.apps.IconOverrides
import dev.omalauncher.apps.IconPack
import dev.omalauncher.apps.IconPackPrompts
import dev.omalauncher.apps.iconComponentKey
import dev.omalauncher.apps.partitionIconApps
import dev.omalauncher.apps.suggestBriefRows
import dev.omalauncher.storage.LauncherData
import dev.omalauncher.theme.Style
import dev.omalauncher.ui.BracketAction
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.OverlayPanel
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.SectionLabel
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.frame
import dev.omalauncher.ui.leavesUpDown
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every app's icon from the current pack: overridden, covered by the pack, or neither.
 * A pick, a clear or an accepted suggestion moves the app between those lists on this screen.
 * Suggest still sends only apps the pack doesn't cover and the user hasn't overridden.
 */
@Composable
fun IconPackIconsScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    onAgent: (title: String, command: (bin: String) -> String) -> Unit,
) {
    val context = LocalContext.current
    val overrides = runtime.androidApps.iconOverrides
    val apps = runtime.androidApps.apps
    val dpi = context.resources.displayMetrics.densityDpi
    val packName = runtime.style.style[Style.ICON_PACK]
    val loadedState =
        produceState<IconPack?>(null, packName) {
            value = packName?.let { pkg -> withContext(Dispatchers.IO) { IconPack.load(context, pkg) } }
        }
    val catalogState =
        produceState<List<String>?>(null, loadedState.value) {
            val pack = loadedState.value
            value = pack?.let { withContext(Dispatchers.IO) { it.catalog() } }
        }
    // revision is snapshot state; the override map is not. Reading it here is what moves an app between the lists.
    // Until the pack's appfilter is read, covered and missing stay empty so they don't flash in as missing.
    val groups by remember {
        derivedStateOf {
            overrides.revision
            val pack = runtime.style.style[Style.ICON_PACK]
            val iconPack = loadedState.value
            if (pack == null || iconPack == null) {
                // No pack yet: show overrides only. Putting the rest in "missing" would flash, then move them.
                val mappings: Map<String, String> = if (pack == null) emptyMap() else overrides.forPack(pack)
                IconAppGroups(apps.filter { mappings[iconComponentKey(it.component)] != null }, emptyList(), emptyList())
            } else {
                val loaded = iconPack
                val mappings = overrides.forPack(pack)
                partitionIconApps(
                    apps,
                    hasOverride = { mappings[iconComponentKey(it.component)] != null },
                    covers = { loaded.covers(it.component) },
                )
            }
        }
    }
    var picking by remember { mutableStateOf<AndroidApp?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler {
        when {
            picking != null -> picking = null
            reviewing -> reviewing = false
            else -> onDismiss()
        }
    }
    val suggestTitle = stringResource(R.string.style_icon_pack_suggest)
    val chosen = picking
    val chosenPack = loadedState.value
    when {
        chosen != null && chosenPack != null -> {
            val packId = runtime.style.style[Style.ICON_PACK]
            val current = overrides.revision.let { if (packId == null) null else overrides.get(packId, iconComponentKey(chosen.component)) }
            DrawablePicker(
                pack = chosenPack,
                names = catalogState.value.orEmpty(),
                app = chosen,
                dpi = dpi,
                current = current,
                onDismiss = { picking = null },
                onPick = { name ->
                    val pack = runtime.style.style[Style.ICON_PACK]
                    if (pack != null) {
                        overrides.set(pack, iconComponentKey(chosen.component), name)
                        runtime.androidApps.reloadIcons()
                    }
                    picking = null
                },
                onClear = {
                    val pack = runtime.style.style[Style.ICON_PACK]
                    if (pack != null) {
                        overrides.set(pack, iconComponentKey(chosen.component), null)
                        runtime.androidApps.reloadIcons()
                    }
                    picking = null
                },
            )
        }
        reviewing ->
            SuggestionsReview(
                runtime = runtime,
                pack = runtime.style.style[Style.ICON_PACK].orEmpty(),
                catalog = catalogState.value,
                dpi = dpi,
                iconPack = loadedState.value,
                onDismiss = { reviewing = false },
            )
        else ->
            OverlayPanel(stringResource(R.string.style_icon_pack_manage), onDismiss, onBack = onDismiss) {
                BasicText(
                    suggestTitle,
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val pack = loadedState.value ?: return@clickable
                            val name = runtime.style.style[Style.ICON_PACK] ?: return@clickable
                            val covered = HashSet<String>()
                            for (app in apps) if (pack.covers(app.component)) covered += iconComponentKey(app.component)
                            val rows =
                                suggestBriefRows(
                                    apps.map { it.label to iconComponentKey(it.component) },
                                    overrides.forPack(name).keys,
                                    covered,
                                )
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    IconPackPrompts.writeBrief(
                                        File(LauncherData.styleDir(context), IconPackPrompts.BRIEF),
                                        name,
                                        rows,
                                        pack.catalog(),
                                    )
                                }
                                onAgent(suggestTitle) { bin -> IconPackPrompts.suggestMissingCommand(bin) }
                            }
                        }.padding(vertical = 10.dp),
                    style = Mono.copy(color = Palette.accent),
                )
                BasicText(
                    stringResource(R.string.style_icon_pack_review),
                    Modifier.fillMaxWidth().clickable { reviewing = true }.padding(vertical = 10.dp),
                    style = Mono.copy(color = Palette.accent),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (groups.overridden.isNotEmpty()) {
                        item(key = "overridden") { SectionLabel(stringResource(R.string.style_icon_pack_overridden)) }
                        items(groups.overridden, key = { "o-${it.key}" }) { app ->
                            OverrideRow(overrides, runtime.style.style[Style.ICON_PACK].orEmpty(), app, loadedState.value, dpi) {
                                if (loadedState.value != null) picking = app
                            }
                        }
                    }
                    if (groups.covered.isNotEmpty()) {
                        item(key = "covered") { SectionLabel(stringResource(R.string.style_icon_pack_covered)) }
                        items(groups.covered, key = { "c-${it.key}" }) { app ->
                            PackAppRow(app.label, loadedState.value?.drawableName(app.component), loadedState.value, dpi) {
                                if (loadedState.value != null) picking = app
                            }
                        }
                    }
                    item(key = "missing") { SectionLabel(stringResource(R.string.style_icon_pack_missing)) }
                    items(groups.missing, key = { "m-${it.key}" }) { app ->
                        AppRow(app.label, runtime.androidApps.icons[app.key]) { if (loadedState.value != null) picking = app }
                    }
                }
            }
    }
}

@Composable
private fun OverrideRow(
    overrides: IconOverrides,
    pack: String,
    app: AndroidApp,
    iconPack: IconPack?,
    dpi: Int,
    onClick: () -> Unit,
) {
    // Subscribes to the counter so a new drawable shows without the row leaving the list.
    val drawable = overrides.revision.let { overrides.get(pack, iconComponentKey(app.component)) }
    PackAppRow(app.label, drawable, iconPack, dpi, onClick)
}

@Composable
private fun PackAppRow(
    label: String,
    drawable: String?,
    iconPack: IconPack?,
    dpi: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (drawable != null && iconPack != null) PackDrawable(iconPack, drawable, dpi, 40.dp) else PackIcon(null, 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            BasicText(label, style = Mono.copy(color = Palette.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (drawable != null) BasicText(drawable, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppRow(
    label: String,
    icon: ImageBitmap?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PackIcon(icon, 40.dp)
        BasicText(label, Modifier.padding(start = 12.dp).weight(1f), style = Mono.copy(color = Palette.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DrawablePicker(
    pack: IconPack,
    names: List<String>,
    app: AndroidApp,
    dpi: Int,
    /** The override in effect, or null when this app has none. */
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(names, query) { IconPack.filterDrawableNames(names, query) }
    val grid = rememberLazyGridState()
    // The field starts empty, so the grid is [names] until the catalog arrives and the user types.
    LaunchedEffect(current, names) {
        if (query.isNotBlank()) return@LaunchedEffect
        val mark = current ?: return@LaunchedEffect
        val index = shown.indexOf(mark)
        if (index >= 0) grid.scrollToItem(index)
    }
    OverlayPanel(app.label, onDismiss, onBack = onDismiss) {
        DrawableFilter(query) { query = it }
        if (current != null) BracketAction(stringResource(R.string.style_icon_pack_clear), Palette.accent, onClick = onClear)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(56.dp),
            state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gridItems(shown, key = { it }) { name ->
                val marked = name == current
                Box(
                    Modifier
                        .clickable { onPick(name) }
                        .then(if (marked) Modifier.frame(1.dp, Palette.accent, Palette.accent.copy(alpha = 0.15f), radius = 8.dp) else Modifier)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PackDrawable(pack, name, dpi, 48.dp)
                }
            }
        }
    }
}

@Composable
private fun DrawableFilter(
    query: String,
    onQuery: (String) -> Unit,
) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = Mono,
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        modifier =
            Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .leavesUpDown()
                .clip(LauncherStyle.shape(8.dp))
                .background(Palette.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) BasicText(stringResource(R.string.style_icon_pack_filter), style = Mono.copy(color = Palette.muted))
                inner()
            }
        },
    )
}

@Composable
private fun SuggestionsReview(
    runtime: Runtime,
    pack: String,
    catalog: List<String>?,
    dpi: Int,
    iconPack: IconPack?,
    onDismiss: () -> Unit,
) {
    val overrides = runtime.androidApps.iconOverrides
    var rows by remember(catalog) {
        mutableStateOf(
            catalog?.let { names ->
                val known = names.toHashSet()
                overrides.readSuggestions().filterValues { it in known }.map { it.key to it.value }
            },
        )
    }
    OverlayPanel(stringResource(R.string.style_icon_pack_review), onDismiss, onBack = onDismiss) {
        val shown = rows
        when {
            shown == null -> {}
            shown.isEmpty() -> BasicText(stringResource(R.string.style_icon_pack_no_suggestions), style = Mono.copy(color = Palette.muted))
            else -> {
                BasicText(
                    stringResource(R.string.style_icon_pack_accept_all),
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            overrides.putAll(pack, shown.toMap())
                            overrides.clearSuggestions()
                            runtime.androidApps.reloadIcons()
                            onDismiss()
                        }.padding(vertical = 10.dp),
                    style = Mono.copy(color = Palette.accent),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(shown, key = { it.first }) { (component, drawable) ->
                        val label = runtime.androidApps.apps.firstOrNull { iconComponentKey(it.component) == component }?.label ?: component
                        PackAppRow(label, drawable, iconPack, dpi) {
                            overrides.set(pack, component, drawable)
                            overrides.dropSuggestion(component)
                            rows = rows?.filterNot { it.first == component }
                            runtime.androidApps.reloadIcons()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackDrawable(
    pack: IconPack,
    name: String,
    dpi: Int,
    size: Dp,
) {
    val bitmap by produceState<ImageBitmap?>(null, pack, name, dpi) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { pack.drawable(name, dpi)?.toIconBitmap(96) }.getOrNull()
            }
    }
    // Accent, as the drawer tints a monochrome icon. A pack that follows the system night mode
    // (Arcticons Day & Night draws dark lines in light mode) would otherwise vanish on this panel.
    PackIcon(bitmap, size, tint = true)
}

@Composable
private fun PackIcon(
    icon: ImageBitmap?,
    size: Dp,
    tint: Boolean = false,
) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(icon, null, Modifier.size(size), colorFilter = if (tint) ColorFilter.tint(Palette.accent) else null)
        } else {
            BasicText("·", style = Mono.copy(color = Palette.muted))
        }
    }
}

private fun Drawable.toIconBitmap(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, size, size)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
