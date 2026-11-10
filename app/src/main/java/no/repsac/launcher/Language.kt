package no.repsac.launcher

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The launcher's own language: follow the TV, or always English / Norwegian.
 * Each screen wraps its context with this in attachBaseContext, so texts, dates and plurals
 * all use the chosen language.
 */
object Language {

    /** Saved value, what it's called (in its own language, so you can always find it), and its locale */
    class Option(val key: String, val label: Int /* string resource */, val tag: String?)

    val OPTIONS = listOf(
        Option("system", R.string.lang_system, null),
        Option("en", R.string.lang_english, "en"),
        Option("nb", R.string.lang_norwegian, "nb"),
    )

    fun wrap(base: Context): Context {
        val key = base.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("app_language", "system")
        val tag = OPTIONS.find { it.key == key }?.tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}
