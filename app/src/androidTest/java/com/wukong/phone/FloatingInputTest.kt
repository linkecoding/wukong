package com.wukong.phone

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

/** Host adb injects real physical-display touches; the debug surface records received virtual touches. */
@RunWith(AndroidJUnit4::class)
class FloatingInputTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private val plan get()=File(context.filesDir,"floating-input-plan.json")
    private val ack get()=File(context.filesDir,"floating-input-ack.txt")
    private var sequence=0
    private val results=JSONArray()
    private fun ui(block: () -> Unit)=i.runOnMainSync(block)
    private fun await(label: String,condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+25000
        while(!condition()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50)
        assertTrue(label,condition())
    }
    private fun panel()=WindowInspector.getGlobalWindowViews().filterIsInstance<VirtualScreenPanel>().single()
    private fun center(view: View): Pair<Float,Float> {
        val pos=IntArray(2);view.getLocationOnScreen(pos)
        return (pos[0]+view.width/2f) to (pos[1]+view.height/2f)
    }
    private fun physicalPoint(point: Pair<Float,Float>,width: Int,height: Int): Pair<Int,Int> {
        var result=0 to 0
        ui {
            val image=panel().findViewById<ImageView>(R.id.virtual_preview_image)
            val drawable=image.drawable
            // Independent oracle: use ImageView's real drawable matrix, not the input mapper's formula.
            val bounds=RectF(0f,0f,drawable.intrinsicWidth.toFloat(),drawable.intrinsicHeight.toFloat())
            image.imageMatrix.mapRect(bounds)
            val pos=IntArray(2);image.getLocationOnScreen(pos)
            result=(pos[0]+bounds.left+point.first/width*bounds.width()).roundToInt() to
                (pos[1]+bounds.top+point.second/height*bounds.height()).roundToInt()
        }
        return result
    }
    private fun inject(kind: String,from: Pair<Int,Int>,to: Pair<Int,Int> = from) {
        val id=++sequence
        val value=JSONObject().put("id",id).put("kind",kind).put("x",from.first).put("y",from.second).put("endX",to.first).put("endY",to.second)
        val temp=File(context.filesDir,"floating-input-plan.tmp");temp.writeText(value.toString());assertTrue(temp.renameTo(plan))
        await("Host must acknowledge physical adb input $id") { ack.exists()&&ack.readText().trim()==id.toString() }
    }
    @Test fun physicalTouchesMatchVirtualTargetsAndScrollAtFivePreviewSizes() {
        assumeTrue("Run via scripts/test-floating-input.py",InstrumentationRegistry.getArguments().getString("physical_input")=="true")
        assumeTrue(!TaskRuntime.active)
        val preferences=context.getSharedPreferences("floating_preview",0);val previous=preferences.all.toMap()
        val enabled=VirtualScreenState.enabled(context)
        plan.delete();ack.delete()
        preferences.edit().putFloat("width",208f).putFloat("height",300f).putFloat("x",12f).putFloat("y",40f).commit()
        val main=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        var fixture: FloatingInputSurfaceActivity?=null
        try {
            ui { main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);VirtualScreenState.start(context) }
            await("Virtual display ready") { VirtualScreenState.displayId>0&&VirtualScreenState.bitmap!=null }
            val displayId=VirtualScreenState.displayId
            val status=ShellClient(context).request("virtualStatus") as JSONObject
            val virtualWidth=status.getInt("width");val virtualHeight=status.getInt("height")
            fixture=i.startActivitySync(Intent(context,FloatingInputSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()) as FloatingInputSurfaceActivity
            val surface=fixture
            VirtualScreenState.control(true);await("Manual control") { VirtualScreenState.manual }
            ui { main.moveTaskToBack(true) };await("Floating preview") { VirtualScreenState.service?.floatingVisible==true }
            for((width,height) in listOf(208 to 120,208 to 300,360 to 180,208 to 600,360 to 600)) {
                // Resize using physical adb touches on the handle while the preview stays attached.
                var handle=0 to 0;var destination=0 to 0
                val targetWidth=WukongUi.dp(context,width);val targetHeight=WukongUi.dp(context,height)
                val countBeforeResize=surface.touches.size
                ui {
                    val p=panel();val grip=center(p.findViewById(R.id.virtual_preview_resize))
                    handle=grip.first.roundToInt() to grip.second.roundToInt()
                    destination=(handle.first+targetWidth-p.width) to (handle.second+targetHeight-p.previewHeight)
                }
                inject("swipe",handle,destination)
                ui { File(context.filesDir,"floating-input-resize.txt").appendText("$width x $height -> ${panel().width} x ${panel().previewHeight} (expected $targetWidth x $targetHeight)\n") }
                await("Live resize reached ${width}x$height") {
                    var reached=false;ui { reached=abs(panel().width-targetWidth)<=3&&abs(panel().previewHeight-targetHeight)<=3 };reached
                }
                i.waitForIdleSync();Thread.sleep(450)
                assertEquals("Resize handle must not inject virtual touches",countBeforeResize,surface.touches.size)
                var tolerance=0f
                ui {
                    val image=panel().findViewById<ImageView>(R.id.virtual_preview_image)
                    val d=image.drawable;val rect=RectF(0f,0f,d.intrinsicWidth.toFloat(),d.intrinsicHeight.toFloat());image.imageMatrix.mapRect(rect)
                    tolerance=max(virtualWidth/rect.width(),virtualHeight/rect.height())+4f
                    surface.vertical.scrollTo(0,0);surface.horizontal.scrollTo(0,0)
                }
                val measurements=JSONArray()
                fun check(label: String,start: Pair<Float,Float>,end: Pair<Float,Float> = start) {
                    val count=surface.touches.size
                    inject(if(start==end)"tap" else "swipe",physicalPoint(start,virtualWidth,virtualHeight),physicalPoint(end,virtualWidth,virtualHeight))
                    await("Virtual touch received: ${width}x$height $label") { surface.touches.size>count }
                    val actual=surface.touches.last()
                    val error=maxOf(abs(actual.downX-start.first),abs(actual.downY-start.second),abs(actual.upX-end.first),abs(actual.upY-end.second))
                    assertTrue("${width}x$height $label coordinate error $error > $tolerance",error<=tolerance)
                    measurements.put(JSONObject().put("operation",label).put("max_error_virtual_px",error).put("tolerance_virtual_px",tolerance))
                }
                for(index in listOf(0,2,4,6,8)) {
                    var point=0f to 0f
                    ui { surface.selected=-1;point=center(surface.targets[index]) }
                    check("target_$index",point)
                    await("Correct native button $index clicked") { surface.selected==index }
                }
                var verticalStart=0f to 0f;var verticalEnd=0f to 0f
                var horizontalStart=0f to 0f;var horizontalEnd=0f to 0f
                ui {
                    val v=center(surface.vertical);verticalStart=v.first to (v.second+surface.vertical.height*.3f);verticalEnd=v.first to (v.second-surface.vertical.height*.3f)
                    val h=center(surface.horizontal);horizontalStart=(h.first+surface.horizontal.width*.3f) to h.second;horizontalEnd=(h.first-surface.horizontal.width*.3f) to h.second
                }
                check("swipe_up",verticalStart,verticalEnd);Thread.sleep(600)
                var scrolled=0;ui { scrolled=surface.vertical.scrollY };assertTrue("Native list scrolls forward",scrolled>100)
                check("swipe_down",verticalEnd,verticalStart);Thread.sleep(600)
                ui { assertTrue("Native list scrolls back",surface.vertical.scrollY<scrolled) }
                check("swipe_left",horizontalStart,horizontalEnd);Thread.sleep(600)
                ui { scrolled=surface.horizontal.scrollX };assertTrue("Native horizontal content scrolls forward",scrolled>100)
                check("swipe_right",horizontalEnd,horizontalStart);Thread.sleep(600)
                ui { assertTrue("Native horizontal content scrolls back",surface.horizontal.scrollX<scrolled) }
                val marginPoints=mutableListOf<Pair<Int,Int>>()
                ui {
                    val image=panel().findViewById<ImageView>(R.id.virtual_preview_image);val d=image.drawable
                    val r=RectF(0f,0f,d.intrinsicWidth.toFloat(),d.intrinsicHeight.toFloat());image.imageMatrix.mapRect(r)
                    val pos=IntArray(2);image.getLocationOnScreen(pos)
                    if(r.left>4) {
                        marginPoints+=(pos[0]+r.left/2).roundToInt() to (pos[1]+image.height/2)
                        marginPoints+=(pos[0]+r.right+(image.width-r.right)/2).roundToInt() to (pos[1]+image.height/2)
                    } else {
                        assertTrue("Tall preview has vertical letterboxing",r.top>4)
                        marginPoints+=(pos[0]+image.width/2) to (pos[1]+r.top/2).roundToInt()
                        marginPoints+=(pos[0]+image.width/2) to (pos[1]+r.bottom+(image.height-r.bottom)/2).roundToInt()
                    }
                }
                for(point in marginPoints) {
                    val count=surface.touches.size;inject("tap",point);Thread.sleep(500)
                    assertEquals("Letterbox taps must not reach virtual display",count,surface.touches.size)
                }
                ui {
                    val p=panel();val bitmap=android.graphics.Bitmap.createBitmap(p.width,p.height,android.graphics.Bitmap.Config.ARGB_8888);p.draw(android.graphics.Canvas(bitmap))
                    File(context.filesDir,"floating-input-${width}x$height.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                results.put(JSONObject().put("width_dp",width).put("preview_height_dp",height).put("operations",measurements).put("letterbox_taps_ignored",2))
                File(context.filesDir,"floating-input-report.json").writeText(results.toString(2))
            }
        } finally {
            plan.delete();ack.delete()
            ui { fixture?.finish();VirtualScreenState.stop(context);main.finish() }
            preferences.edit().clear().also { edit -> previous.forEach { (key,value) -> edit.putFloat(key,value as Float) } }.commit()
            VirtualScreenState.enabled(context,enabled)
        }
    }
}
