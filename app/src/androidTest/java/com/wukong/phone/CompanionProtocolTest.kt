package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wukong.phone.companion.CompanionServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class CompanionProtocolTest {
    private val c get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = "b".repeat(64)
    private fun request(port: Int, headers: String, path: String = "/v1/model", method: String = "GET", body: String = ""): Pair<Int, String> = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10000
        s.getOutputStream().write("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n$headers\r\n$body".toByteArray())
        val value = s.getInputStream().bufferedReader().readText()
        value.lineSequence().first().split(' ')[1].toInt() to value.substringAfter("\r\n\r\n")
    }
    @Test fun authorizationFramingAndModelRedaction() {
        val port = 39744
        val server = CompanionServer(c, token, port)
        server.start()
        try {
            assertEquals(401, request(port, "").first)
            assertEquals(401, request(port, "Authorization: Bearer wrong\r\n").first)
            assertEquals(401, request(port, "Authorization: Bearer $token\r\nOrigin: http://evil.test\r\n").first)
            val model = request(port, "Authorization: Bearer $token\r\n")
            assertEquals(200, model.first)
            val json = JSONObject(model.second)
            assertFalse(json.has("key")); assertFalse(json.has("apiKey"))
            val saved = SettingsStore(c).load()
            if (saved != null) assertFalse(model.second.contains(saved.apiKey))
            assertEquals(400, request(port, "Authorization: Bearer $token\r\nContent-Length: 65537\r\nContent-Type: application/json\r\n", method = "POST").first)
            assertEquals(400, request(port, "Authorization: Bearer $token\r\nContent-Length: 2\r\nContent-Length: 2\r\nContent-Type: application/json\r\n", method = "POST", body = "{}").first)
            assertEquals(400, request(port, "Authorization: Bearer $token\r\nTransfer-Encoding: chunked\r\n", method = "POST").first)
            assertEquals(404, request(port, "Authorization: Bearer $token\r\n", path = "/v1/shell").first)
        } finally { server.close() }
    }
    @Test fun bootstrapAndActivityRequireShellPermission() {
        val provider = c.packageManager.resolveContentProvider("com.wukong.phone.bootstrap", 0)!!
        assertEquals("android.permission.DUMP", provider.readPermission)
        val activity = c.packageManager.getActivityInfo(android.content.ComponentName(c, "com.wukong.phone.companion.CompanionActivity"), 0)
        assertEquals("android.permission.DUMP", activity.permission)
        assertFalse(c.packageManager.getServiceInfo(android.content.ComponentName(c, "com.wukong.phone.companion.CompanionService"), 0).exported)
        try { c.contentResolver.call(android.net.Uri.parse("content://com.wukong.phone.bootstrap"), "companion", null, null); fail("App UID must not provision remote credentials") }
        catch (_: SecurityException) { }
    }
    @Test fun disconnectRevokesTheCredentialAndClosesTheListener() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(c, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val original = com.wukong.phone.companion.CompanionCredentials.get(c)
        try {
            instrumentation.runOnMainSync { c.startForegroundService(android.content.Intent(c, com.wukong.phone.companion.CompanionService::class.java)) }
            val deadline = System.currentTimeMillis() + 5000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                ready = runCatching { request(39743, "Authorization: Bearer $original\r\n").first == 200 }.getOrDefault(false)
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Companion listener not ready", ready)
            instrumentation.runOnMainSync { c.startService(android.content.Intent(c, com.wukong.phone.companion.CompanionService::class.java).setAction("disconnect")) }
            instrumentation.waitForIdleSync()
            assertNotEquals(original, com.wukong.phone.companion.CompanionCredentials.get(c))
            assertTrue(runCatching { request(39743, "Authorization: Bearer $original\r\n").first != 200 }.getOrDefault(true))
        } finally {
            instrumentation.runOnMainSync { c.stopService(android.content.Intent(c, com.wukong.phone.companion.CompanionService::class.java)); activity.finish() }
        }
    }
}
