package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.Action
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicTargetTest {
    @Test fun tickingTimeDoesNotBlockAnUnchangedTargetButChangedLabelsAndPaymentsStayExact() {
        assumeTrue(!TaskRuntime.active)
        val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
        val enabled=VirtualScreenState.enabled(c);i.runOnMainSync { VirtualScreenState.stop(c) }
        val main=i.startActivitySync(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        val handler=Handler(Looper.getMainLooper());var count=0;var tick=0
        lateinit var timer:TextView;lateinit var button:Button
        val update=object:Runnable { override fun run() { timer.text="00:01.${(tick++%100).toString().padStart(2,'0')}";handler.postDelayed(this,30) } }
        try {
            i.runOnMainSync {
                main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val root=LinearLayout(main).apply { orientation=LinearLayout.VERTICAL;setPadding(40,200,40,40) }
                timer=TextView(main);button=Button(main).apply { text="暂停";setOnClickListener { count++ } }
                root.addView(timer);root.addView(button);main.setContentView(root);handler.post(update)
            }
            Thread.sleep(200)
            ShellSession(c).use { session ->
                val driver=AndroidDriver(session)
                val before=driver.observeNow();Thread.sleep(120);val after=driver.observeNow()
                assertNotEquals(before.fingerprint,after.fingerprint);assertEquals(before.interactionFingerprint,after.interactionFingerprint)
                driver.execute(Action.Tap(before.nodes.single { it.text=="暂停" }.id),before);i.waitForIdleSync();assertEquals(1,count)
                val stale=driver.observeNow();i.runOnMainSync { button.text="继续" };Thread.sleep(80)
                try { driver.execute(Action.Tap(stale.nodes.single { it.text=="暂停" }.id),stale);fail("Changed target text cannot be rebased") }
                catch(error:IllegalStateException) { assertTrue(error.message.orEmpty().startsWith("界面已更新")) }
                assertEquals(1,count)
                i.runOnMainSync { handler.removeCallbacks(update);timer.text="10:30" }
                val clock=driver.observeNow();i.runOnMainSync { timer.text="11:45" }
                try { driver.execute(Action.Tap(clock.nodes.single { it.text=="继续" }.id),clock);fail("Ordinary time fields are not volatile stopwatch counters") }
                catch(error:IllegalStateException) { assertTrue(error.message.orEmpty().startsWith("界面已更新")) }
                assertEquals(1,count)
                i.runOnMainSync { handler.post(update);button.text="立即支付" };val payment=driver.observeNow();Thread.sleep(120)
                try { driver.execute(Action.Tap(payment.nodes.single { it.text=="立即支付" }.id),payment);fail("High-risk UI requires an exact approved snapshot") }
                catch(error:IllegalStateException) { assertTrue(error.message.orEmpty().startsWith("界面已更新")) }
                assertEquals(1,count)
            }
        } finally { i.runOnMainSync { handler.removeCallbacksAndMessages(null);main.finish() };VirtualScreenState.enabled(c,enabled) }
    }
}
