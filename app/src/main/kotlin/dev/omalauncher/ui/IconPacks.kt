package dev.omalauncher.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser

/**
 * An icon pack app: another launcher's set of app icons, as packs are shared between them all. Each maps components
 * to drawables of its own in an `appfilter.xml` (in its assets or its `res/xml`), optionally with a background
 * (`iconback`), a mask (`iconmask`) and an overlay (`iconupon`), and a `scale` for the icon itself.
 * Drawn icons are cached: the list is short, and composing the layers again per icon would be slow.
 */
class IconPack(
    context: Context,
    val packageName: String,
    val label: String,
) {
    private val resources: Resources = context.packageManager.getResourcesForApplication(packageName)

    /** Component, as [normalize] makes it, -> drawable name. */
    private val entries = HashMap<String, String>()
    private var back: String? = null
    private var mask: String? = null
    private var upon: String? = null
    private var scale: Float = 1f

    /** Component -> the pack's icon, composed and layered; null for components the pack has none for. */
    private val cache = HashMap<String, Drawable?>()

    init {
        parse()
    }

    /**
     * Reads the pack's `appfilter.xml`: `assets/appfilter.xml` for most packs, `res/xml/appfilter.xml` for the rest.
     * Either source can be all a pack has; one with no entries means an unusable pack.
     */
    private fun parse() {
        var assets: java.io.InputStream? = null
        val fromAssets =
            runCatching {
                resources.assets.open("appfilter.xml").also { assets = it }.let { stream -> Xml.newPullParser().apply { setInput(stream, null) } }
            }.getOrNull()
        if (fromAssets != null) {
            runCatching { read(fromAssets) }.onFailure { Log.w(TAG, "cannot read $packageName's appfilter", it) }
            runCatching<Unit> { assets?.close() }
            if (entries.isNotEmpty()) return
        }
        val xmlId = runCatching { resources.getIdentifier("appfilter", "xml", packageName) }.getOrDefault(0)
        if (xmlId != 0) {
            val fromXml = runCatching { resources.getXml(xmlId) }.getOrNull()
            if (fromXml != null) {
                runCatching { read(fromXml) }.onFailure { Log.w(TAG, "cannot read $packageName's appfilter", it) }
                runCatching<Unit> { fromXml.close() }
            }
        }
    }

    /** Whether the pack's `appfilter.xml` had any entries: a declared pack without one draws nothing. */
    val hasEntries get() = entries.isNotEmpty()

    private fun read(parser: XmlPullParser) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) entries[normalize(component)] = drawable
                    }
                    "scale" -> parser.getAttributeValue(null, "factor")?.toFloatOrNull()?.let { scale = it.coerceIn(0.1f, 1f) }
                    "iconback" -> parser.getAttributeValue(null, "img")?.let { back = it }
                    "iconmask" -> parser.getAttributeValue(null, "img")?.let { mask = it }
                    "iconupon" -> parser.getAttributeValue(null, "img")?.let { upon = it }
                }
            }
            event = parser.next()
        }
    }

    /** The pack's icon for [component], or null when the pack has none, so the app's own icon stays. */
    fun iconFor(component: ComponentName): Drawable? = cache.getOrPut(component.flattenToString()) { load(component) }

    private fun load(component: ComponentName): Drawable? {
        // The activity an app opens from the drawer, or the one the icon was drawn for.
        val name = entries[component.flattenToString()] ?: entries[component.flattenToShortString()] ?: return null
        val icon = drawable(name) ?: return null
        val back = drawable(back)
        val mask = drawable(mask)
        val upon = drawable(upon)
        if (back == null && mask == null && upon == null && scale == 1f) return icon
        return compose(icon, back, mask, upon, scale)
    }

    private fun drawable(name: String?): Drawable? {
        if (name.isNullOrBlank()) return null
        // Packs name drawables plainly ("gmail"), or with their package or resource ("com.pack.gmail/icon").
        val plain = name.substringAfterLast('/').removePrefix("@").substringAfterLast('/')
        val id = listOf("drawable", "mipmap").map { resources.getIdentifier(plain, it, packageName) }.firstOrNull { it != 0 } ?: return null
        return runCatching { resources.getDrawable(id, null) }.getOrNull()
    }

    /** [icon] at [scale] over [back], cut out by [mask] and covered by [upon], the layers icon packs compose. */
    private fun compose(
        icon: Drawable,
        back: Drawable?,
        mask: Drawable?,
        upon: Drawable?,
        scale: Float,
    ): Drawable {
        val size = (108 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val behind = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(behind).run {
            back?.let { it.setBounds(0, 0, size, size); it.draw(this) }
            val inset = (size * (1f - scale) / 2f).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(this)
        }
        val composed =
            if (mask == null) {
                behind
            } else {
                // The mask's alpha keeps the icon and the background where it is and drops the rest.
                val cut = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                Canvas(layer).run { mask.setBounds(0, 0, size, size); mask.draw(this) }
                Canvas(cut).run {
                    drawBitmap(behind, 0f, 0f, null)
                    drawBitmap(layer, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
                }
                cut
            }
        Canvas(composed).run { upon?.let { it.setBounds(0, 0, size, size); it.draw(this) } }
        return BitmapDrawable(resources, composed)
    }

    companion object {
        private const val TAG = "omalauncher-pack"

        /** `ComponentInfo{pkg/cls}` or the short form to one key, so spellings of the same activity map alike. */
        fun normalize(component: String) = component.removePrefix("ComponentInfo{").removeSuffix("}")
    }
}

/**
 * The icon packs installed on the phone, and the one chosen. Packs declare themselves with another launcher's
 * theme action (see the manifest's `<queries>`); Android hides them from the app otherwise.
 */
class IconPackStore(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val packageManager = context.packageManager

    /** Installed packs; the apps' own icons stay with none chosen. */
    val installed = mutableStateListOf<IconPack>()

    /** The chosen pack; null keeps every app's own icon. */
    var chosen: IconPack? by mutableStateOf(null)
        private set

    /** The pack [component]'s icon comes from, or null for its own. */
    fun iconFor(component: ComponentName): Drawable? = chosen?.iconFor(component)

    /**
     * Reloads [installed] and applies the saved choice, and reports whether what is drawn changed: the chosen pack
     * itself, or an update of it (a pack that was uninstalled falls back to the apps' own icons).
     */
    fun refresh(): Boolean {
        val packs = findPacks()
        installed.clear()
        installed += packs
        val saved = prefs.getString(PACK, null)
        val pack = packs.firstOrNull { it.packageName == saved }
        val changed = pack?.packageName != chosen?.packageName || stampOf(pack) != prefs.getLong(STAMP, 0L)
        chosen = pack
        prefs.edit().putLong(STAMP, stampOf(pack)).apply()
        return changed
    }

    /** Picks [pack] (null for the apps' own icons) and saves it; the caller redraws the icons. */
    fun choose(pack: IconPack?) {
        chosen = pack
        prefs.edit().putString(PACK, pack?.packageName).apply()
    }

    /** Every pack on the phone that can be drawn from: declared as a launcher's theme, with an appfilter to read. */
    private fun findPacks(): List<IconPack> {
        val found = LinkedHashSet<String>()
        // Most launchers share packs through `MAIN` + a theme category; a few through a theme action.
        for (theme in PACK_THEMES) {
            val asCategory = Intent(Intent.ACTION_MAIN).addCategory(theme)
            val asAction = Intent(theme)
            for (intent in listOf(asCategory, asAction)) {
                val listed = runCatching { packageManager.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
                for (info in listed) found += info.activityInfo.packageName
            }
        }
        return found.mapNotNull { pkg ->
            val label = labelOf(pkg) ?: return@mapNotNull null
            val pack = runCatching { IconPack(context, pkg, label) }.getOrNull()
            pack?.takeIf { it.hasEntries }
        }.sortedBy { it.label.lowercase() }
    }

    private fun labelOf(packageName: String): String? =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** When [pack] was last updated, to notice an update of it; 0 with no pack. */
    private fun stampOf(pack: IconPack?) =
        pack?.let { runCatching { packageManager.getPackageInfo(it.packageName, 0).lastUpdateTime }.getOrDefault(0L) } ?: 0L

    private companion object {
        const val PACK = "icon_pack"

        /** When [PACK]'s pack was last updated, so an update of it redraws the icons too. */
        const val STAMP = "icon_pack_stamp"

        /** The themes icon packs declare themselves with, shared between launchers this way. */
        val PACK_THEMES =
            listOf(
                "com.novalauncher.THEME",
                "org.adw.launcher.icons.THEMES",
                "com.gau.go.launcherex.theme",
                "com.anddoes.launcher.THEME",
                "com.teslacoilsw.launcher.THEME",
                "com.fede.launcher.THEME_ICONPACK",
                "com.motorola.launcher3.THEME",
                "com.sonymobile.home.THEME",
            )
    }
}
