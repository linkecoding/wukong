package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellRecoveryTest {
    @Test fun readsCurrentAppAfterWindowOrAutomationConnectionChanges() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("read_current") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val port = args.getString("wireless_port")?.toIntOrNull()
        if (port != null) {
            val adb = LocalAdb(AdbIdentity.load(context))
            try { adb.connectLocal(port); adb.activate(context) { } } finally { adb.disconnect() }
        }
        ShellSession(context).use { session ->
            repeat(4) {
                val screen = AndroidDriver(session).observe()
                assertTrue("当前应用应提供节点", screen.nodes.isNotEmpty())
                assertTrue(screen.packageName.isNotBlank())
            }
        }
    }
}
