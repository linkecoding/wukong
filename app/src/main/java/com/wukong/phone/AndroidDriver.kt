package com.wukong.phone

import com.wukong.core.*
import org.json.JSONObject
import org.json.JSONArray
import android.graphics.BitmapFactory
import android.util.Base64

/** Observations and mutations are performed by the separate UID 2000 app_process. */
class AndroidDriver(private val session: ShellSession, private val cancellation: Cancellation = Cancellation(), private val trace: RunLog? = null, private val control: ExecutionControl? = null) : DeviceDriver {
    @Volatile private var visual: VisualFrame? = null
    @Volatile private var visualFingerprint: String? = null
    fun invalidateVisual() { visual = null; visualFingerprint = null }
    private val settler = ScreenSettler()
    private fun readNow(): Screen {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "界面读取必须在后台线程运行" }
        cancellation.check()
        return (session.request("observe") as JSONObject).toScreen().also { cancellation.check() }
    }
    override fun verify(conditions: List<ScreenCondition>, screen: Screen): ConditionReport = super.verify(conditions, screen).also {
        trace?.record("completion_verification", it.toJson())
    }
    override fun observe(): Screen {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "界面读取必须在后台线程运行" }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            // Shell reads TYPE_APPLICATION windows; overlays need no UI-thread hide/show for node reads.
            val screen = settler.observe(::readNow, cancellation::check)
            if (screen.fingerprint != visualFingerprint) { visual = null; visualFingerprint = null }
            return screen.copy(visual = visual).also {
                trace?.record("device_observation", JSONObject().put("package", it.packageName).put("nodeCount", it.nodes.size)
                    .put("durationMs", android.os.SystemClock.elapsedRealtime() - started)
                    .put("settled", screen.observation?.settled).put("samples", screen.observation?.samples)
                    .put("uiEvents", screen.uiEvents).put("uiRevision", screen.uiRevision).put("uiQuietMs", screen.uiQuietMillis)
                    .put("thread", Thread.currentThread().name))
            }
        } catch (e: Exception) {
            trace?.record("observation_error", JSONObject().put("message", e.message).put("durationMs", android.os.SystemClock.elapsedRealtime() - started))
            throw e
        }
    }
    override fun execute(action: Action, expected: Screen): String {
        cancellation.check()
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "设备操作必须在后台线程运行" }
        if (action is Action.Query || action is Action.WaitUntil || action is Action.Verify) {
            val epoch = control?.epoch
            trace?.record("device_action", JSONObject().put("action", action.toJson()).put("package", expected.packageName))
            try {
                val result = Inspector(::readNow, {
                    cancellation.check()
                    check(control == null || !control.paused && control.epoch == epoch) { "用户正在接管，Agent 已暂停" }
                }).run(action)
                val json = result.toJson()
                trace?.record("inspection_result", JSONObject().put("action", action.toJson()).put("result", json).put("durationMs", result.elapsedMillis))
                return json.toString()
            } catch (error: Exception) {
                trace?.record("inspection_error", JSONObject().put("action", action.toJson()).put("message", error.message))
                throw error
            }
        }
        if (action is Action.Wait) {
            Thread.sleep(action.milliseconds)
            trace?.record("device_result", JSONObject().put("action", JSONObject().put("kind", "wait").put("milliseconds", action.milliseconds)).put("result", "等待完成"))
            return "等待完成"
        }
        val started = android.os.SystemClock.elapsedRealtime()
        trace?.record("device_action", JSONObject().put("action", action.toJson()).put("package", expected.packageName))
        val controls = WukongService.instance
        controls?.onMain { controls.hideControls() }
        try {
            cancellation.check()
            if (action is Action.Screenshot || action is Action.Ocr) {
                val packet = session.request("capture", JSONObject().put("package", expected.packageName).put("fingerprint", expected.fingerprint)) as JSONObject
                val jpeg = packet.getString("jpeg")
                val bytes = Base64.decode(jpeg, Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("截图解码失败")
                val lines = ScreenOcr.recognize(session.appContext, bitmap)
                cancellation.check()
                val frame = VisualFrame(packet.getString("captureId"), packet.getInt("width"), packet.getInt("height"), lines, if (action is Action.Screenshot) jpeg else null)
                visual = frame; visualFingerprint = expected.fingerprint
                val result = JSONObject().put("captureId", frame.id).put("coordinateSystem", "normalized_0_1000")
                    .put("width", frame.width).put("height", frame.height).put("lines", JSONArray(lines.map { JSONObject().put("text", it.text).put("bounds", it.bounds.toJson()) }))
                trace?.record("device_result", JSONObject().put("action", action.toJson()).put("captureId", frame.id).put("lineCount", lines.size).put("ocrEngine", ScreenOcr.ENGINE)
                    .put("durationMs", android.os.SystemClock.elapsedRealtime() - started))
                return result.toString()
            }
            val result = session.request("execute", JSONObject().put("action", action.toJson())
                .put("package", expected.packageName).put("fingerprint", expected.fingerprint)) as String
            visual = null; visualFingerprint = null
            trace?.record("device_result", JSONObject().put("action", action.toJson()).put("result", result).put("durationMs", android.os.SystemClock.elapsedRealtime() - started))
            return result
        } catch (e: Exception) {
            if (e.message?.startsWith("截图已过期") == true) invalidateVisual()
            trace?.record("device_error", JSONObject().put("action", action.toJson()).put("message", e.message).put("durationMs", android.os.SystemClock.elapsedRealtime() - started))
            throw e
        } finally { controls?.onMain { if (!TaskRuntime.stopRequested) controls.showControls() } }
    }
}

fun Bounds.toJson() = JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom)
