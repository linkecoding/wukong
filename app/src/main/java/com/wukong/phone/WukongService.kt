package com.wukong.phone

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wukong.core.*
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.lang.ref.WeakReference

/** Visible task controls only. This service has no device-control privileges. */
class WukongService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val update: () -> Unit = {
        if (VirtualScreenState.foreground && TaskRuntime.displayId > 0 && TaskRuntime.pending == null) hideControls()
        else if (TaskRuntime.active && TaskRuntime.displayId > 0) { if (overlay == null) showControls() else renderControls() }
        else if (overlay != null) renderControls()
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tasks", "悟空任务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WukongService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "tasks").setSmallIcon(R.drawable.ic_wukong)
            .setContentTitle("悟空任务控制").setContentText("普通操作自动执行，敏感操作需确认；可随时停止")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build())
        serviceReference = WeakReference(this); TaskRuntime.listen(update); VirtualScreenState.listen(update); TaskRuntime.connectionChanged()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { TaskRuntime.stop(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() { TaskRuntime.stop(); TaskRuntime.unlisten(update); VirtualScreenState.unlisten(update); hideControls(); if (instance === this) serviceReference = null; TaskRuntime.connectionChanged() }

    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return try { task.get(5, TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
        catch (e: Exception) { task.cancel(false); throw e }
    }

    fun showControls() {
        if (VirtualScreenState.foreground && TaskRuntime.displayId > 0 && TaskRuntime.pending == null) return
        if (overlay != null) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(getColor(R.color.paper)) }
        overlay = box
        val width = minOf(dp(340), resources.displayMetrics.widthPixels - dp(24))
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(32)
        }
        try { getSystemService(WindowManager::class.java).addView(box, params); renderControls() }
        catch (e: Exception) { overlay = null; throw e }
    }

    private fun renderControls() {
        val box = overlay as? LinearLayout ?: return
        box.removeAllViews()
        fun text(value: String, size: Float = 14f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(getColor(R.color.ink)); setPadding(0, 0, 0, dp(8))
        }
        box.addView(text("悟空 · ${TaskRuntime.appLabel}", 16f))
        val request = TaskRuntime.pending
        val detail = if (request == null) TaskRuntime.event.message else {
            val id = when (val action = request.action) {
                is Action.Tap -> action.nodeId
                is Action.Input -> action.nodeId
                is Action.Scroll -> action.nodeId
                else -> null
            }
            val target = id?.let { nodeId -> request.screen.nodes.find { it.id == nodeId }?.text }.orEmpty()
            buildString {
                append("允许这一步？\n所在应用：${request.screen.packageName}\n${request.action.label()}")
                if (target.isNotBlank()) append("\n目标：$target")
                if (request.action is Action.Input) append("\n输入内容（替换原文本）：${request.action.text}")
            }
        }
        val scroll = ScrollView(this).apply { addView(text(detail)) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { height = if (request != null) dp(132) else dp(64) })
        val row = LinearLayout(this)
        fun button(label: String, click: () -> Unit) {
            row.addView(Button(this).apply { text = label; textSize = 13f; isAllCaps = false; setSingleLine(); minWidth = 0; minimumWidth = 0; setPadding(dp(4), 0, dp(4), 0); setOnClickListener { click() } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        if (request != null && !TaskRuntime.stopRequested) {
            button("允许一次") { TaskRuntime.answer(request, true) }
            button("拒绝") { TaskRuntime.answer(request, false) }
        }
        if (TaskRuntime.active) button(if (TaskRuntime.stopRequested) "停止中" else "停止") { TaskRuntime.stop() }
        else button("收起") { hideControls() }
        button("回悟空") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        box.addView(row)
    }

    fun hideControls() { overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; overlay = null }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        @Volatile private var serviceReference: WeakReference<WukongService>? = null
        val instance: WukongService? get() = serviceReference?.get()
    }
}
