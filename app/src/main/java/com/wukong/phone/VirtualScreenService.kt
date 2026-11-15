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
import kotlin.math.roundToInt

/** One polling stream serves both the embedded view and background floating monitor. */
class VirtualScreenService : LocalizedService() {
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
        VirtualScreenState.service = this; VirtualScreenState.frameError=null
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("virtual", getString(R.string.ui_wukong_virtual_display), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 51, Intent(this, VirtualScreenService::class.java).setAction("close"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(5, Notification.Builder(this, "virtual").setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.ui_wukong_virtual_display))
            .setContentText(getString(R.string.ui_embedded_in_wukong_floating_in_the_background_take_over_anytime)).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.ui_close_display), stop).build()).build())
        VirtualScreenState.message = getString(R.string.ui_creating_virtual_display); VirtualScreenState.changed()
        io.execute {
            try {
                val status = ShellClient(this).request("virtualStart",JSONObject().put("ownerId",ownerId)) as JSONObject
                VirtualScreenState.displayId = status.getInt("displayId")
                VirtualScreenState.manual = status.getBoolean("manual")
                VirtualScreenState.message = getString(R.string.ui_virtual_display_started)
            } catch (error: Exception) { VirtualScreenState.message = error.message ?: getString(R.string.ui_could_not_start_the_virtual_display) }
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
            VirtualScreenState.frameError=null
            VirtualScreenState.manual = packet.getBoolean("manual")
            val next = packet.getLong("revision")
            if (next != revision && packet.has("jpeg")) {
                val bytes = Base64.decode(packet.getString("jpeg"), Base64.NO_WRAP)
                VirtualScreenState.bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                revision = next
            }
            VirtualScreenState.message = if (VirtualScreenState.manual) getString(R.string.ui_you_are_in_control_touch_the_preview) else if (TaskRuntime.active && TaskRuntime.displayId > 0) getString(R.string.ui_agent_is_working_on_the_virtual_display) else getString(R.string.ui_display_ready_run_a_task_or_take_over)
        } catch (error: Exception) { VirtualScreenState.message = error.message ?: getString(R.string.ui_display_connection_interrupted); VirtualScreenState.frameError=VirtualScreenState.message }
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
            } catch (error: Exception) { VirtualScreenState.message = error.message ?: getString(R.string.ui_could_not_switch_control) }
            VirtualScreenState.changed()
        }
    }
    fun input(value: JSONObject) {
        io.execute {
            if (closed) return@execute
            try { ShellClient(this).request("manualInput", value) }
            catch (error: Exception) {
                VirtualScreenState.message = error.message ?: getString(R.string.ui_could_not_send_input)
                val message=VirtualScreenState.message
                android.os.Handler(mainLooper).post { android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_SHORT).show() }
                VirtualScreenState.changed()
            }
        }
    }
    internal fun updateOverlay() {
        if(android.os.Build.VERSION.SDK_INT<30) { hideOverlay();return }
        val visible = !closed && !VirtualScreenState.foreground && !VirtualScreenState.floatingDismissed && VirtualScreenState.displayId > 0 && Settings.canDrawOverlays(this)
        if (!visible) { hideOverlay(); return }
        if (overlay != null) return
        // A Service's default resources can follow the virtual display's config.
        // Always build the overlay using the physical display's density and dimensions.
        val physical = getSystemService(DisplayManager::class.java).getDisplay(0)
        val ui = ContextThemeWrapper(createDisplayContext(physical), R.style.Theme_Wukong)
        val metrics = ui.resources.displayMetrics
        val manager = ui.getSystemService(WindowManager::class.java)
        val panel = VirtualScreenPanel(ui, compact = true)
        val preferences = getSharedPreferences("floating_preview", MODE_PRIVATE)
        val density=metrics.density
        fun px(value: Float)=(value*density).toInt()
        val bounds=manager.currentWindowMetrics
        val insets=bounds.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val availableWidth=(bounds.bounds.width()-insets.left-insets.right).coerceAtLeast(1)
        val availableHeight=(bounds.bounds.height()-insets.top-insets.bottom).coerceAtLeast(1)
        val minWidth=minOf(px(208f),availableWidth)
        val minPreview=minOf(px(120f),(availableHeight-px(172f)).coerceAtLeast(1))
        val maxPreview=(availableHeight-px(172f)).coerceAtLeast(minPreview)
        val previewHeight=px(preferences.getFloat("height",300f)).coerceIn(minPreview,maxPreview)
        panel.setPreviewHeight(previewHeight)
        val width=px(preferences.getFloat("width",208f)).coerceIn(minWidth,availableWidth)
        val params = WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            @Suppress("RtlHardcoded")
            gravity = Gravity.TOP or Gravity.LEFT
            x=px(preferences.getFloat("x",(availableWidth-width)/density)).coerceIn(0,availableWidth-width)
            y=px(preferences.getFloat("y",70f)).coerceIn(0,(availableHeight-previewHeight-px(172f)).coerceAtLeast(0))
        }
        fun save() {
            preferences.edit().putFloat("width",params.width/density).putFloat("height",panel.previewHeight/density)
                .putFloat("x",params.x/density).putFloat("y",params.y/density).apply()
        }
        fun constrain(height: Int) {
            params.x=params.x.coerceIn(0,(availableWidth-params.width).coerceAtLeast(0))
            params.y=params.y.coerceIn(0,(availableHeight-height).coerceAtLeast(0))
        }
        // Preserve fractional deltas across real MotionEvent streams; rounding every move loses distance.
        var dragX=params.x.toFloat();var dragY=params.y.toFloat()
        var resizeWidth=params.width.toFloat();var resizeHeight=panel.previewHeight.toFloat()
        var chromeHeight=px(172f)
        panel.onDrag = { dx,dy ->
            dragX=(dragX+dx).coerceIn(0f,(availableWidth-params.width).coerceAtLeast(0).toFloat())
            dragY=(dragY+dy).coerceIn(0f,(availableHeight-panel.height).coerceAtLeast(0).toFloat())
            params.x=dragX.roundToInt();params.y=dragY.roundToInt()
            runCatching { manager.updateViewLayout(panel,params) }
        }
        panel.onResize = { dx,dy ->
            resizeWidth=(resizeWidth+dx).coerceIn(minWidth.toFloat(),availableWidth.toFloat())
            resizeHeight=(resizeHeight+dy).coerceIn(minPreview.toFloat(),maxPreview.toFloat())
            params.width=resizeWidth.roundToInt()
            val height=resizeHeight.roundToInt()
            panel.setPreviewHeight(height);constrain(height+chromeHeight)
            dragX=params.x.toFloat();dragY=params.y.toFloat()
            runCatching { manager.updateViewLayout(panel,params) }
        }
        panel.onGeometrySettled = {
            dragX=params.x.toFloat();dragY=params.y.toFloat()
            resizeWidth=params.width.toFloat();resizeHeight=panel.previewHeight.toFloat()
            save()
        }
        panel.addOnLayoutChangeListener { _,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom ->
            if(bottom-top!=oldBottom-oldTop||right-left!=oldRight-oldLeft) {
                chromeHeight=panel.height-panel.previewHeight
                val oldX=params.x;val oldY=params.y
                constrain(panel.height)
                dragX=params.x.toFloat();dragY=params.y.toFloat()
                if(oldX!=params.x||oldY!=params.y)runCatching { manager.updateViewLayout(panel,params) }
            }
        }
        try { manager.addView(panel, params); overlay = panel; overlayManager = manager }
        catch (error: Exception) { VirtualScreenState.message = error.message ?: getString(R.string.ui_could_not_show_floating_preview) }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        hideOverlay();updateOverlay()
    }
    internal fun closeFloatingPreview() { VirtualScreenState.dismissFloatingPreview(); hideOverlay() }
    private fun hideOverlay() { overlay?.let { runCatching { overlayManager?.removeView(it) } }; overlay = null; overlayManager = null }
    override fun onDestroy() {
        closed = true; polling?.cancel(false); hideOverlay()
        if (TaskRuntime.displayId > 0) TaskRuntime.stop()
        io.execute { runCatching { ShellClient(this).request("virtualStop",JSONObject().put("ownerId",ownerId)) } }
        io.shutdown()
        VirtualScreenState.frameError=null; VirtualScreenState.service = null; VirtualScreenState.displayId = 0; VirtualScreenState.bitmap = null
        VirtualScreenState.message = getString(R.string.ui_virtual_display_closed); VirtualScreenState.changed()
        super.onDestroy()
    }
}
