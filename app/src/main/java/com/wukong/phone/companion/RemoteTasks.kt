package com.wukong.phone.companion

import android.content.Context
import com.wukong.phone.*
import org.json.JSONArray
import org.json.JSONObject

/** Persist the reservation before execution: a lost response must never replay a task. */
internal object RemoteTasks {
    @Volatile private var activeId: String? = null
    private val lock = Any()
    private fun prefs(c: Context) = c.getSharedPreferences("companion-tasks", 0)
    fun validate(request: JSONObject) {
        require(request.getString("id").matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "id must be a lowercase UUID" }
        require(request.getString("goal").trim().length in 1..4000) { "goal must contain 1–4000 characters" }
        require(request.optString("display", "virtual") in setOf("virtual", "physical")) { "display must be virtual or physical" }
        require(request.optString("initialPackage", "").length <= 256) { "Invalid initialPackage" }
    }
    fun start(c: Context, request: JSONObject, displayId: Int): JSONObject = synchronized(lock) {
        validate(request)
        val id = request.getString("id")
        require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "id must be a lowercase UUID" }
        val canonical = JSONObject().put("goal", request.getString("goal").trim()).put("display", request.optString("display", "virtual"))
            .put("initialPackage", request.optString("initialPackage", ""))
        val requestHash = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val old = prefs(c).getString(id, null)?.let(::JSONObject)
        if (old != null) {
            check(old.getString("request") == requestHash || old.getString("request") == canonical.toString()) { "Request ID already belongs to another task" }
            return@synchronized get(c, id)
        }
        check(!TaskRuntime.active) { "Another task is running" }
        require(canonical.getString("goal").length in 1..4000) { "goal must contain 1–4000 characters" }
        val settings = SettingsStore(c).load() ?: error("Configure a model first")
        settings.validate()
        val conversation = ConversationStore(c).create()
        val receipt = JSONObject().put("id", id).put("request", requestHash).put("goal", canonical.getString("goal"))
            .put("display", canonical.getString("display")).put("conversationId", conversation).put("createdAt", System.currentTimeMillis())
            .put("phase", "PREPARING").put("result", "")
        save(c, id, receipt)
        activeId = id
        try {
            TaskRuntime.start(c, canonical.getString("goal"), canonical.getString("initialPackage").ifBlank { null }, settings,
                conversation, targetDisplayId = displayId, onFinished = { record -> synchronized(lock) {
                    val finished = JSONObject(prefs(c).getString(id, null)!!).put("phase", record.phase).put("result", record.result)
                        .put("finishedAt", record.timestamp).put("logName", record.logName ?: JSONObject.NULL)
                    save(c, id, finished)
                    compact(c)
                    if (activeId == id) activeId = null
                } })
            save(c, id, receipt.put("phase", "RUNNING"))
        } catch (error: Exception) {
            save(c, id, receipt.put("phase", "FAILED").put("result", error.message ?: "Start failed"))
            activeId = null
            throw error
        }
        get(c, id)
    }
    fun get(c: Context, id: String): JSONObject = synchronized(lock) {
        val saved = prefs(c).getString(id, null) ?: throw NoSuchElementException("Unknown task")
        val receipt = JSONObject(saved)
        if (receipt.getString("phase") in setOf("RUNNING", "PREPARING")) {
            if (activeId == id) {
                val event = TaskRuntime.event
                receipt.put("phase", if (TaskRuntime.pending != null) "AWAITING_APPROVAL" else if (TaskRuntime.stopRequested) "STOPPING" else event.phase.name)
                    .put("message", event.message).put("step", event.step).put("displayId", TaskRuntime.displayId)
                    .put("events", JSONArray(TaskRuntime.events().takeLast(30).map { JSONObject().put("phase", it.phase.name).put("step", it.step).put("message", it.message) }))
            } else {
                receipt.put("phase", "INTERRUPTED").put("result", "App restarted before the task receipt completed. Inspect the phone before submitting another task.")
                save(c, id, receipt)
            }
        }
        receipt.remove("request")
        receipt
    }
    fun existing(c: Context, request: JSONObject): JSONObject? = synchronized(lock) {
        val id = request.getString("id")
        if (!prefs(c).contains(id)) null else start(c, request, 0)
    }
    fun control(c: Context, id: String, action: String): JSONObject = synchronized(lock) {
        get(c, id)
        check(activeId == id && TaskRuntime.active) { "Task is no longer running" }
        when (action) {
            "stop" -> TaskRuntime.stop()
            "takeover", "resume" -> {
                check(TaskRuntime.displayId > 0) { "Takeover controls require a virtual display" }
                check(VirtualScreenState.service != null) { "Virtual display is unavailable" }
                VirtualScreenState.control(action == "takeover")
            }
            else -> error("Unknown control")
        }
        get(c, id)
    }
    fun logName(c: Context, id: String): String? = synchronized(lock) {
        val task = get(c, id)
        if (task.optBoolean("archived")) return@synchronized null
        task.optString("logName").takeIf { it.isNotBlank() && it != "null" }
            ?: ConversationStore(c).get(task.getString("conversationId"))?.turns?.lastOrNull()?.logName
    }
    fun virtualHome(c: Context, id: String): JSONObject = synchronized(lock) {
        get(c,id)
        check(activeId==id&&TaskRuntime.active&&TaskRuntime.displayId>0&&VirtualScreenState.manual) { "Take over this virtual task first" }
        VirtualScreenState.input(JSONObject().put("kind","home"))
        get(c,id)
    }
    fun stopOwned() { if (activeId != null) TaskRuntime.stop() }
    /** Keep compact ID/hash tombstones so archiving old details never allows duplicate execution. */
    private fun compact(c: Context) {
        val detailed = prefs(c).all.values.mapNotNull { (it as? String)?.let(::JSONObject) }
            .filter { !it.optBoolean("archived") && it.optString("phase") !in setOf("RUNNING", "PREPARING") }
            .sortedByDescending { it.optLong("createdAt") }
        detailed.drop(200).forEach { old ->
            val archive = JSONObject().put("id", old.getString("id")).put("request", old.getString("request"))
                .put("phase", old.getString("phase")).put("createdAt", old.optLong("createdAt")).put("archived", true)
                .put("result", "Detailed receipt archived; the original request will not execute again.")
            save(c, old.getString("id"), archive)
        }
    }
    private fun save(c: Context, id: String, value: JSONObject) { check(prefs(c).edit().putString(id, value.toString()).commit()) }
}
