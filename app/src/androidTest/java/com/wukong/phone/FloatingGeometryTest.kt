package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingGeometryTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun ui(block: () -> Unit)=i.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+20000
        while(!condition()&&SystemClock.elapsedRealtime()<end)Thread.sleep(70)
        assertTrue("Floating preview state timed out",condition())
    }
    private fun panel()=WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.virtual_preview_resize)!=null } as VirtualScreenPanel
    private fun drag(view: View,dx: Float,dy: Float) {
        val location=IntArray(2);view.getLocationOnScreen(location)
        val now=SystemClock.uptimeMillis()
        for((action,fraction) in listOf(MotionEvent.ACTION_DOWN to 0f,MotionEvent.ACTION_MOVE to 1f,MotionEvent.ACTION_UP to 1f)) {
            val e=MotionEvent.obtain(now,now+(fraction*100).toLong(),action,location[0]+view.width/2f+dx*fraction,location[1]+view.height/2f+dy*fraction,0)
            e.offsetLocation(-location[0].toFloat(),-location[1].toFloat());view.dispatchTouchEvent(e);e.recycle()
        }
    }
    private fun geometry(): List<Int> {
        var value=listOf<Int>()
        ui { val p=panel();val lp=p.layoutParams as WindowManager.LayoutParams;value=listOf(p.width,p.previewHeight,lp.x,lp.y) }
        return value
    }
    private fun assertEvenFooter() {
        val home=panel().findViewById<View>(R.id.virtual_preview_home);val grip=panel().findViewById<View>(R.id.virtual_preview_resize)
        val back=(home.parent as android.view.ViewGroup).getChildAt(0)
        assertTrue("Manual footer uses equal-width targets",kotlin.math.abs(back.width-home.width)<=1&&kotlin.math.abs(home.width-grip.width)<=1)
        assertTrue("Manual icons have equal center spacing",kotlin.math.abs((home.left+home.width/2-back.left-back.width/2)-(grip.left+grip.width/2-home.left-home.width/2))<=1)
    }
    @Test fun resizeMovePersistAndHomeReturnsFromAnotherPageInTheLauncherTask() {
        assumeTrue(!TaskRuntime.active);assertTrue(Settings.canDrawOverlays(context))
        val prefs=context.getSharedPreferences("floating_preview",0)
        val previousGeometry=prefs.all.toMap();val enabled=VirtualScreenState.enabled(context)
        prefs.edit().clear().commit()
        val main=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        try {
            ui { main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);VirtualScreenState.start(context) }
            await { VirtualScreenState.displayId>0&&VirtualScreenState.bitmap!=null }
            ui { main.moveTaskToBack(true) };await { VirtualScreenState.service?.floatingVisible==true }
            val before=geometry()
            ui { drag(panel().findViewById(R.id.virtual_preview_header),-100f,50f) }
            assertFalse(VirtualScreenState.foreground)
            ui { drag(panel().findViewById(R.id.virtual_preview_resize),90f,120f) }
            await { geometry()[0]>before[0]&&geometry()[1]>before[1] }
            val resized=geometry();assertNotEquals(before,resized)
            ui { panel().findViewById<View>(R.id.virtual_preview_close).performClick() }
            await { VirtualScreenState.service?.floatingVisible==false }
            ui { context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) }
            await { VirtualScreenState.foreground }
            ui { main.moveTaskToBack(true) };await { VirtualScreenState.service?.floatingVisible==true }
            assertEquals("Reopening retains size and position",resized,geometry())
            ui { context.stopService(Intent(context,VirtualScreenService::class.java)) }
            await { VirtualScreenState.service==null&&VirtualScreenState.displayId==0 }
            ui { VirtualScreenState.start(context) }
            await { VirtualScreenState.service?.floatingVisible==true&&VirtualScreenState.bitmap!=null }
            assertEquals("Service recreation retains size and position",resized,geometry())
            val id=VirtualScreenState.displayId
            // Launch without NEW_TASK to reproduce a page stacked above the virtual launcher.
            ui { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<VirtualHomeActivity>().single().startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            ShellSession(context,id).use { session ->
                val driver=AndroidDriver(session)
                await { driver.observe().packageName=="com.android.settings" }
                VirtualScreenState.control(true);await { VirtualScreenState.manual }
                ui {
                    fun descendants(view: View): Sequence<View> = sequence {
                        yield(view)
                        if(view is android.view.ViewGroup) for(index in 0 until view.childCount)yieldAll(descendants(view.getChildAt(index)))
                    }
                    val back=descendants(panel()).single { it.contentDescription==context.getString(R.string.ui_back) }
                    assertTrue("Floating Back is an icon button",back is android.widget.ImageButton)
                    back.performClick()
                }
                VirtualScreenState.control(false);await { !VirtualScreenState.manual }
                await { driver.observe().packageName==context.packageName }
                ui { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<VirtualHomeActivity>().single().startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                await { driver.observe().packageName=="com.android.settings" }
                VirtualScreenState.control(true);await { VirtualScreenState.manual }
                ui {
                    val home=panel().findViewById<View>(R.id.virtual_preview_home)
                    assertTrue("Floating Home is an icon button",home is android.widget.ImageButton)
                    home.performClick()
                }
                // Input and handoff share the service queue; observe only after manual ownership ends.
                VirtualScreenState.control(false);await { !VirtualScreenState.manual }
                await { driver.observe().packageName==context.packageName }
                assertTrue(driver.observe().nodes.any { it.text==context.getString(R.string.ui_wukong_virtual_display) })
                VirtualScreenState.control(true);await { VirtualScreenState.manual }
            }
            ui {
                val p=panel()
                assertEvenFooter()
                val bitmap=android.graphics.Bitmap.createBitmap(p.width,p.height,android.graphics.Bitmap.Config.ARGB_8888)
                p.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.filesDir,"floating-preview-resized.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                // Resize beyond both bounds: controls must stay visible and inside the display.
                drag(p.findViewById(R.id.virtual_preview_resize),5000f,5000f)
            }
            i.waitForIdleSync()
            ui {
                val p=panel();assertTrue(p.width<=context.resources.displayMetrics.widthPixels)
                assertTrue(p.height<=context.resources.displayMetrics.heightPixels)
                assertTrue(p.findViewById<View>(R.id.virtual_preview_home).isShown)
                assertEvenFooter()
                drag(p.findViewById(R.id.virtual_preview_resize),-5000f,-5000f)
            }
            i.waitForIdleSync()
            ui { assertEquals(WukongUi.dp(context,208),panel().width);assertTrue(panel().findViewById<View>(R.id.virtual_preview_home).isShown);assertEvenFooter() }
        } finally {
            ui { VirtualScreenState.stop(context);main.finish() }
            VirtualScreenState.enabled(context,enabled)
            prefs.edit().clear().also { edit -> previousGeometry.forEach { (key,value) -> edit.putFloat(key,value as Float) } }.commit()
        }
    }
}
