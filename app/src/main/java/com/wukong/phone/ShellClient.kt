package com.wukong.phone

import android.content.Context
import com.wukong.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Local authenticated transport. Model credentials never enter the shell process. */
class ShellClient(context: Context, private val credentialOverride: String? = null) {
    private val appContext = context.applicationContext
    fun request(method: String, fields: JSONObject = JSONObject()): Any {
        check(!Thread.currentThread().isInterrupted) { "请求已停止" }
        val token = credentialOverride ?: appContext.getSharedPreferences("shell", 0).getString("token", null)
            ?: error("执行引擎尚未激活，请进入“手机内激活”")
        val request = fields.put("method", method).put("token", token).toString()
        require(request.toByteArray(Charsets.UTF_8).size <= 65_535) { "设备请求过大" }
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
                socket.soTimeout = 30_000 // Allows bounded UI connection recovery during app transitions.
                socket.getOutputStream().write((request + "\n").toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                val out = java.io.ByteArrayOutputStream()
                val input = java.io.BufferedInputStream(socket.getInputStream())
                while (out.size() < 4_194_304) {
                    val next = input.read()
                    if (next < 0 || next == 10) break
                    out.write(next)
                }
                require(out.size() < 4_194_304) { "设备响应过大" }
                val response = JSONObject(out.toString("UTF-8"))
                check(response.getBoolean("ok")) { response.optString("error", "设备操作失败") }
                return response.get("result")
            }
        } catch (e: java.io.IOException) { throw IllegalStateException("执行引擎连接不可用，请在手机内重新激活") }
    }
    fun status(): JSONObject = (request("status") as JSONObject).also {
        check(it.getInt("uid") == 2000 && it.getInt("protocol") == 2 && it.getString("engine") == "app_process" && it.optBoolean("uiEvents") && it.optBoolean("inspection")) { "执行引擎需更新，请在手机内重新激活" }
    }
    companion object { const val PORT = 43219 }
}

class ShellSession(context: Context, val displayId: Int = 0) : AutoCloseable {
    internal val appContext = context.applicationContext
    private val client = ShellClient(context)
    val id: String = UUID.randomUUID().toString()
    init { client.status(); client.request("beginTask", JSONObject().put("taskId", id).put("displayId", displayId)) }
    fun request(method: String, fields: JSONObject = JSONObject()) = client.request(method, fields.put("taskId", id))
    fun cancel() { client.request("cancelTask", JSONObject().put("taskId", id)) }
    override fun close() { client.request("finishTask", JSONObject().put("taskId", id)) }
}

fun JSONObject.toScreen(): Screen = Screen(getString("package"), getString("fingerprint"), getJSONArray("nodes").toNodes(), uiRevision = optLong("uiRevision", 0), uiQuietMillis = optLong("uiQuietMs", Long.MAX_VALUE), uiEvents = optBoolean("uiEvents"), truncated = optBoolean("truncated"))
private fun JSONArray.toNodes() = (0 until length()).map { index -> getJSONObject(index).let {
    UiNode(it.getInt("id"), it.getString("text"), it.getBoolean("editable"), it.getBoolean("clickable"), it.getBoolean("scrollable"), it.getBoolean("sensitive"), it.optJSONObject("bounds")?.toBounds(),
        it.optString("resourceId"), it.optString("className"), if (it.isNull("parentId")) null else it.getInt("parentId"),
        it.optBoolean("enabled", true), if (it.isNull("checked")) null else it.getBoolean("checked"), it.optBoolean("selected"), if (it.isNull("rawText")) null else it.getString("rawText"))
} }
fun JSONObject.toBounds() = Bounds(getInt("left"), getInt("top"), getInt("right"), getInt("bottom"))
fun Action.toJson(): JSONObject = JSONObject().apply {
    when (val action = this@toJson) {
        is Action.Query -> put("kind", "query").put("selector", action.selector.toJson()).put("limit", action.limit).put("offset", action.offset)
        is Action.WaitUntil -> put("kind", "wait_until").put("conditions", JSONArray(action.conditions.map { it.toJson() })).put("matchAll", action.matchAll).put("timeoutMs", action.timeoutMillis)
        is Action.Verify -> put("kind", "verify").put("conditions", JSONArray(action.conditions.map { it.toJson() })).put("matchAll", action.matchAll)
        is Action.Tap -> put("kind", "tap").put("nodeId", action.nodeId)
        is Action.Input -> put("kind", "input").put("nodeId", action.nodeId).put("text", action.text)
        is Action.Scroll -> put("kind", "scroll").put("nodeId", action.nodeId).put("forward", action.forward)
        is Action.Launch -> put("kind", "launch").put("packageName", action.packageName)
        Action.Home -> put("kind", "home")
        Action.Back -> put("kind", "back")
        Action.Screenshot -> put("kind", "screenshot")
        Action.Ocr -> put("kind", "ocr")
        is Action.TapAt -> put("kind", "tap_at").put("x", action.x).put("y", action.y).put("captureId", action.captureId)
        is Action.LongPress -> put("kind", "long_press").put("x", action.x).put("y", action.y).put("duration", action.duration).put("captureId", action.captureId)
        is Action.Swipe -> put("kind", "swipe").put("x", action.x).put("y", action.y).put("endX", action.endX).put("endY", action.endY).put("duration", action.duration).put("captureId", action.captureId)
        else -> error("This action does not mutate the device")
    }
}
