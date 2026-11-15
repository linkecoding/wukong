package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a real system pairing completed in Wukong, never host-provisioned ADB keys. */
@RunWith(AndroidJUnit4::class)
class WirelessActivationTest {
    @Test fun persistedIdentityReconnectsAndRapidStreamsDoNotLoseAcknowledgements() {
        val port = InstrumentationRegistry.getArguments().getString("wireless_port")?.toIntOrNull()
        assumeTrue("Provide the paired emulator's current wireless connection port", port != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Load the encrypted identity in a fresh instrumentation process, reconnect and
        // exercise the fast loopback OPEN/OKAY path repeatedly before reactivation.
        repeat(2) {
            val adb = LocalAdb(AdbIdentity.load(context))
            try {
                adb.connectLocal(port!!)
                repeat(50) { assertEquals("2000", adb.shell("id -u").trim()) }
                adb.activate(context) { }
                assertEquals(2000, ShellClient(context).status().getInt("uid"))
            } finally { adb.disconnect() }
            // The detached execution process must survive closing its ADB connection.
            Thread.sleep(1200)
            assertEquals(2000, ShellClient(context).status().getInt("uid"))
        }
    }
}
