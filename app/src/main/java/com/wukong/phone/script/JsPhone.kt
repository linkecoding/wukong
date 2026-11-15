package com.wukong.phone.script

import android.os.SystemClock
import com.wukong.core.*
import com.wukong.phone.*
import org.json.JSONObject
import org.json.JSONArray

/** Only this finite API is reachable from JS. No Context, reflection, filesystem, network or shell bridge. */
internal class JsPhone(private val host: ScriptHost, private val guard: () -> Unit, private val trace: RunLog?) {
    var calls = 0; private set
    private class ApiFailure(val code: String, message: String) : IllegalArgumentException(message)
    private var injected = false
    private var accepted = false
    private var attempts = 0
    private val completed = mutableListOf<JSONObject>()
    private var acceptedCount = 0
    fun call(method: String, args: JSONObject): Any {
        val started = SystemClock.elapsedRealtime()
        injected = false; accepted = false; attempts = 0
        try { return dispatch(method, args) }
        catch (error: Exception) {
            if (error is InterruptedException || error is ScriptApprovalRejected || error is ScriptCallFailure) throw error
            val detail = JSONObject().put("code", if (error is ApiFailure) error.code else if (injected) "OUTCOME_UNKNOWN" else if (error is IllegalArgumentException || error is org.json.JSONException) "INVALID_ARGUMENT" else "CANCELLED")
                .put("method", method).put("message", error.message.orEmpty().take(300)).put("step", calls)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("attempts", attempts)
                .put("accepted", if (accepted) true else if (injected) "unknown" else false).put("verified", "unknown")
                .put("acceptedCount", acceptedCount).put("completed", JSONArray(completed.takeLast(4)))
            args.optJSONObject("selector")?.let { detail.put("selector", it) }
            trace?.record("script_call_error", detail)
            throw ScriptCallFailure(detail.toString(), error)
        }
    }
    fun cellFailure(error: Exception): ScriptCallFailure {
        val localCode = runCatching { JSONObject(error.message.orEmpty().substringAfter("Error: ")).optString("code") }.getOrNull()
        val detail = JSONObject().put("code", localCode?.takeIf { it == "INVALID_ARGUMENT" } ?: "SCRIPT_ERROR")
            .put("method", "javascript").put("message", error.message.orEmpty().take(400)).put("step", calls)
            .put("acceptedCount", acceptedCount).put("completed", JSONArray(completed.takeLast(4)))
        return ScriptCallFailure(detail.toString(), error)
    }
    private fun dispatch(method: String, args: JSONObject): Any {
        guard(); check(++calls <= 80) { "单段脚本最多调用 80 次手机 API" }
        trace?.record("script_call", JSONObject().put("method", method).put("arguments", args).put("index", calls))
        val start = SystemClock.elapsedRealtime()
        val allowed = when (method) {
            "query" -> arrayOf("selector", "limit", "offset")
            "verify", "waitUntil" -> arrayOf("conditions", "matchAll", "timeoutMs")
            "waitAny" -> arrayOf("conditions", "names", "timeoutMs")
            "tap" -> arrayOf("selector", "timeoutMs")
            "input", "setText" -> arrayOf("selector", "text", "timeoutMs")
            "setChecked" -> arrayOf("selector", "checked", "timeoutMs")
            "scroll" -> arrayOf("selector", "forward", "timeoutMs")
            "launch" -> arrayOf("packageName")
            "tapAt" -> arrayOf("x", "y", "captureId")
            "longPress" -> arrayOf("x", "y", "captureId", "duration")
            "swipe" -> arrayOf("x", "y", "endX", "endY", "captureId", "duration")
            "sleep" -> arrayOf("milliseconds")
            "nextChange" -> arrayOf("timeoutMs", "after")
            "log" -> arrayOf("message")
            "observe", "home", "back", "screenshot", "ocr" -> emptyArray()
            else -> throw ApiFailure("INVALID_ARGUMENT", "Unknown method: $method")
        }
        args.requireKeys(*allowed)
        for (key in listOf("timeoutMs", "limit", "offset", "milliseconds", "duration", "x", "y", "endX", "endY")) if (args.has(key)) {
            val number = args.get(key)
            require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble()) { "$key must be an integer" }
        }
        for (key in listOf("checked", "forward", "matchAll")) if (args.has(key)) require(args.get(key) is Boolean) { "$key must be boolean" }
        val result: Any = when (method) {
            "observe" -> observe().toJson().put("uiRevision", lastRevision)
            "query" -> Inspector(::observe, guard).run(Action.Query(args.optJSONObject("selector")?.toSelector() ?: NodeSelector(), args.optInt("limit", 20), args.optInt("offset", 0))).toJson()
            "waitUntil", "verify" -> {
                val conditions = args.toConditions()
                Inspector(::observe, guard).run(if (method == "verify") Action.Verify(conditions, args.optBoolean("matchAll", true))
                    else Action.WaitUntil(conditions, args.optBoolean("matchAll", false), args.optLong("timeoutMs", 5000))).toJson()
            }
            "waitAny" -> {
                val conditions = args.toConditions(); Conditions.validate(conditions)
                val names = args.getJSONArray("names")
                require(names.length() == conditions.size && (0 until names.length()).all { names.get(it) is String && names.getString(it).length in 1..80 }) { "Invalid outcome names" }
                require((0 until names.length()).map { names.getString(it) }.distinct().size == names.length()) { "Outcome names must be unique" }
                val inspection = Inspector(::observe, guard).run(Action.WaitUntil(conditions, false, args.optLong("timeoutMs", 5000)))
                val checks = inspection.report!!.checks
                val met = checks.indices.filter { checks[it].state == CheckState.MET }
                inspection.toJson().put("key", met.firstOrNull()?.let { names.getString(it) } ?: JSONObject.NULL)
                    .put("matchedKeys", JSONArray(met.map { names.getString(it) }))
                    .put("matches", JSONArray(met.firstOrNull()?.let { checks[it].matches.map { node -> node.toJson() } }.orEmpty()))
            }
            "tap", "input", "scroll", "setText", "setChecked" -> mutate(method, args)
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
    private fun mutate(method: String, args: JSONObject): JSONObject {
        val selector = args.getJSONObject("selector").toSelector()
        require(selector != NodeSelector()) { "Actions require a specific selector" }
        if (method == "input" || method == "setText") require(args.get("text") is String && args.getString("text").length <= 2000) { "text must be a string up to 2000 characters" }
        if (method == "setChecked") require(args.get("checked") is Boolean) { "checked must be boolean" }
        val timeout = args.optLong("timeoutMs", 3000); require(timeout in 0..10000) { "timeoutMs must be 0–10000" }
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val screen = observe(); attempts++
            val matches = Conditions.find(screen, selector)
            if (matches.size > 1) throw ApiFailure("AMBIGUOUS_TARGET", "Matched ${matches.size} targets; refine the selector")
            val node = matches.singleOrNull()
            if (node != null && !screen.truncated) {
                if (method == "setText" && !node.editable) throw ApiFailure("INVALID_ARGUMENT", "Target is not editable")
                if (method == "setText" && node.rawText == null) throw ApiFailure("ENGINE_UPDATE_REQUIRED", "Engine does not expose rawText; reactivate the built-in shell before setting text")
                if (method == "setText" && node.rawText == args.getString("text")) return JSONObject().put("accepted", false).put("noOp", true).put("verified", true)
                if (method == "setChecked") {
                    if (node.checked == null) throw ApiFailure("INVALID_ARGUMENT", "Target is not checkable")
                    if (node.checked == args.getBoolean("checked")) return JSONObject().put("accepted", false).put("noOp", true).put("verified", true)
                }
                if (node.enabled) {
                    val action = when (method) {
                        "tap", "setChecked" -> Action.Tap(node.id)
                        "input", "setText" -> Action.Input(node.id, args.getString("text"))
                        else -> Action.Scroll(node.id, args.optBoolean("forward", true))
                    }
                    val result = executeOn(action, screen)
                    if (method == "setText" || method == "setChecked") {
                        // Read-only reconciliation: never repeat a possibly accepted input.
                        val verifyStart = SystemClock.elapsedRealtime()
                        do {
                            val fresh = observe(); val found = Conditions.find(fresh, selector)
                            if (!fresh.truncated && found.size == 1 && if (method == "setText") found.single().rawText == args.getString("text") else found.single().checked == args.getBoolean("checked")) {
                                completed.lastOrNull()?.put("verified", true)
                                return result.put("verified", true).put("noOp", false)
                            }
                            if (SystemClock.elapsedRealtime() - verifyStart >= 2000) throw ApiFailure("OUTCOME_UNKNOWN", "Input accepted but requested state could not be verified; inspect before continuing")
                            pause(100)
                        } while (true)
                    }
                    return result
                }
            }
            if (SystemClock.elapsedRealtime() - start >= timeout) throw ApiFailure(if (screen.truncated) "INCOMPLETE_SOURCE" else if (node == null) "NOT_FOUND" else "NOT_READY", "Target not uniquely ready within ${timeout}ms; no input sent")
            pause(minOf(100, (timeout - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(1)))
        }
    }
    private fun pause(ms: Long) { guard(); Thread.sleep(ms); guard() }
    private fun executeOn(action: Action, screen: Screen): JSONObject {
        injected = true
        val result = host.execute(action, screen)
        accepted = true; acceptedCount++
        completed += JSONObject().put("step", calls).put("action", action.javaClass.simpleName).put("accepted", true)
        return JSONObject().put("accepted", result)
    }
    private var lastRevision = 0L
    private fun observe() = host.observe().also { lastRevision = it.uiRevision }
    private fun execute(action: Action): JSONObject = executeOn(action, observe())
}
