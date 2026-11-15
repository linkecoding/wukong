package com.wukong.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ConversationTurn(val id: String, val goal: String, val phase: String, val result: String, val startedAt: Long,
    val logName: String? = null, val elapsedMs: Long = 0)
internal data class Conversation(val id: String, val title: String, val updatedAt: Long, val turns: List<ConversationTurn>)

/** App-private conversation summaries, separate from full diagnostic traces. No automatic replay. */
internal class ConversationStore(context: Context, namespace: String = "") {
    private val prefs = context.getSharedPreferences("conversations$namespace", Context.MODE_PRIVATE)
    init { require(namespace.matches(Regex("(-[a-z]+)?"))) }
    fun version() = prefs.getLong("version", 0)
    fun selected() = prefs.getString("selected", null)
    fun draft(id: String) = prefs.getString("draft-$id", "").orEmpty()
    fun saveDraft(id: String, value: String) { prefs.edit().putString("draft-$id",value.take(4000)).apply() }
    fun select(id: String) { prefs.edit().putString("selected", id).apply() }
    fun read(): List<Conversation> = synchronized(lock) {
        runCatching {
            val array = JSONArray(prefs.getString("threads", "[]"))
            (0 until array.length()).map { n -> array.getJSONObject(n).let { thread ->
                val turns = thread.getJSONArray("turns")
                Conversation(thread.getString("id"), thread.getString("title"), thread.getLong("updatedAt"), (0 until turns.length()).map { i -> turns.getJSONObject(i).let {
                    ConversationTurn(it.getString("id"), it.getString("goal"), it.getString("phase"), it.getString("result"), it.getLong("startedAt"),
                        if (it.isNull("logName")) null else it.getString("logName"), it.optLong("elapsedMs"))
                } })
            } }
        }.getOrDefault(emptyList())
    }
    fun get(id: String) = read().firstOrNull { it.id == id }
    fun create(): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        save(listOf(Conversation(id, "", System.currentTimeMillis(), emptyList())) + read().filter { it.turns.isNotEmpty() })
        select(id); id
    }
    fun begin(id: String, goal: String, logName: String?): String = synchronized(lock) {
        require(goal.isNotBlank() && goal.length <= 4000)
        val all = read(); val thread = all.firstOrNull { it.id == id } ?: error("Conversation no longer exists")
        val turn = ConversationTurn(UUID.randomUUID().toString(), goal, "RUNNING", "", System.currentTimeMillis(), logName)
        save(listOf(thread.copy(title = thread.title.ifBlank { goal.split(Regex("[，。！？!?\n]")).first().take(40) }, updatedAt = turn.startedAt, turns = (thread.turns + turn).takeLast(20))) + all.filter { it.id != id })
        turn.id
    }
    fun finish(id: String, turnId: String, phase: String, result: String, elapsedMs: Long) = synchronized(lock) {
        val all = read(); val thread = all.firstOrNull { it.id == id } ?: return@synchronized
        val updated = thread.copy(updatedAt = System.currentTimeMillis(), turns = thread.turns.map {
            if (it.id == turnId) it.copy(phase = phase, result = result.take(12000), elapsedMs = elapsedMs.coerceAtLeast(0)) else it
        })
        save(listOf(updated) + all.filter { it.id != id })
    }
    /** Prior turns are context, not a command queue, current UI evidence, or durable permissions. */
    fun context(id: String): String = JSONArray(get(id)?.turns.orEmpty().takeLast(6).map {
        JSONObject().put("request", it.goal.take(2000)).put("phase", it.phase).put("result", it.result.take(2000))
    }).toString()
    private fun save(threads: List<Conversation>) {
        val json = JSONArray(threads.take(20).map { thread -> JSONObject().put("id",thread.id).put("title",thread.title).put("updatedAt",thread.updatedAt)
            .put("turns", JSONArray(thread.turns.map { JSONObject().put("id",it.id).put("goal",it.goal).put("phase",it.phase).put("result",it.result)
                .put("startedAt",it.startedAt).put("logName",it.logName ?: JSONObject.NULL).put("elapsedMs",it.elapsedMs) })) })
        val edit=prefs.edit().putString("threads",json.toString()).putLong("version",version()+1)
        val retained=threads.take(20).map { "draft-${it.id}" }.toSet()
        prefs.all.keys.filter { it.startsWith("draft-") && it !in retained }.forEach { edit.remove(it) }
        check(edit.commit())
    }
    companion object { private val lock = Any() }
}
