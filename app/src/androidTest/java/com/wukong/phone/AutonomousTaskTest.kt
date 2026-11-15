package com.wukong.phone

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.Phase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real model + real shell + task owner; only navigates to Settings, never changes settings. */
@RunWith(AndroidJUnit4::class)
class AutonomousTaskTest {
    @Test fun opensSystemSettingsWithoutApprovalAndRecordsTokensAndResults() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsStore(context).load() ?: error("模型未配置")
        val logs = RunLogStore(context)
        val wasEnabled = logs.enabled
        val previous = logs.files().map { it.name }.toSet()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            logs.enabled = true
            instrumentation.runOnMainSync {
                TaskRuntime.start(context, "请打开系统设置（com.android.settings）的主页面。到达后立即 finish，不要修改任何设置。", null, settings)
            }
            val deadline = System.currentTimeMillis() + 75_000
            while (TaskRuntime.active && System.currentTimeMillis() < deadline) {
                assertNull("普通导航不应等待确认", TaskRuntime.pending)
                Thread.sleep(100)
            }
            assertFalse("任务应在限定时间内结束", TaskRuntime.active)
            assertEquals(TaskRuntime.event.message, Phase.COMPLETED, TaskRuntime.event.phase)
            ShellSession(context).use { assertEquals("com.android.settings", AndroidDriver(it).observe().packageName) }
            val file = logs.files().single { it.name !in previous }
            val records = logs.read(file.name)
            assertTrue(records.any { it.optString("type") == "tool_call" })
            assertTrue(records.any { it.optString("type") == "device_result" && it.getJSONObject("data").optInt("step") > 0 })
            assertTrue(records.any { it.optString("type") == "model_response" && it.getJSONObject("data").getJSONObject("usage").getLong("total_tokens") > 0 })
            assertFalse(records.any { it.optString("type") == "approval_requested" })
            assertFalse(records.toString().contains(settings.apiKey))
        } finally {
            TaskRuntime.stop(); logs.enabled = wasEnabled
            instrumentation.runOnMainSync { context.stopService(Intent(context, WukongService::class.java)); activity.finish() }
        }
    }
}
