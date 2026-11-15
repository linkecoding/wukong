package com.wukong.phone

import android.content.Context

/** Keep the user's selected task across setup screens and process restarts. Never auto-run it. */
class TaskDraftStore(context: Context) {
    private val preferences = context.getSharedPreferences("task_draft", Context.MODE_PRIVATE)
    fun goal() = preferences.getString("goal", "").orEmpty()
    fun packageName() = preferences.getString("package", null)
    fun save(goal: String, packageName: String?) {
        preferences.edit().putString("goal", goal.take(4000)).putString("package", packageName).apply()
    }
    fun clear() { preferences.edit().clear().apply() }
}

object TaskTemplates {
    data class Template(val title: Int, val hint: Int, val prompt: Int)
    val all = listOf(
        Template(R.string.sample_file, R.string.sample_file_hint, R.string.sample_file_prompt),
        Template(R.string.sample_web, R.string.sample_web_hint, R.string.sample_web_prompt),
        Template(R.string.sample_app, R.string.sample_app_hint, R.string.sample_app_prompt)
    )
}
