package com.wukong.phone

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceDriverTest {
    @Test fun shellReadsInjectsNavigatesAndRejectsInvalidRequests() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(2000, ShellClient(context).status().getInt("uid"))
        assertTrue(android.os.Process.myUid() != 2000)
        try {
            ShellClient(context, "0".repeat(64)).status()
            fail("An invalid credential must be rejected")
        } catch (error: IllegalStateException) { assertEquals("Unauthorized", error.message) }
        try {
            context.contentResolver.call(android.net.Uri.parse("content://com.wukong.phone.bootstrap"), "prepare", null, null)
            fail("The ordinary app UID must not provision a new credential")
        } catch (_: SecurityException) { /* only adb shell can provision */ }
        val activity = instrumentation.startActivitySync(Intent(context, TestSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            Thread.sleep(800)
            context.startForegroundService(Intent(context, WukongService::class.java))
            for (attempt in 0 until 40) { if (WukongService.instance != null) break; Thread.sleep(50) }
            val controls = WukongService.instance ?: error("Task controls did not start")
            controls.onMain { controls.showControls() }
            ShellSession(context).use { session ->
                val driver = AndroidDriver(session)
                val initial = driver.observe()
                val input = initial.nodes.single { it.editable }
                driver.execute(Action.Input(input.id, "悟空 中文输入"), initial)
                val entered = driver.observe()
                assertTrue(entered.nodes.any { it.text.contains("悟空 中文输入") })
                try {
                    driver.execute(Action.Input(input.id, "must not replace"), initial)
                    fail("Expected stale observation to be rejected")
                } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("界面已更新")) }
                val fresh = driver.observe()
                val search = fresh.nodes.single { it.clickable && it.text.equals("Search", ignoreCase = true) }
                driver.execute(Action.Tap(search.id), fresh)
                assertTrue(driver.observe().nodes.any { it.text.contains("Results for: 悟空 中文输入") })
                val beforeScroll = driver.observe()
                val scroll = beforeScroll.nodes.first { it.scrollable }
                driver.execute(Action.Scroll(scroll.id, true), beforeScroll)
                assertNotEquals(beforeScroll.fingerprint, driver.observe().fingerprint)
                val cancelled = Cancellation().apply { cancel() }
                try {
                    AndroidDriver(session, cancelled).execute(Action.Home, driver.observe())
                    fail("Locally cancelled action must not execute")
                } catch (_: InterruptedException) { /* expected */ }
                driver.execute(Action.Home, driver.observe())
                val launcher = driver.observe()
                assertNotEquals(context.packageName, launcher.packageName)
                driver.execute(Action.Launch("com.android.settings"), launcher)
                var settingsScreen = driver.observe()
                for (attempt in 0 until 20) {
                    if (settingsScreen.packageName == "com.android.settings") break
                    Thread.sleep(200); settingsScreen = driver.observe()
                }
                assertEquals("com.android.settings", settingsScreen.packageName)
                driver.execute(Action.Back, settingsScreen)
                driver.execute(Action.Launch(context.packageName), driver.observe())
                assertEquals(context.packageName, driver.observe().packageName)
                val lastScreen = driver.observe()
                session.cancel()
                try {
                    session.request("execute", org.json.JSONObject().put("action", Action.Home.toJson())
                        .put("package", lastScreen.packageName).put("fingerprint", lastScreen.fingerprint))
                    fail("The shell process must reject cancelled sessions")
                } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("任务已停止")) }
            }
        } finally { instrumentation.runOnMainSync { context.stopService(Intent(context, WukongService::class.java)); activity.finish() } }
    }
}
