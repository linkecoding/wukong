package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.Action
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ScreenObservationTest {
    @Test fun realWindowEventsSettlingTimeoutAndStaleProtectionRunOffTheAppMainThread() {
        val i = InstrumentationRegistry.getInstrumentation(); val c = i.targetContext
        assumeTrue(!TaskRuntime.active)
        val previousVirtual = VirtualScreenState.enabled(c)
        i.runOnMainSync { VirtualScreenState.enabled(c, false); VirtualScreenState.stop(c) }
        val a = i.startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        val handler = Handler(Looper.getMainLooper())
        val clicks = AtomicInteger()
        lateinit var label: TextView
        val report = JSONObject()
        try {
            i.runOnMainSync {
                a.setTurnScreenOn(true); a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val fixture = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 200, 40, 40) }
                label = TextView(a).apply { text = "settling-fixture-initial"; textSize = 22f }
                fixture.addView(label, LinearLayout.LayoutParams(-1, 180))
                fixture.addView(Button(a).apply {
                    text = "change-fixture"; isAllCaps = false
                    setOnClickListener { clicks.incrementAndGet(); label.text = "settling-fixture-click-${clicks.get()}" }
                }, LinearLayout.LayoutParams(-1, 180))
                a.setContentView(fixture)
            }
            i.waitForIdleSync()
            val bounds = Rect(); i.runOnMainSync { label.getGlobalVisibleRect(bounds) }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN; i.sendPointerSync(event); event.recycle()
            }
            ShellSession(c).use { session ->
                val driver = AndroidDriver(session)
                val initial = driver.observe()
                assertTrue(initial.uiEvents); assertTrue(initial.observation!!.settled)
                assertTrue(initial.nodes.any { it.text == "settling-fixture-initial" })
                i.runOnMainSync {
                    fun tick(n: Int) {
                        label.text = if (n == 5) "settling-fixture-finished" else "settling-fixture-tick-$n"
                        if (n < 5) handler.postDelayed({ tick(n + 1) }, 70)
                    }
                    tick(0)
                }
                val transitioned = driver.observe()
                assertTrue(transitioned.observation!!.settled)
                assertTrue(transitioned.uiRevision > initial.uiRevision)
                assertTrue(transitioned.nodes.any { it.text == "settling-fixture-finished" })
                report.put("transitionMs", transitioned.observation!!.elapsedMillis).put("transitionSamples", transitioned.observation!!.samples)
                i.runOnMainSync {
                    fun tick(n: Int) { label.text = "settling-fixture-continuous-$n"; handler.postDelayed({ tick(n + 1) }, 40) }
                    tick(0)
                }
                val moving = driver.observe()
                assertFalse("Persistent changes return a snapshot instead of failing", moving.observation!!.settled)
                report.put("movingMs", moving.observation!!.elapsedMillis).put("movingSamples", moving.observation!!.samples)
                i.runOnMainSync { handler.removeCallbacksAndMessages(null); label.text = "settling-fixture-ready" }
                val ready = driver.observe()
                val action = Action.Tap(ready.nodes.single { it.text == "change-fixture" }.id)
                val started = SystemClock.elapsedRealtime()
                driver.execute(action, ready)
                report.put("executeMs", SystemClock.elapsedRealtime() - started)
                driver.execute(Action.WaitUntil(listOf(com.wukong.core.ScreenCondition(com.wukong.core.NodeSelector(textEquals = "settling-fixture-click-1")))), ready)
                assertEquals(1, clicks.get())
                val stale = runCatching { driver.execute(action, ready) }.exceptionOrNull()
                assertTrue(stale?.message.orEmpty().startsWith("界面已更新，本次操作未执行"))
                assertEquals("A stale decision must never double-click", 1, clicks.get())
                val afterClick = driver.observe()
                val ocrStart = SystemClock.elapsedRealtime()
                driver.execute(Action.Ocr, afterClick)
                report.put("ocrMs", SystemClock.elapsedRealtime() - ocrStart)
                val visual = driver.observe()
                val frame = visual.visual ?: error("OCR must retain a current capture")
                val target = frame.lines.firstOrNull { it.text.lowercase().contains("change") } ?: error("Fixture button must be recognized")
                driver.execute(Action.TapAt((target.bounds.left + target.bounds.right) / 2, (target.bounds.top + target.bounds.bottom) / 2, frame.id), visual)
                driver.execute(Action.WaitUntil(listOf(com.wukong.core.ScreenCondition(com.wukong.core.NodeSelector(textEquals = "settling-fixture-click-2")))), visual)
                assertEquals("Coordinate path accepts the fresh capture exactly once", 2, clicks.get())
                i.runOnMainSync {
                    assertTrue("Observe must reject UI-thread calls before doing IO", runCatching { driver.observe() }.exceptionOrNull() is IllegalStateException)
                }
                report.put("eventRevision", ready.uiRevision).put("thread", Thread.currentThread().name)
            }
        } finally {
            i.runOnMainSync { handler.removeCallbacksAndMessages(null); a.finish(); VirtualScreenState.enabled(c, previousVirtual) }
            c.openFileOutput("screen-observation-report.json", 0).use { it.write(report.toString().toByteArray()) }
        }
    }
}
