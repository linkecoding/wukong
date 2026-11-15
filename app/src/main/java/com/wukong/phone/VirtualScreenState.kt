package com.wukong.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object VirtualScreenState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var displayId = 0; internal set
    @Volatile var manual = false; internal set
    @Volatile var bitmap: Bitmap? = null; internal set
    @Volatile var frameError: String? = null; internal set
    @Volatile var message = "虚拟屏未启动"; internal set
    @Volatile var foreground = false; private set
    @Volatile internal var service: VirtualScreenService? = null
    fun enabled(context: Context) = context.getSharedPreferences("virtual", 0).getBoolean("enabled", false)
    fun enabled(context: Context, value: Boolean) { context.getSharedPreferences("virtual", 0).edit().putBoolean("enabled", value).apply() }
    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    internal fun changed() { main.post { listeners.forEach { it() }; service?.updateOverlay() } }
    private val visibleActivities = mutableSetOf<Activity>()
    @Volatile internal var floatingDismissed = false; private set
    @Suppress("DEPRECATION")
    internal fun activityStarted(activity: Activity) {
        // VirtualHomeActivity is part of this package but is not the physical Wukong UI.
        if (activity is VirtualHomeActivity || activity.windowManager.defaultDisplay.displayId != 0) return
        visibleActivities += activity
        updateForeground()
    }
    internal fun activityStopped(activity: Activity) {
        visibleActivities -= activity
        // An internal Activity transition must not briefly create a floating window.
        main.post { updateForeground() }
    }
    private fun updateForeground() {
        val visible = visibleActivities.isNotEmpty()
        if (foreground == visible) return
        foreground = visible
        if (visible) floatingDismissed = false
        changed()
    }
    fun dismissFloatingPreview() { floatingDismissed = true; changed() }
    fun start(context: Context) {
        require(android.os.Build.VERSION.SDK_INT >= 30) { "虚拟屏模式需要 Android 11 或以上" }
        context.startForegroundService(Intent(context, VirtualScreenService::class.java))
    }
    fun stop(context: Context) {
        if (TaskRuntime.displayId > 0) TaskRuntime.stop()
        enabled(context, false)
        context.stopService(Intent(context, VirtualScreenService::class.java))
    }
    fun control(manual: Boolean) { service?.control(manual) }
    fun input(value: JSONObject) { service?.input(value) }
}
