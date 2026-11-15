package com.wukong.phone.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.wukong.core.Cancellation
import com.wukong.core.ExecutionControl
import com.wukong.core.ScriptHost
import com.wukong.core.ScriptRunner
import com.wukong.phone.RunLog
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Binder callbacks only enqueue. All phone APIs and approvals execute on the existing task worker. */
class JsSession(context: Context, private val cancellation: Cancellation, private val control: ExecutionControl, private val trace: RunLog? = null,
    private val timeoutMs: Long = 60000) : ScriptRunner, AutoCloseable {
    override val timeoutMillis get() = timeoutMs
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val connected = CompletableFuture<IJsRuntime>()
    @Volatile private var remote: IJsRuntime? = null
    @Volatile private var closed = false
    @Volatile private var died = false
    internal var enginePid = 0; private set
    internal var engineUid = 0; private set
    private var binding = false
    @Volatile private var state = "{}"
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = IJsRuntime.Stub.asInterface(binder); connected.complete(remote) }
        override fun onServiceDisconnected(name: ComponentName) { died = true; remote = null; connected.completeExceptionally(IllegalStateException("JS 隔离进程已退出，旧脚本不会重放")) }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("无法绑定 JS 执行器")) }
    }
    private data class Call(val method: String, val arguments: String, val answer: CompletableFuture<String> = CompletableFuture())
    override fun run(code: String, host: ScriptHost): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "JS 必须在工作线程调度" }
        cancellation.check(); check(!closed && !died) { "JS 会话不可用，请重新开始任务" }
        val epoch = control.epoch
        val started = SystemClock.elapsedRealtime()
        fun guard() {
            cancellation.check()
            check(!closed && !died && !control.paused && control.epoch == epoch) { "用户接管或 JS 进程已退出，旧脚本已停止" }
            check(SystemClock.elapsedRealtime() - started < timeoutMs + 3000) { "JS 短流程已超时；先前操作不会自动重放" }
        }
        if (!binding) {
            binding = true
            main.post {
                if (!closed && !context.bindService(Intent(context, JsRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)) connected.completeExceptionally(IllegalStateException("无法启动 JS 隔离进程"))
            }
        }
        while (!connected.isDone) { guard(); Thread.sleep(25) }
        val engine = connected.get()
        val calls = LinkedBlockingQueue<Call>(1)
        val done = CompletableFuture<String>()
        val phone = JsPhone(host, ::guard, trace)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val gate = Any()
        val bridge = object : IJsBridge.Stub() {
            override fun call(method: String, arguments: String): String {
                val request = Call(method, arguments)
                if (!synchronized(gate) { active.get() && arguments.length <= 65536 && calls.offer(request) }) return "{\"ok\":false,\"error\":\"JS bridge unavailable\"}"
                return try { request.answer.get(180, TimeUnit.SECONDS) } catch (_: Exception) { "{\"ok\":false,\"error\":\"JS call interrupted\"}" }
            }
        }
        trace?.record("script_started", JSONObject().put("engine", "QuickJS 2026-06-04").put("isolated", true).put("codeLength", code.length))
        var fatal: Exception? = null
        try {
            engine.execute(JsSdk.wrap(code, state), timeoutMs, bridge, object : IJsResult.Stub() { override fun completed(result: String) { done.complete(result) } })
            while (!done.isDone) {
                guard()
                val call = calls.poll(40, TimeUnit.MILLISECONDS) ?: continue
                val reply = try { JSONObject().put("ok", true).put("result", phone.call(call.method, JSONObject(call.arguments))) }
                    catch (error: Exception) { fatal = error; JSONObject().put("ok", false).put("error", error.message.orEmpty().take(1000)) }
                call.answer.complete(reply.toString())
                if (fatal != null) throw fatal
            }
            guard()
            val envelope = JSONObject(done.get())
            enginePid = envelope.getInt("pid"); engineUid = envelope.getInt("uid")
            check(envelope.getBoolean("ok")) { envelope.optString("error", "脚本失败") }
            val output = JSONObject(envelope.getString("result"))
            val nextState = output.getJSONObject("state").toString()
            require(nextState.length <= 32768) { "脚本 state 超过 32K" }
            val value = output.opt("value")?.toString().orEmpty()
            require(value.length <= 64000) { "脚本返回值超过 64K，请只返回需要的结果" }
            state = nextState
            trace?.record("script_completed", JSONObject().put("durationMs", SystemClock.elapsedRealtime() - started).put("calls", phone.calls))
            return value
        } catch (error: Exception) {
            state = "{}"
            trace?.record("script_error", JSONObject().put("message", error.message.orEmpty().take(2000)).put("durationMs", SystemClock.elapsedRealtime() - started).put("calls", phone.calls))
            throw error
        } finally {
            synchronized(gate) {
                active.set(false)
                while (true) { val pending = calls.poll() ?: break; pending.answer.complete("{\"ok\":false,\"error\":\"Cell ended\"}") }
            }
            if (!done.isDone) runCatching { engine.cancel() }
        }
    }
    fun cancelCell() { state = "{}"; runCatching { remote?.cancel() } }
    override fun close() {
        closed = true; cancelCell()
        if (binding) main.post { runCatching { context.unbindService(connection) } }
    }
}
