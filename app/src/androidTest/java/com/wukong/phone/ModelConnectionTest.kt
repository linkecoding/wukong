package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: uses the saved service for one harmless tool-call request. No device actions. */
@RunWith(AndroidJUnit4::class)
class ModelConnectionTest {
    @Test fun configuredModelReturnsFinishToolWithoutExecutingAnything() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context).load() ?: error("模型未配置")
        val modes = if (InstrumentationRegistry.getArguments().getString("thinking_modes") == "true") listOf(true, false) else listOf(settings.thinkingEnabled)
        for (enabled in modes) {
            val store = RunLogStore(context)
            val trace = store.start("connection_test", "真实模型与 Token 日志测试", settings.copy(thinkingEnabled = enabled))
            assertTrue("thinking=$enabled", ChatModel(settings.copy(thinkingEnabled = enabled), trace = trace).test().contains("工具调用测试成功"))
            trace?.record("end", JSONObject().put("message", "工具调用测试通过"))
            if (trace != null) {
                val records = store.read(trace.name)
                assertTrue(records.any { it.optString("type") == "tool_call" })
                assertTrue(records.any { it.optString("type") == "model_response" && it.getJSONObject("data").getJSONObject("usage").getLong("total_tokens") > 0 })
                assertFalse(records.toString().contains(settings.apiKey))
            }
        }
    }
}
