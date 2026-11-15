package com.wukong.phone

import android.content.Intent
import android.app.ActivityOptions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualToolsTest {
    @Test fun offlineOcrCoordinatesSwipeAndLongPressWorkOnOpaqueCanvas() {
        runCanvasTools(0)
    }
    @Test fun virtualCanvasOcrCoordinatesHitIndependentDisplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ShellClient(context)
        val id = (client.request("virtualStart") as org.json.JSONObject).getInt("displayId")
        try { runCanvasTools(id) } finally { client.request("virtualStop") }
    }
    private fun runCanvasTools(displayId: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
        val activity = instrumentation.startActivitySync(Intent(context, CanvasSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),options.toBundle())
        try {
            Thread.sleep(500)
            ShellSession(context,displayId).use { session ->
                val driver = AndroidDriver(session)
                fun capture(): Screen { driver.execute(Action.Ocr, driver.observe()); return driver.observe() }
                var screen = capture()
                assertTrue("Canvas OCR: ${screen.visual!!.lines.map { it.text }}", screen.visual!!.lines.any { it.text.contains("悟空") })
                fun center(state: Screen, text: String): Pair<Int, Int> {
                    val b = state.visual!!.lines.first { it.text.contains(text) }.bounds
                    assertTrue(b.left in 0..1000 && b.right in 0..1000 && b.top in 0..1000 && b.bottom in 0..1000)
                    return (b.left + b.right)/2 to (b.top + b.bottom)/2
                }
                var point = center(screen, "点击测试")
                val tap = Action.TapAt(point.first, point.second, screen.visual!!.id)
                assertFalse(SensitiveActions.requiresApproval(tap, screen))
                driver.execute(tap, screen)
                screen = capture(); assertTrue(screen.visual!!.lines.any { it.text.contains("TAPPED") })
                try { driver.execute(tap, screen); fail("Old capture must be rejected") } catch (_: IllegalStateException) { }
                point = center(screen, "LONG")
                driver.execute(Action.LongPress(point.first, point.second, 650, screen.visual!!.id), screen)
                screen = capture(); assertTrue(screen.visual!!.lines.any { it.text.contains("PRESSED") })
                point = center(screen, "SWIPE")
                driver.execute(Action.Swipe(point.first, point.second, point.first, point.second - 150, 450, screen.visual!!.id), screen)
                screen = capture(); assertTrue(screen.visual!!.lines.any { it.text.contains("SWIPED") })
                driver.execute(Action.Screenshot, screen)
                assertTrue(driver.observe().visual!!.jpegBase64!!.startsWith("/9j/"))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
