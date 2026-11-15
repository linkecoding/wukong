package com.wukong.phone

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualAgentTest {
    @Test fun modelUsesOcrThenCoordinateTap() = runVisualTask("phone_ocr")
    @Test fun modelUsesScreenshotThenCoordinateTap() = runVisualTask("phone_screenshot")
    private fun runVisualTask(perception: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsStore(context).load() ?: error("模型未配置")
        val store = RunLogStore(context)
        val trace = store.start("visual_tool_test", "$perception 与坐标点击测试", settings)
        val activity = instrumentation.startActivitySync(Intent(context, CanvasSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            Thread.sleep(400)
            ShellSession(context).use { session ->
                val driver = AndroidDriver(session, trace = trace)
                val result = Agent(ChatModel(settings, trace = trace), driver, Approval { _, _ -> error("测试画布上的普通按钮不应要求确认") }, maxSteps = 12)
                    .run("必须先调用 $perception 读取当前画布，再用 phone_tap_at 点击文字为‘点击测试 TAP’的区域。点击后再次调用 $perception 确认出现 TAPPED，再通过 phone_action 的 finish 结束。不要离开当前应用，不使用节点点击。", Cancellation()) { }
                assertEquals(result.message, Phase.COMPLETED, result.phase)
                driver.execute(Action.Ocr, driver.observe())
                assertTrue(driver.observe().visual!!.lines.any { it.text.contains("TAPPED") })
                trace?.record("end", JSONObject().put("phase", result.phase.name))
                if (trace != null) {
                    val tools = store.read(trace.name).filter { it.optString("type") == "tool_call" }.map { it.getJSONObject("data").getString("name") }
                    assertTrue(tools.contains(perception)); assertTrue(tools.contains("phone_tap_at"))
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
