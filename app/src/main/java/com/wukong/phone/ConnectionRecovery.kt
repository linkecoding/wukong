package com.wukong.phone

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors

/** Bounded reconnect for an identity the user has already paired. Never pairs or runs a task. */
object ConnectionRecovery {
    private val worker = Executors.newSingleThreadExecutor()
    private var lastAttempt = 0L
    private var lastPort = 0
    @Synchronized fun restore(context: Context, port: Int? = WirelessActivation.connectPort): Boolean {
        if (port == null || TaskRuntime.active || WirelessActivation.busy ||
            !context.getSharedPreferences("wireless", 0).getBoolean("paired", false)) return false
        val now = SystemClock.elapsedRealtime()
        if (port == lastPort && now - lastAttempt < 60_000) return false
        lastPort = port; lastAttempt = now
        val app = context.applicationContext
        worker.execute {
            if (runCatching { ShellClient(app).status() }.isFailure && !TaskRuntime.active && !WirelessActivation.busy) {
                runCatching { WirelessActivation.activate(app, port) }
            }
        }
        return true
    }
}
