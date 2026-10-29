package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import dev.omalauncher.LauncherData
import java.io.File
import java.util.Locale

private const val TAG = "locale"

/**
 * Linux apps run in the phone's language and region: Android's locale
 * (en_GB, de_DE, ...) as LANG, generated in the guest when it isn't yet.
 * Without it every app got C.UTF-8, and some refuse to start in it
 * (wxWidgets ones like PrusaSlicer), or show dates and numbers the wrong way.
 * Falls back to C.UTF-8 when the guest can't make the locale.
 */
class GuestLocale(
    context: Context,
    private val proot: Proot,
) {
    private val prefs = LauncherData.prefs(context)

    init {
        // The one that worked last time, so apps get it before this start's check has run.
        prefs.getString(LANG, null)?.let { proot.lang = it }
    }

    /** The phone locale last checked: a configuration change that isn't a language change (a rotation) does nothing. */
    @Volatile
    private var checked: Locale? = null

    /** Makes the phone's locale the guest's: generates it if needed. Call with Linux up, off the main thread. */
    @Synchronized
    fun sync() {
        val locale = Locale.getDefault()
        if (locale == checked) return
        checked = locale
        val wanted = glibcName(locale) ?: return use(FALLBACK)
        val code =
            runCatching { proot.run(script(wanted)) { Log.i(TAG, it) } }.getOrElse {
                Log.w(TAG, "checking $wanted failed", it)
                -1
            }
        use(if (code == 0) wanted else FALLBACK)
    }

    private fun use(lang: String) {
        proot.lang = lang
        prefs.edit().putString(LANG, lang).apply()
        // Login shells (terminals) read it from here, and would otherwise put C.UTF-8 back.
        runCatching { File(proot.rootfs, "etc/locale.conf").writeText("LANG=$lang\n") }
    }

    companion object {
        private const val LANG = "guest_lang"
        const val FALLBACK = "C.UTF-8"

        /** "en_GB.UTF-8" for English (UK); null without a region, which glibc locales need. */
        fun glibcName(locale: Locale): String? {
            val language = locale.language.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: return null
            val country = locale.country.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: return null
            return "${language}_$country.UTF-8"
        }

        /** Succeeds once [name] is generated: enables it in locale.gen and runs locale-gen if glibc knows it. */
        fun script(name: String): String {
            val short = name.substringBefore('.')
            // locale -a lists "en_GB.utf8"; SUPPORTED lists "en_GB.UTF-8 UTF-8".
            val listed = "${short}.utf8"
            return """
                locale -a 2>/dev/null | grep -qx '$listed' && exit 0
                grep -qx '$name UTF-8' /usr/share/i18n/SUPPORTED 2>/dev/null || exit 1
                grep -qx '$name UTF-8' /etc/locale.gen || echo '$name UTF-8' >> /etc/locale.gen
                locale-gen >/dev/null && locale -a | grep -qx '$listed'
            """.trimIndent()
        }
    }
}
