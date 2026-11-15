package com.wukong.phone

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingRecoveryTest {
    /** Harness stops only Wukong's engine before running this opt-in test. */
    @Test fun launchingAppRestoresAnAuthorizedConnectionWithoutStartingATask() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restore_connection") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.getSharedPreferences("wireless", 0).getBoolean("paired", false))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val end = System.currentTimeMillis() + 40_000
            var ready = false
            while (!ready && System.currentTimeMillis() < end) {
                ready = runCatching { ShellClient(context).status().getInt("uid") == 2000 }.getOrDefault(false)
                if (!ready) Thread.sleep(200)
            }
            assertTrue("Opening Wukong should restore its previously authorized engine", ready)
            assertFalse(TaskRuntime.active)
            assertNotNull(WirelessActivation.connectPort)
            assertFalse("A healthy connection must not be repeatedly restarted", ConnectionRecovery.restore(context))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
