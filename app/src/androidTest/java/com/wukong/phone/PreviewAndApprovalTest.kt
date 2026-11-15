package com.wukong.phone

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewAndApprovalTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context get() = i.targetContext
    private fun ui(block: () -> Unit) = i.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Expected device state did not arrive", condition())
    }
    private fun open(type: Class<out Activity>, modelOnly: Boolean = false) = i.startActivitySync(
        Intent(context, type).putExtra("model_only", modelOnly).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())

    @Test fun everyPhysicalWukongPageHidesPreviewAndCloseOnlyDismissesTheWindow() {
        assumeTrue(!TaskRuntime.active); assertTrue(Settings.canDrawOverlays(context))
        val previous = VirtualScreenState.enabled(context)
        val main = open(MainActivity::class.java)
        var setup: Activity? = null; var wireless: Activity? = null
        try {
            ui { main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); VirtualScreenState.enabled(context,true); VirtualScreenState.start(main) }
            await { VirtualScreenState.displayId > 0 && VirtualScreenState.bitmap != null }
            val id = VirtualScreenState.displayId
            assertTrue(VirtualScreenState.foreground); assertFalse(VirtualScreenState.service!!.floatingVisible)
            setup = open(SetupActivity::class.java, true)
            await { VirtualScreenState.foreground }; i.waitForIdleSync()
            assertFalse("Model configuration must not show the floating preview", VirtualScreenState.service!!.floatingVisible)
            wireless = open(WirelessSetupActivity::class.java)
            i.waitForIdleSync(); assertTrue(VirtualScreenState.foreground); assertFalse(VirtualScreenState.service!!.floatingVisible)
            ui { wireless!!.finish(); setup!!.finish() }
            await { main.hasWindowFocus() }
            ui { main.moveTaskToBack(true) }
            await { VirtualScreenState.service!!.floatingVisible }
            assertFalse("The virtual launcher must not count as physical foreground", VirtualScreenState.foreground)
            ui {
                val close = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<View>(R.id.virtual_preview_close) }
                assertNotNull("Floating preview needs a real close button", close)
                val panel = WindowInspector.getGlobalWindowViews().first { it.findViewById<View>(R.id.virtual_preview_close) != null }
                val bitmap = android.graphics.Bitmap.createBitmap(panel.width, panel.height, android.graphics.Bitmap.Config.ARGB_8888)
                panel.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.filesDir, "floating-preview-close.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle(); close!!.performClick()
            }
            await { !VirtualScreenState.service!!.floatingVisible }
            Thread.sleep(900) // Cover multiple frame polls: dismissal must persist.
            assertFalse(VirtualScreenState.service!!.floatingVisible)
            assertEquals(id, VirtualScreenState.displayId)
            assertEquals(id, (ShellClient(context).request("virtualStatus") as org.json.JSONObject).getInt("displayId"))
            ui { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await { VirtualScreenState.foreground }; assertFalse(VirtualScreenState.service!!.floatingVisible)
            ui { main.moveTaskToBack(true) }; await { VirtualScreenState.service!!.floatingVisible }
            assertEquals(id, VirtualScreenState.displayId)
        } finally {
            ui { wireless?.finish(); setup?.finish(); VirtualScreenState.stop(context); main.finish() }
            VirtualScreenState.enabled(context, previous)
        }
    }

    @Test fun ordinaryRealButtonsAreAutonomousWhileSyntheticPaymentAndDeletionAsk() {
        assumeTrue(!TaskRuntime.active)
        val previous = VirtualScreenState.enabled(context)
        ui { VirtualScreenState.enabled(context,false); VirtualScreenState.stop(context) }
        val a = open(MainActivity::class.java)
        val labels = listOf("允许", "发送", "发布", "分享", "上传", "安装", "权限设置", "支付方式", "删除文件的方法", "立即支付", "删除文件")
        val clicked = mutableListOf<String>(); val approvals = mutableListOf<String>()
        try {
            ui {
                a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val root = LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(30,160,30,40) }
                labels.forEach { label -> root.addView(Button(a).apply { text=label; isAllCaps=false; setOnClickListener { clicked += label } }, LinearLayout.LayoutParams(-1,110)) }
                a.setContentView(root)
            }
            i.waitForIdleSync(); await { a.hasWindowFocus() }
            var index = 0
            ShellSession(context).use { session ->
                val driver = AndroidDriver(session)
                val outcome = Agent(Model { _, screen, _ ->
                    if (index < labels.size) { val label = labels[index++]; Action.Tap(screen.nodes.single { it.text == label && it.clickable }.id) }
                    else Action.Finish("fixture complete", evidence=listOf(ScreenCondition(packageName=context.packageName)))
                }, driver, Approval { action, screen ->
                    approvals += screen.nodes.single { it.id == (action as Action.Tap).nodeId }.text
                    true // These are synthetic buttons: no real payment/deletion exists.
                }, allowedPackage=context.packageName).run("Exercise synthetic approval controls only", Cancellation()) {}
                assertEquals(outcome.message, Phase.COMPLETED, outcome.phase)
            }
            i.waitForIdleSync(); assertEquals(labels, clicked); assertEquals(listOf("立即支付", "删除文件"), approvals)
        } finally { ui { a.finish() }; VirtualScreenState.enabled(context,previous) }
    }
}
