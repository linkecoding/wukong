package com.wukong.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Activation survives switching to system Settings; pairing codes are never persisted or logged. */
object WirelessActivation {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val running = AtomicBoolean(false)
    @Volatile var pairingPort: Int? = null; private set
    @Volatile var connectPort: Int? = null; private set
    @Volatile var message = "先开启无线调试，再配对悟空"; private set
    @Volatile var paired = false; private set
    @Volatile var activated = false; private set
    val busy get() = running.get()
    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() { main.post { listeners.forEach { it() } } }
    fun discovered(pairing: Boolean, port: Int) {
        if (pairing) pairingPort = port else connectPort = port
        if (!busy && !activated) message = if (pairingPort != null) "已找到本机配对端口，可从通知栏输入配对码" else "已找到本机无线调试连接"
        notifyChanged()
    }

    fun pair(context: Context, code: String, manualPort: Int? = null) {
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位数字配对码" }
        val port = manualPort ?: pairingPort ?: error("还未发现配对窗口，请在系统无线调试中打开“使用配对码配对设备”")
        submit(context) { manager, app ->
            update("正在与这台手机配对…")
            manager.pairLocal(port, code)
            paired = true
            app.getSharedPreferences("wireless", 0).edit().putBoolean("paired", true).apply()
            update("配对成功，正在查找连接端口…")
            for (attempt in 0 until 30) { if (connectPort != null) break; Thread.sleep(200) }
            val connection = connectPort
            if (connection == null) update("配对已完成；请回到悟空填写无线调试主页的连接端口")
            else activateConnected(manager, app, connection)
        }
    }

    fun activate(context: Context, manualPort: Int? = null) {
        val port = manualPort ?: connectPort ?: error("未发现连接端口，请填写无线调试主页的端口")
        submit(context) { manager, app -> activateConnected(manager, app, port) }
    }

    private fun activateConnected(manager: LocalAdb, app: Context, port: Int) {
        update("正在连接本机无线调试…")
        manager.connectLocal(port)
        manager.activate(app, ::update)
        activated = true
        update("激活成功 · 可以开始手机任务")
        TaskRuntime.connectionChanged()
    }
    private fun submit(context: Context, action: (LocalAdb, Context) -> Unit) {
        require(android.os.Build.VERSION.SDK_INT >= 30) { "免电脑激活需要 Android 11 或以上" }
        check(!TaskRuntime.active) { "请先停止当前任务" }
        check(running.compareAndSet(false, true)) { "正在处理上一次激活请求" }
        activated = false
        val app = context.applicationContext
        notifyChanged()
        worker.submit {
            var manager: LocalAdb? = null
            try {
                manager = LocalAdb(AdbIdentity.load(app))
                action(manager, app)
            } catch (error: Exception) {
                if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.w("WukongActivation", "Local activation failed", error)
                update("未能完成配对或激活。请确认无线调试开启、端口正确；配对码过期时重新打开系统配对窗口。")
            } finally { runCatching { manager?.disconnect() }; running.set(false); notifyChanged() }
        }
    }
    private fun update(value: String) { message = value; notifyChanged() }
    fun inputError(value: String) { if (!busy) update(value) }
}
