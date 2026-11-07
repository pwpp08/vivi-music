package com.xlollx.songport.ytmbridge

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** App language chosen in-app: system setting on Android 13+, preferences before; applied at once. */
object AppLocale {
    val CHOICES = listOf("", "en", "it", "fr", "de")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_locale", Context.MODE_PRIVATE)

    fun current(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language ?: ""
        } else prefs(ctx).getString("tag", "") ?: ""

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString("tag", tag).apply()
        }
        activity.recreate()
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString("tag", "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }

    fun label(ctx: Context, tag: String): String = when (tag) {
        "" -> ctx.getString(R.string.th_bridge_language_system)
        else -> Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
    }
}
