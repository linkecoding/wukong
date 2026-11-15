package com.wukong.phone

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.Phase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualControlTest {
    @Test fun embeddedBackgroundFloatingTakeoverAndHandoffContinueRealAgent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = VirtualScreenState.enabled(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(condition: () -> Boolean) {
            val end = System.currentTimeMillis()+30_000
            while (!condition() && System.currentTimeMillis()<end) Thread.sleep(100)
            assertTrue("虚拟屏状态未在限定时间内到达", condition())
        }
        try {
            instrumentation.runOnMainSync { VirtualScreenState.enabled(context,true); VirtualScreenState.start(activity) }
            await { VirtualScreenState.displayId > 0 && VirtualScreenState.bitmap != null }
            val id = VirtualScreenState.displayId
            assertFalse(VirtualScreenState.service!!.floatingVisible)
            instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
            await { VirtualScreenState.service!!.floatingVisible }
            instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await { !VirtualScreenState.service!!.floatingVisible }
            val settings = SettingsStore(context).load() ?: error("模型未配置")
            instrumentation.runOnMainSync {
                TaskRuntime.start(context,"打开系统设置 com.android.settings 的首页后立即结束，不修改设置。",null,settings)
                VirtualScreenState.control(true)
            }
            await { VirtualScreenState.manual }
            assertTrue(TaskRuntime.active); assertEquals(Phase.PAUSED,TaskRuntime.event.phase)
            ShellClient(context).request("manualInput",JSONObject().put("kind","home"))
            Thread.sleep(600)
            assertTrue("接管期间 Agent 不能结束或继续执行",TaskRuntime.active)
            VirtualScreenState.control(false)
            await { !VirtualScreenState.manual && !TaskRuntime.active }
            assertEquals(TaskRuntime.event.message,Phase.COMPLETED,TaskRuntime.event.phase)
            ShellSession(context,id).use { assertEquals("com.android.settings",AndroidDriver(it).observe().packageName) }
            ShellSession(context).use { assertEquals("com.wukong.phone",AndroidDriver(it).observe().packageName) }
        } finally {
            TaskRuntime.stop()
            instrumentation.runOnMainSync { VirtualScreenState.stop(context); activity.finish() }
            VirtualScreenState.enabled(context,previous)
        }
    }
}
