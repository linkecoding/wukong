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

/** Opt-in real-model regression. No purchase, sending, or approval is permitted by this test. */
@RunWith(AndroidJUnit4::class)
class OnboardingLiveTest {
    @Test fun savedProviderConnectsWithTheNewRequestAdapter() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsStore(context).load() ?: error("No configured model")
        val result = ChatModel(settings).test()
        assertTrue(result.contains("成功"))
    }
    @Test fun researchTemplateRunsOnVirtualDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_research") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = SettingsStore(context).load() ?: error("No configured model")
        val previous = VirtualScreenState.enabled(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(timeout: Long, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + timeout
            while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
            assertTrue("State did not arrive before timeout", condition())
        }
        try {
            instrumentation.runOnMainSync { VirtualScreenState.enabled(context, true); VirtualScreenState.start(activity) }
            await(30_000) { VirtualScreenState.displayId > 0 && VirtualScreenState.bitmap != null }
            val goal = UiLanguage.wrap(context).getString(R.string.sample_compare_prompt, "小米手环9")
            instrumentation.runOnMainSync { TaskRuntime.start(context, goal, "com.android.chrome", settings) }
            val deadline = System.currentTimeMillis() + 180_000
            while (TaskRuntime.active && System.currentTimeMillis() < deadline) {
                TaskRuntime.pending?.let { TaskRuntime.answer(it, false) }
                Thread.sleep(200)
            }
            val event = TaskRuntime.event
            val report = JSONObject().put("phase", event.phase.name).put("message", event.message)
                .put("steps", TaskRuntime.events().size)
            context.openFileOutput("onboarding-research-result.json", 0).use { it.write(report.toString(2).toByteArray()) }
            assertFalse("Research timed out", TaskRuntime.active)
            assertEquals(event.message, Phase.COMPLETED, event.phase)
        } finally {
            TaskRuntime.stop()
            instrumentation.runOnMainSync { VirtualScreenState.stop(context); activity.finish() }
            VirtualScreenState.enabled(context, previous)
        }
    }
}
