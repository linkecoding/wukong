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
        Template(R.string.sample_compare, R.string.sample_compare_hint, R.string.sample_compare_prompt),
        Template(R.string.sample_food, R.string.sample_food_hint, R.string.sample_food_prompt),
        Template(R.string.sample_trip, R.string.sample_trip_hint, R.string.sample_trip_prompt)
    )
}
