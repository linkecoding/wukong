package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.wukong.core.Phase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualStartupTest {
    @Test fun liveTaskRecoversAnEmptyVirtualDisplayBeforeItsFirstObservation() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_model")=="true")
        assumeTrue(!TaskRuntime.active)
        val enabled=VirtualScreenState.enabled(context)
        val logs=RunLogStore(context);val logging=logs.enabled;logs.enabled=true
        fun ui(block: () -> Unit)=i.runOnMainSync(block)
        fun await(label: String,condition: () -> Boolean) {
            val end=SystemClock.elapsedRealtime()+40000
            while(!condition()&&SystemClock.elapsedRealtime()<end)Thread.sleep(80)
            assertTrue(label,condition())
        }
        val main=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        try {
            ui { main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);VirtualScreenState.enabled(context,true);VirtualScreenState.start(context) }
            await("Virtual display ready") { VirtualScreenState.displayId>0&&VirtualScreenState.bitmap!=null }
            val id=VirtualScreenState.displayId
            val client=ShellClient(context)
            ShellSession(context,id).use { session ->
                val driver=AndroidDriver(session);val before=driver.observe()
                assertFalse((session.request("recoverVirtualContent") as org.json.JSONObject).getBoolean("recovered"))
                assertEquals("Readable content must not be reset",before.fingerprint,driver.observe().fingerprint)
            }
            ShellSession(context).use { session ->
                try { session.request("recoverVirtualContent");fail("Physical display recovery is forbidden") }
                catch(error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("只能恢复")) }
            }
            client.request("setControl",org.json.JSONObject().put("manual",true))
            ShellSession(context,id).use { session ->
                try { session.request("recoverVirtualContent");fail("Recovery cannot interrupt manual ownership") }
                catch(error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("接管")) }
            }
            client.request("manualInput",org.json.JSONObject().put("kind","back"))
            client.request("setControl",org.json.JSONObject().put("manual",false))
            ShellSession(context,id).use { assertTrue(AndroidDriver(it).observe().nodes.any { node -> node.text==context.getString(R.string.ui_wukong_virtual_display) }) }
            ui { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<VirtualHomeActivity>().single().finishAndRemoveTask() }
            Thread.sleep(500)
            ShellSession(context,id).use { session ->
                try { AndroidDriver(session).observe();fail("Fixture must reproduce a virtual display without readable content") }
                catch(error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("可读取")) }
            }
            ui { TaskRuntime.start(context,"帮我打开设置（仅打开，不修改任何设置）。",null,SettingsStore(context).load()!!) }
            await("Task finishes") { !TaskRuntime.active }
            assertEquals(TaskRuntime.event.message,Phase.COMPLETED,TaskRuntime.event.phase)
            val logName=HistoryStore(context).read().first().logName!!
            assertEquals(1,RunLogStore(context).read(logName).count { it.optString("type")=="startup_recovery" })
            ShellSession(context,id).use { assertEquals("com.android.settings",AndroidDriver(it).observe().packageName) }
            ShellSession(context).use { assertEquals(context.packageName,AndroidDriver(it).observe().packageName) }
        } finally {
            TaskRuntime.stop();await("Stopped") { !TaskRuntime.active }
            ui { VirtualScreenState.stop(context);main.finish() };VirtualScreenState.enabled(context,enabled);logs.enabled=logging
        }
    }
}
