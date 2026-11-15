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
    @Volatile var message = "虚拟屏未启动"; internal set
    @Volatile var foreground = true; private set
    @Volatile internal var service: VirtualScreenService? = null
    fun enabled(context: Context) = context.getSharedPreferences("virtual", 0).getBoolean("enabled", false)
    fun enabled(context: Context, value: Boolean) { context.getSharedPreferences("virtual", 0).edit().putBoolean("enabled", value).apply() }
    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    internal fun changed() { main.post { listeners.forEach { it() }; service?.updateOverlay() } }
    fun foreground(value: Boolean) { foreground = value; changed() }
    fun start(context: Context) {
        require(android.os.Build.VERSION.SDK_INT >= 30) { "虚拟屏模式需要 Android 11 或以上" }
        if (context is Activity) foreground(true)
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
