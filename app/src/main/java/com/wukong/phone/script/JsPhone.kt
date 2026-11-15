package com.wukong.phone.script

import android.os.SystemClock
import com.wukong.core.*
import com.wukong.phone.*
import org.json.JSONObject

/** Only this finite API is reachable from JS. No Context, reflection, filesystem, network or shell bridge. */
internal class JsPhone(private val host: ScriptHost, private val guard: () -> Unit, private val trace: RunLog?) {
    var calls = 0; private set
    fun call(method: String, args: JSONObject): Any {
        guard(); check(++calls <= 80) { "单段脚本最多调用 80 次手机 API" }
        trace?.record("script_call", JSONObject().put("method", method).put("arguments", args).put("index", calls))
        val start = SystemClock.elapsedRealtime()
        val result: Any = when (method) {
            "observe" -> observe().toJson().put("uiRevision", lastRevision)
            "query" -> Inspector(::observe, guard).run(Action.Query(args.optJSONObject("selector")?.toSelector() ?: NodeSelector(), args.optInt("limit", 20), args.optInt("offset", 0))).toJson()
            "waitUntil", "verify" -> {
                val conditions = args.toConditions()
                Inspector(::observe, guard).run(if (method == "verify") Action.Verify(conditions, args.optBoolean("matchAll", true))
                    else Action.WaitUntil(conditions, args.optBoolean("matchAll", false), args.optLong("timeoutMs", 5000))).toJson()
            }
            "tap", "input", "scroll" -> {
                val screen = observe(); val selector = args.getJSONObject("selector").toSelector()
                val matches = Conditions.find(screen, selector)
                require(matches.size == 1 && !screen.truncated) { "目标须唯一且页面读取完整，当前匹配 ${matches.size} 个；请补充资源 ID 或所属区域" }
                val id = matches.single().id
                val action = when (method) {
                    "tap" -> Action.Tap(id)
                    "input" -> Action.Input(id, args.getString("text"))
                    else -> Action.Scroll(id, args.optBoolean("forward", true))
                }
                JSONObject().put("accepted", host.execute(action, screen))
            }
            "launch" -> execute(Action.Launch(args.getString("packageName")))
            "home" -> execute(Action.Home)
            "back" -> execute(Action.Back)
            "screenshot" -> JSONObject(host.execute(Action.Screenshot, observe()))
            "ocr" -> JSONObject(host.execute(Action.Ocr, observe()))
            "tapAt" -> execute(Action.TapAt(args.getInt("x"), args.getInt("y"), args.getString("captureId")))
            "longPress" -> execute(Action.LongPress(args.getInt("x"), args.getInt("y"), args.optLong("duration", 600), args.getString("captureId")))
            "swipe" -> execute(Action.Swipe(args.getInt("x"), args.getInt("y"), args.getInt("endX"), args.getInt("endY"), args.optLong("duration", 350), args.getString("captureId")))
            "sleep" -> {
                val millis = args.getLong("milliseconds"); require(millis in 1..10000)
                val end = SystemClock.elapsedRealtime() + millis
                while (SystemClock.elapsedRealtime() < end) { guard(); Thread.sleep(minOf(100, (end - SystemClock.elapsedRealtime()).coerceAtLeast(1))) }
                JSONObject().put("waitedMs", millis)
            }
            "nextChange" -> {
                val timeout = args.optLong("timeoutMs", 5000); require(timeout in 100..10000)
                val baseline = args.optJSONObject("after") ?: observe().let { JSONObject().put("fingerprint", it.fingerprint).put("uiRevision", it.uiRevision).put("package", it.packageName) }
                var screen: Screen
                var changed: Boolean
                do {
                    screen = observe()
                    changed = screen.fingerprint != baseline.optString("fingerprint") || screen.uiRevision != baseline.optLong("uiRevision") || screen.packageName != baseline.optString("package")
                    if (changed || SystemClock.elapsedRealtime() - start >= timeout) break
                    Thread.sleep(minOf(150, (timeout - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(1)))
                } while (true)
                JSONObject().put("changed", changed).put("timedOut", !changed).put("screen", screen.toJson().put("uiRevision", screen.uiRevision))
            }
            "log" -> { val message = args.getString("message"); require(message.length <= 2000); JSONObject().put("logged", true) }
            else -> error("未提供此 JS 手机能力：$method")
        }
        guard()
        trace?.record("script_call_result", JSONObject().put("method", method).put("index", calls).put("durationMs", SystemClock.elapsedRealtime() - start)
            .put("result", if (result.toString().length <= 12000) result else "[结果超过日志单条限制，已省略]"))
        return result
    }
    private var lastRevision = 0L
    private fun observe() = host.observe().also { lastRevision = it.uiRevision }
    private fun execute(action: Action): JSONObject = JSONObject().put("accepted", host.execute(action, observe()))
}
