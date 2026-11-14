package dev.omalauncher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * An installed icon pack (Arcticons, Lawnicons and the like) in the ADW/Nova format most packs use: its appfilter.xml maps
 * an app's launcher activity to one of the pack's drawables. Apps the pack doesn't cover keep their own icon.
 */
class IconPack private constructor(
    val packageName: String,
    private val res: Resources,
    /** Drawable names by "package/class" (unabbreviated), and by package alone for the first entry of each. */
    private val names: Map<String, String>,
) {
    /** [catalog], once drawable.xml (or the appfilter fallback) has been read. */
    private var catalogCache: List<String>? = null

    /** Appfilter's drawable for [component]'s activity, or for its package when only the package is mapped. */
    fun drawableName(component: ComponentName): String? =
        names["${component.packageName}/${component.className}"] ?: names[component.packageName]

    /** [component]'s icon from the pack: for that activity, else for its app (an app that switched to another activity alias). */
    fun drawable(
        component: ComponentName,
        densityDpi: Int,
    ): Drawable? = drawableName(component)?.let { drawable(it, densityDpi) }

    /**
     * [component]'s icon: [override] when the pack actually has that drawable, else the appfilter icon.
     * A blank [override], or a name the pack doesn't ship, falls through to appfilter.
     */
    fun drawable(
        component: ComponentName,
        densityDpi: Int,
        override: String?,
    ): Drawable? {
        if (!override.isNullOrBlank()) drawable(override, densityDpi)?.let { return it }
        return drawable(component, densityDpi)
    }

    /** The pack's [name] drawable at [densityDpi], or null when it has none. */
    fun drawable(
        name: String,
        densityDpi: Int,
    ): Drawable? {
        if (name.isBlank()) return null
        @Suppress("DiscouragedApi") // Packs name their drawables in appfilter.xml; there are no ids to go by.
        val id = res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } ?: return null
        return runCatching { res.getDrawableForDensity(id, densityDpi, null) }.getOrNull()
    }

    /** Whether appfilter maps [component] itself or, failing that, its package. */
    fun covers(component: ComponentName): Boolean = drawableName(component) != null

    /** As [covers]: the pack has an icon for [component] or its package. */
    fun hasIcon(component: ComponentName): Boolean = covers(component)

    /**
     * Drawable names for the picker and the suggest brief: drawable.xml, or the appfilter's names when that
     * file lists none. Read once. Slow the first time: call it off the main thread.
     */
    @Synchronized
    fun catalog(): List<String> = catalogCache ?: catalogNames(runCatching { drawableXmlNames() }.getOrDefault(emptyList()), names.values).also { catalogCache = it }

    /** `<item drawable="..."/>` names from res/xml/drawable.xml, else assets/drawable.xml. */
    private fun drawableXmlNames(): List<String> {
        val fromRes = runCatching { compiledDrawableNames() }.getOrNull().orEmpty()
        if (fromRes.isNotEmpty()) return fromRes
        val text = runCatching { res.assets.open("drawable.xml").use { it.readBytes().decodeToString() } }.getOrNull() ?: return emptyList()
        return drawableNames(text)
    }

    /** Closes the compiled parser even when reading it fails. */
    private fun compiledDrawableNames(): List<String> {
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier("drawable", "xml", packageName)
        if (id == 0) return emptyList()
        val parser = res.getXml(id)
        return try {
            readDrawableItems(parser)
        } finally {
            parser.close()
        }
    }

    companion object {
        private const val TAG = "iconpack"

        /** What packs declare so launchers find them; ADW's is the one nearly all have. */
        private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

        /** The installed icon packs as package name to label, by label. */
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            return ACTIONS
                .flatMap { action -> runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrDefault(emptyList()) }
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
        }

        /** [packageName]'s pack, with its appfilter.xml read; null when it isn't installed or has none. Slow: call it off the main thread. */
        fun load(
            context: Context,
            packageName: String,
        ): IconPack? {
            val res =
                try {
                    context.packageManager.getResourcesForApplication(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    return null
                }
            val names = HashMap<String, String>()
            try {
                val parser = appfilter(res, packageName) ?: return null
                // A compiled one holds the pack's resources until closed.
                try {
                    readAppfilter(parser, names)
                } finally {
                    (parser as? XmlResourceParser)?.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot read $packageName's appfilter.xml", e)
                return null
            }
            return IconPack(packageName, res, names)
        }

        /** The pack's appfilter.xml: compiled in res/xml, or as plain text in its assets. */
        private fun appfilter(
            res: Resources,
            packageName: String,
        ): XmlPullParser? {
            @Suppress("DiscouragedApi")
            val id = res.getIdentifier("appfilter", "xml", packageName)
            if (id != 0) return res.getXml(id)
            val text = runCatching { res.assets.open("appfilter.xml").use { it.readBytes() } }.getOrNull() ?: return null
            return XmlPullParserFactory.newInstance().newPullParser().apply { setInput(text.inputStream(), null) }
        }

        private fun readAppfilter(
            parser: XmlPullParser,
            into: MutableMap<String, String>,
        ) {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                val (pkg, cls) = appfilterComponent(parser.getAttributeValue(null, "component") ?: continue) ?: continue
                val drawable = parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() } ?: continue
                into.putIfAbsent("$pkg/$cls", drawable)
                into.putIfAbsent(pkg, drawable)
            }
        }

        /** An appfilter.xml `component`, `ComponentInfo{pkg/cls}` (the class may start with a dot), as package and full class name; null when it isn't one. */
        fun appfilterComponent(value: String): Pair<String, String>? {
            val inner = value.trim().removePrefix("ComponentInfo{").removeSuffix("}")
            val pkg = inner.substringBefore('/', "").trim()
            val cls = inner.substringAfter('/', "").trim()
            if (pkg.isEmpty() || cls.isEmpty()) return null
            return pkg to if (cls.startsWith('.')) pkg + cls else cls
        }

        private val DRAWABLE_ITEM = Regex("""<item\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
        private val DRAWABLE_ATTR = Regex("""(?:^|\s)drawable\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

        /** Drawable names from a drawable.xml `<item drawable="..."/>` list, in file order. Blank names are skipped. */
        fun drawableNames(xml: String): List<String> {
            val out = LinkedHashSet<String>()
            for (item in DRAWABLE_ITEM.findAll(xml)) {
                val name = DRAWABLE_ATTR.find(item.groupValues[1])?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                out += name
            }
            return out.toList()
        }

        /**
         * Names the picker and the agent see: [fromXml] when drawable.xml listed any, otherwise the appfilter's
         * drawable names. Blanks and duplicates are dropped; the result is sorted.
         */
        fun catalogNames(
            fromXml: List<String>,
            appfilterDrawables: Iterable<String>,
        ): List<String> = (if (fromXml.any { it.isNotBlank() }) fromXml else appfilterDrawables).map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

        /**
         * [names] containing [query], ignoring case, in the same order. A blank [query] (spaces included) keeps every name.
         */
        fun filterDrawableNames(
            names: List<String>,
            query: String,
        ): List<String> {
            val needle = query.trim()
            if (needle.isEmpty()) return names
            return names.filter { it.contains(needle, ignoreCase = true) }
        }

        private fun readDrawableItems(parser: XmlPullParser): List<String> {
            val out = LinkedHashSet<String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                parser.getAttributeValue(null, "drawable")?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
            return out.toList()
        }
    }
}
