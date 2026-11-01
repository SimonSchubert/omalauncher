package dev.omalauncher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.LocaleList
import android.view.ContextThemeWrapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * The language the launcher shows itself in: the phone's, or one picked in settings. Widgets follow it too, since their
 * strings { } and lang() read the default Locale.
 *
 * Not Android's own per-app language: changing that while a settings or files task is alive crashes Android's Settings
 * for a home app ("Can't change activity type once set"). So the launcher keeps the pick itself and puts it over the
 * app's resources ([resources]), each activity's ([applyTo]) and the default Locale.
 */
object AppLanguage {
    /** The languages the launcher has its text in, as language tags; more come with their translations. */
    val supported = listOf("en", "id", "es", "pt", "de", "zh")

    private const val KEY = "language"

    private val picked = MutableStateFlow<String?>(null)

    /** The picked language's tag, or null for the phone's. */
    val language: StateFlow<String?> get() = picked

    /** The app's resources in the picked language, made once per pick. */
    private var localized: Pair<String, Resources>? = null

    private val activities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /** Reads the pick, as the process starts, and recreates the activities when it changes. */
    fun load(
        app: Application,
        base: Context,
    ) {
        picked.value = LauncherData.prefs(base).getString(KEY, null)?.takeIf { it in supported }
        applyDefault()
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    activities += activity
                }

                override fun onActivityDestroyed(activity: Activity) {
                    activities -= activity
                }

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit
            },
        )
    }

    /** Picks [language], or the phone's for null: saved, and every open screen comes back in it. */
    fun pick(
        context: Context,
        language: String?,
    ) {
        if (language == picked.value) return
        LauncherData.prefs(context).edit().putString(KEY, language).apply()
        picked.value = language
        if (language == null) phoneDefault() else applyDefault()
        activities.toList().forEach(Activity::recreate)
    }

    /**
     * The default Locale in the picked language. Android sets it back to the phone's on every configuration change
     * (turning the phone, dark mode), so the app calls this again before passing the change on.
     */
    fun applyDefault() {
        val tag = picked.value ?: return
        val locale = Locale.forLanguageTag(tag)
        if (Locale.getDefault() != locale) LocaleList.setDefault(LocaleList(locale))
    }

    /** Back to the phone's language as the default Locale: Android itself only sets it on a configuration change. */
    private fun phoneDefault() = LocaleList.setDefault(Resources.getSystem().configuration.locales)

    /** The app's [base] resources, or the same in the picked language; they follow configuration changes as [base] does. */
    fun resources(
        context: Context,
        base: Resources,
    ): Resources {
        val tag = picked.value ?: return base
        localized?.takeIf { it.first == tag }?.let { return it.second }
        return context.createConfigurationContext(overrides(tag)).resources.also { localized = tag to it }
    }

    /** Puts the picked language over [activity]'s resources; called from its attachBaseContext. */
    fun applyTo(activity: ContextThemeWrapper) {
        picked.value?.let { activity.applyOverrideConfiguration(overrides(it)) }
    }

    private fun overrides(tag: String) = Configuration().apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }

    /**
     * [code] in two letters where it has them ("deu" → "de"), as the launcher and speech recognizers name languages:
     * some text-to-speech engines name theirs in three.
     */
    fun short(code: String): String = threeLetters[code.lowercase(Locale.ROOT)] ?: code

    private val threeLetters by lazy {
        Locale.getISOLanguages().mapNotNull { l -> runCatching { Locale(l).isO3Language to l }.getOrNull() }.toMap()
    }

    /** String [id] in [locale] when the launcher has its text in that language, else null. */
    fun stringIn(
        context: Context,
        locale: Locale,
        id: Int,
        vararg args: Any,
    ): String? {
        if (supported.none { Locale.forLanguageTag(it).language == locale.language }) return null
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).getString(id, *args)
    }

    /** [tag]'s name in its own language: "English", "Indonesia". */
    fun name(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
