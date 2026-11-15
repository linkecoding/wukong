package com.wukong.phone

import android.app.*
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ContextThemeWrapper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture

/** One polling stream serves both the embedded view and background floating monitor. */
class VirtualScreenService : Service() {
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val ownerId = java.util.UUID.randomUUID().toString()
    private var polling: ScheduledFuture<*>? = null
    private var overlay: View? = null
    private var overlayManager: WindowManager? = null
    internal val floatingVisible get() = overlay != null
    private var revision = -1L
    @Volatile private var closed = false
    override fun onCreate() {
        super.onCreate()
        VirtualScreenState.service = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("virtual", "悟空虚拟屏", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 51, Intent(this, VirtualScreenService::class.java).setAction("close"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(5, Notification.Builder(this, "virtual").setSmallIcon(R.drawable.ic_wukong).setContentTitle("悟空虚拟屏")
            .setContentText("前台内嵌显示，后台悬浮显示；可随时接管").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭虚拟屏", stop).build()).build())
        VirtualScreenState.message = "正在创建虚拟屏…"; VirtualScreenState.changed()
        io.execute {
            try {
                val status = ShellClient(this).request("virtualStart",JSONObject().put("ownerId",ownerId)) as JSONObject
                VirtualScreenState.displayId = status.getInt("displayId")
                VirtualScreenState.manual = status.getBoolean("manual")
                VirtualScreenState.message = "虚拟屏已启动"
            } catch (error: Exception) { VirtualScreenState.message = error.message ?: "虚拟屏启动失败" }
            VirtualScreenState.changed()
        }
        polling = io.scheduleWithFixedDelay({ poll() }, 300, 350, TimeUnit.MILLISECONDS)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") VirtualScreenState.stop(this)
        return START_NOT_STICKY
    }
    private fun poll() {
        if (closed || VirtualScreenState.displayId == 0) return
        try {
            val packet = ShellClient(this).request("virtualFrame") as JSONObject
            if (closed) return
            VirtualScreenState.manual = packet.getBoolean("manual")
            val next = packet.getLong("revision")
            if (next != revision && packet.has("jpeg")) {
                val bytes = Base64.decode(packet.getString("jpeg"), Base64.NO_WRAP)
                VirtualScreenState.bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                revision = next
            }
            VirtualScreenState.message = if (VirtualScreenState.manual) "你已接管 · 触摸画面操作" else if (TaskRuntime.active && TaskRuntime.displayId > 0) "Agent 正在操作虚拟屏" else "虚拟屏就绪 · 可开始任务或接管"
        } catch (error: Exception) { VirtualScreenState.message = error.message ?: "画面连接中断" }
        VirtualScreenState.changed()
    }
    fun control(manual: Boolean) {
        if (manual) TaskRuntime.pauseForHuman(true)
        io.execute {
            if (closed) return@execute
            try {
                ShellClient(this).request("setControl", JSONObject().put("manual", manual))
                VirtualScreenState.manual = manual
                if (!manual) TaskRuntime.pauseForHuman(false)
            } catch (error: Exception) { VirtualScreenState.message = error.message ?: "切换失败" }
            VirtualScreenState.changed()
        }
    }
    fun input(value: JSONObject) {
        io.execute {
            if (closed) return@execute
            try { ShellClient(this).request("manualInput", value) }
            catch (error: Exception) { VirtualScreenState.message = error.message ?: "输入失败"; VirtualScreenState.changed() }
        }
    }
    internal fun updateOverlay() {
        val visible = !closed && !VirtualScreenState.foreground && VirtualScreenState.displayId > 0 && Settings.canDrawOverlays(this)
        if (!visible) { hideOverlay(); return }
        if (overlay != null) return
        // A Service's default resources can follow the virtual display's config.
        // Always build the overlay using the physical display's density and dimensions.
        val physical = getSystemService(DisplayManager::class.java).getDisplay(0)
        val ui = ContextThemeWrapper(createDisplayContext(physical), R.style.Theme_Wukong)
        val metrics = ui.resources.displayMetrics
        val manager = ui.getSystemService(WindowManager::class.java)
        val panel = VirtualScreenPanel(ui, compact = true)
        val params = WindowManager.LayoutParams((220 * metrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; y = (70 * metrics.density).toInt()
        }
        panel.onDrag = { dx,dy ->
            params.x = (params.x-dx).toInt().coerceIn(0,(metrics.widthPixels-panel.width).coerceAtLeast(0))
            params.y = (params.y+dy).toInt().coerceIn(0,(metrics.heightPixels-panel.height).coerceAtLeast(0))
            runCatching { manager.updateViewLayout(panel,params) }
        }
        try { manager.addView(panel, params); overlay = panel; overlayManager = manager }
        catch (error: Exception) { VirtualScreenState.message = error.message ?: "无法显示悬浮窗" }
    }
    private fun hideOverlay() { overlay?.let { runCatching { overlayManager?.removeView(it) } }; overlay = null; overlayManager = null }
    override fun onDestroy() {
        closed = true; polling?.cancel(false); hideOverlay()
        if (TaskRuntime.displayId > 0) TaskRuntime.stop()
        io.execute { runCatching { ShellClient(this).request("virtualStop",JSONObject().put("ownerId",ownerId)) } }
        io.shutdown()
        VirtualScreenState.service = null; VirtualScreenState.displayId = 0; VirtualScreenState.bitmap = null
        VirtualScreenState.message = "虚拟屏已关闭"; VirtualScreenState.changed()
        super.onDestroy()
    }
}
