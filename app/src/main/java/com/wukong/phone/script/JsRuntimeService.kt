package com.wukong.phone.script

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Runs with an isolated Android UID. No shell token, model key, Context or Java interop reaches JS. */
class JsRuntimeService : Service() {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "wukong-quickjs") }
    private val generation = AtomicLong()
    @Volatile private var runtime: QuickJsEngine? = null
    private val lifecycleLock = Any()
    class Bridge(private val host: IJsBridge) {
        fun call(method: String, arguments: String): String {
            require(method.length <= 40 && arguments.length <= 65536) { "JS bridge request too large" }
            return host.call(method, arguments)
        }
    }
    private val binder = object : IJsRuntime.Stub() {
        override fun execute(code: String, timeoutMs: Long, bridge: IJsBridge, result: IJsResult) {
            val expectedGeneration = generation.get()
            executor.execute {
                val reply = try {
                    require(code.length in 1..60000 && timeoutMs in 200..180000)
                    check(generation.get() == expectedGeneration) { "脚本已取消" }
                    val engine = synchronized(lifecycleLock) { QuickJsEngine().also { runtime = it } }
                    try {
                        check(generation.get() == expectedGeneration) { "脚本已取消" }
                        val output = engine.evaluate(code, timeoutMs, Bridge(bridge))
                        require(output.length <= 96000) { "脚本结果或状态过大" }
                        JSONObject().put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid()).put("ok", true).put("result", output).toString()
                    } finally { synchronized(lifecycleLock) { runtime = null; engine.close() } }
                } catch (error: Exception) {
                    JSONObject().put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid()).put("ok", false).put("error", error.message.orEmpty().take(2000)).toString()
                }
                runCatching { result.completed(reply) }
            }
        }
        override fun cancel() {
            generation.incrementAndGet()
            synchronized(lifecycleLock) { runCatching { runtime?.interrupt() } }
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        binder.cancel()
        executor.execute { synchronized(lifecycleLock) { runCatching { runtime?.close() }; runtime = null } }
        executor.shutdown()
        super.onDestroy()
    }
}
