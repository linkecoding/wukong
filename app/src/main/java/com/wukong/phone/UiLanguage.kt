package com.wukong.phone

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/** Follow the system by default; explicit language choices remain persistent. */
object UiLanguage {
    fun code(context: Context): String = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        .getString("language", "system").let { if (it == "en" || it == "zh-CN") it else "system" }

    fun effectiveCode(context: Context): String = when (val choice = code(context)) {
        "system" -> if (Resources.getSystem().configuration.locales[0].language == "zh") "zh-CN" else "en"
        else -> choice
    }

    fun set(context: Context, value: String) {
        require(value == "system" || value == "en" || value == "zh-CN")
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", value).apply()
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(effectiveCode(context))
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}

abstract class LocalizedActivity : Activity() {
    override fun onStart() { super.onStart(); VirtualScreenState.activityStarted(this) }
    override fun onStop() { VirtualScreenState.activityStopped(this); super.onStop() }
    override fun onDestroy() { VirtualScreenState.activityStopped(this); super.onDestroy() }
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))
}

abstract class LocalizedService : Service() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))
}
