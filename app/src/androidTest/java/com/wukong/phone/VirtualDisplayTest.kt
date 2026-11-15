package com.wukong.phone

import android.graphics.BitmapFactory
import android.app.ActivityOptions
import android.content.Intent
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualDisplayTest {
    @Test fun repeatedDisplayCreationCaptureAndReleaseKeepsRuntimeAlive() {
        org.junit.Assume.assumeTrue("High-frequency lifecycle stress runs on an emulator", android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ShellClient(context)
        repeat(8) {
            client.request("virtualStart")
            Thread.sleep(100)
            client.request("virtualFrame")
            client.request("virtualStop")
            assertEquals(2000,client.status().getInt("uid"))
        }
    }
    @Test fun stalePreviewOwnerCannotCloseANewerPreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ShellClient(context)
        val first = java.util.UUID.randomUUID().toString(); val second = java.util.UUID.randomUUID().toString()
        try {
            client.request("virtualStart",JSONObject().put("ownerId",first))
            val next = client.request("virtualStart",JSONObject().put("ownerId",second)) as JSONObject
            assertEquals("stale_owner",client.request("virtualStop",JSONObject().put("ownerId",first)))
            assertEquals(next.getInt("displayId"),(client.request("virtualStatus") as JSONObject).getInt("displayId"))
        } finally { client.request("virtualStop",JSONObject().put("ownerId",second)) }
    }
    @Test fun virtualDisplayHasOwnFramesWindowsAndInputWithManualExclusion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ShellClient(context)
        val status = client.request("virtualStart") as JSONObject
        val displayId = status.getInt("displayId")
        assertTrue(displayId > 0)
        try {
            var packet = JSONObject()
            for (attempt in 0 until 30) {
                packet = client.request("virtualFrame") as JSONObject
                if (packet.has("jpeg")) break
                Thread.sleep(200)
            }
            val bytes = Base64.decode(packet.getString("jpeg"), Base64.NO_WRAP)
            val frame = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue(frame.width > 0 && frame.height > 0)
            assertTrue(frame.width <= status.getInt("width") && frame.height <= status.getInt("height")); frame.recycle()
            ShellSession(context, displayId).use { session ->
                val driver = AndroidDriver(session)
                var screen = driver.observe()
                assertEquals(context.packageName, screen.packageName)
                assertTrue(screen.nodes.any { it.text.contains("悟空虚拟屏") })
                val opened = Agent(Model { _, state, _ -> if (state.packageName == "com.android.settings") Action.Finish("opened") else Action.Launch("com.android.settings") },
                    driver, Approval { _, _ -> false }, maxSteps = 6).run("打开系统设置",Cancellation()) { }
                assertEquals(opened.message,Phase.COMPLETED,opened.phase)
                screen = driver.observe(); assertEquals("com.android.settings", screen.packageName)
                client.request("setControl", JSONObject().put("manual", true))
                try { driver.execute(Action.Home, screen); fail("Agent cannot inject while human owns display") } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("接管")) }
                client.request("manualInput", JSONObject().put("kind", "home"))
                client.request("setControl", JSONObject().put("manual", false))
                for (attempt in 0 until 30) {
                    screen = driver.observe()
                    if (screen.packageName == context.packageName && screen.nodes.any { it.text.contains("悟空虚拟屏") }) break
                    Thread.sleep(100)
                }
                assertEquals(context.packageName, screen.packageName)
                assertTrue(screen.nodes.any { it.text.contains("悟空虚拟屏") })
                val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    context.startActivity(Intent(context, TestSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),options.toBundle())
                }
                Thread.sleep(500)
                val hint = context.getString(R.string.test_query)
                for (attempt in 0 until 20) {
                    screen = driver.observe()
                    if (screen.nodes.any { it.editable && it.text.contains(hint) }) break
                    Thread.sleep(150)
                }
                val input = screen.nodes.single { it.editable && it.text.contains(hint) }.bounds!!
                assertTrue("Virtual input bounds: $input",input.left in 0..1000 && input.right in 0..1000 && input.top in 0..1000 && input.bottom in 0..1000)
                val x = (input.left+input.right)/2; val y = (input.top+input.bottom)/2
                client.request("setControl",JSONObject().put("manual",true))
                client.request("manualInput",JSONObject().put("kind","gesture").put("x",x).put("y",y).put("endX",x).put("endY",y).put("duration",60))
                client.request("manualInput",JSONObject().put("kind","text").put("text","虚拟屏中文接管测试"))
                client.request("setControl",JSONObject().put("manual",false))
                assertTrue(driver.observe().nodes.any { it.text.contains("虚拟屏中文接管测试") })
                ShellSession(context).use { main -> assertFalse(AndroidDriver(main).observe().nodes.any { it.text.contains("虚拟屏中文接管测试") }) }
            }
        } finally { client.request("virtualStop") }
    }
}
