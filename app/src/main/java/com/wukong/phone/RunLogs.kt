package com.wukong.phone

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Bounded, app-private JSONL traces. Never records request headers or raw screen contents. */
class RunLogStore(context: Context, private val namespace: String = "") {
    init { require(namespace.matches(Regex("(-[a-z]+)?"))) }
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("diagnostics$namespace", 0)
    private val directory get() = File(app.filesDir, "run-logs$namespace").apply { mkdirs() }
    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    fun files(): List<File> = synchronized(lock) { directory.listFiles()?.filter { it.extension == "jsonl" }?.sortedByDescending { it.name }.orEmpty() }
    fun read(name: String, maxEvents: Int = 1000): List<JSONObject> = synchronized(lock) {
        require(name.matches(Regex("[0-9]+-[a-f0-9-]+\\.jsonl")))
        require(maxEvents in 1..20000)
        val file = File(directory, name)
        if (!file.exists()) emptyList() else file.useLines { lines -> lines.take(maxEvents).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList() }
    }
    fun delete(name: String) = synchronized(lock) {
        require(name.matches(Regex("[0-9]+-[a-f0-9-]+\\.jsonl")))
        File(directory, name).delete()
    }

    /** New tasks keep an exact reference; legacy matches require one unique finished run. */
    fun forTask(task: TaskRecord): String? {
        task.logName?.let { name -> return files().firstOrNull { it.name == name }?.name }
        if (task.logAssociationKnown) return null
        return files().filter { file ->
            val records = read(file.name)
            val start = records.firstOrNull { it.optString("type") == "start" }?.optJSONObject("data")
            val end = records.lastOrNull { it.optString("type") == "end" }
            start?.optString("kind") == "task" && start.optString("goal") == task.goal && end != null &&
                kotlin.math.abs(end.optLong("time") - task.timestamp) <= 5000 &&
                end.optJSONObject("data")?.optString("phase") == task.phase
        }.singleOrNull()?.name
    }

    fun clear() = synchronized(lock) { files().forEach { it.delete() }; Unit }

    fun start(kind: String, goal: String, settings: ModelSettings): RunLog? {
        if (!enabled) return null
        return runCatching {
            synchronized(lock) {
                val file = File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.jsonl")
                val trace = RunLog(this, file, settings.apiKey)
                trace.record("start", JSONObject().put("kind", kind).put("goal", goal).put("model", settings.model).put("thinking", settings.thinkingEnabled))
                files().drop(20).forEach { it.delete() }
                trace
            }
        }.getOrNull()
    }

    internal fun append(file: File, value: JSONObject, secret: String) {
        // Logging failure must never change whether a device action is performed.
        runCatching {
            synchronized(lock) {
                if (!enabled || file.length() >= 512 * 1024) return
                val safe = redact(value, secret).toString()
                if (file.length() + safe.toByteArray(Charsets.UTF_8).size + 1 <= 512 * 1024) file.appendText(safe + "\n", Charsets.UTF_8)
            }
        }
    }
    private fun redact(value: Any?, secret: String): Any? = when (value) {
        is JSONObject -> JSONObject().also { copy -> value.keys().forEach { key -> copy.put(key, redact(value.get(key), secret)) } }
        is JSONArray -> JSONArray().also { copy -> for (index in 0 until value.length()) copy.put(redact(value.get(index), secret)) }
        is String -> if (secret.isEmpty()) value else value.replace(secret, "[已隐藏]")
        else -> value
    }
    companion object { private val lock = Any() }
}

class RunLog internal constructor(private val store: RunLogStore, private val file: File, private val secret: String) {
    val name get() = file.name
    @Volatile var step: Int = 0
    fun record(type: String, data: JSONObject = JSONObject()) {
        if (type != "start" && !data.has("step")) data.put("step", step)
        store.append(file, JSONObject().put("time", System.currentTimeMillis()).put("type", type).put("data", data), secret)
    }
}
