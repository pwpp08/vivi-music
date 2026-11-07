package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app language, chosen inside the app. On Android 13+ it is stored with the system (the same
 * setting as Settings > Apps > Trackhop > Language); before that in preferences, applied by wrapping
 * every activity's base context. In both cases the current activity is recreated at once, so the
 * change is visible immediately even while a sync is running.
 */
object AppLocale {
    /** Language tags offered, in display order; "" is the system language. */
    val CHOICES = listOf("", "en", "it", "fr", "de", "es", "pt-BR", "pl", "ru", "tr", "zh", "ja", "ko")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_locale", Context.MODE_PRIVATE)

    /** Current choice as a language tag, or "" for the system language. */
    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language ?: ""
        } else prefs(ctx).getString("tag", "") ?: ""

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = activity.getSystemService(LocaleManager::class.java)
            lm?.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString("tag", tag).apply()
        }
        activity.recreate()
    }

    /** For attachBaseContext on Android 12 and older: resources in the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString("tag", "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** Name of a choice in its own language. */
    fun label(ctx: Context, tag: String): String = when (tag) {
        "" -> ctx.getString(com.xlollx.songport.R.string.th_settings_language_system)
        else -> Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
    }
}
