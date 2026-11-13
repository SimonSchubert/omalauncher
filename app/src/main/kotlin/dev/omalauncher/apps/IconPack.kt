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
    /** [component]'s icon from the pack: for that activity, else for its app (an app that switched to another activity alias). */
    fun drawable(
        component: ComponentName,
        densityDpi: Int,
    ): Drawable? {
        val name = names["${component.packageName}/${component.className}"] ?: names[component.packageName] ?: return null
        @Suppress("DiscouragedApi") // Packs name their drawables in appfilter.xml; there are no ids to go by.
        val id = res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } ?: return null
        return runCatching { res.getDrawableForDensity(id, densityDpi, null) }.getOrNull()
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
    }
}
