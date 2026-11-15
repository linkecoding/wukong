package com.wukong.phone

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.wukong.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PendingApproval(val action: Action, val screen: Screen, val answer: CompletableFuture<Boolean> = CompletableFuture())
data class TaskRecord(val goal: String, val app: String, val phase: String, val result: String, val timestamp: Long, val logName: String? = null, val logAssociationKnown: Boolean = false)

/** Single task owner, independent of Activity lifecycle. No externally callable transport in v0.1. */
object TaskRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "wukong-agent") }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val busy = AtomicBoolean(false)
    private var cancellation: Cancellation? = null
    private var client: ChatModel? = null
    private var control = ExecutionControl()
    @Volatile var conversationId: String? = null; private set
    @Volatile var conversationTurnId: String? = null; private set
    @Volatile var displayId = 0; private set
    @Volatile private var activeTrace: RunLog? = null
    @Volatile private var activeDriver: AndroidDriver? = null
    @Volatile private var scriptSession: com.wukong.phone.script.JsSession? = null
    @Volatile private var shellSession: ShellSession? = null
    private val canceller = Executors.newSingleThreadExecutor { Thread(it, "wukong-task-cancel") }
    @Volatile private var worker: Thread? = null
    @Volatile var event = AgentEvent(Phase.IDLE, "准备好后，把任务交给悟空")
        private set
    @Volatile var pending: PendingApproval? = null
        private set
    @Volatile var goal = ""
        private set
    @Volatile var appLabel = ""
        private set
    @Volatile var stopRequested = false
        private set
    val active get() = busy.get()
    private val timeline = mutableListOf<AgentEvent>()
    fun events(): List<AgentEvent> = synchronized(timeline) { timeline.toList() }
    fun listen(listener: () -> Unit) { listeners += listener }
    fun unlisten(listener: () -> Unit) { listeners -= listener }
    private fun notifyChanged() { main.post { listeners.forEach { it() } } }
    fun connectionChanged() { notifyChanged() }
    private fun publish(value: AgentEvent) {
        event = value
        synchronized(timeline) { timeline += value; if (timeline.size > 100) timeline.removeAt(0) }
        notifyChanged()
    }

    /** Invoked on main by the explicit Start button. */
    fun start(context: Context, taskGoal: String, initialPackage: String?, settings: ModelSettings, threadId: String? = null) {
        require(taskGoal.isNotBlank() && taskGoal.length <= 4000) { "请输入 1–4000 字的任务" }
        settings.validate()
        check(!WirelessActivation.busy) { "请等待执行引擎激活完成" }
        require(Settings.canDrawOverlays(context)) { "请先允许悟空显示悬浮控制条" }
        if (initialPackage != null) require(context.packageManager.getLaunchIntentForPackage(initialPackage) != null) { "无法打开这个应用" }
        val targetDisplay = if (VirtualScreenState.enabled(context)) VirtualScreenState.displayId.also { require(it > 0) { "请先启动虚拟屏，等待画面就绪" } } else 0
        check(busy.compareAndSet(false, true)) { "请先停止当前任务" }
        val appContext = context.applicationContext
        val token = Cancellation()
        @Suppress("DEPRECATION")
        val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.distinctBy { it.first }
        val trace = RunLogStore(appContext).start("task", taskGoal, settings)
        activeTrace = trace
        control = ExecutionControl()
        displayId = targetDisplay
        trace?.record("display_target", JSONObject().put("displayId", displayId))
        val conversations = ConversationStore(appContext)
        val priorContext = threadId?.let { conversations.context(it) }
        val turnId = try { threadId?.let { conversations.begin(it, taskGoal.trim(), trace?.name) } } catch (error: Exception) { busy.set(false); throw error }
        conversationId = threadId; conversationTurnId = turnId
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val taskEvidence=TaskEvidence()
        val model = ChatModel(settings, apps, trace, conversationContext = priorContext,taskEvidence=taskEvidence)
        goal = taskGoal.trim(); appLabel = "整机任务"
        cancellation = token; client = model; stopRequested = false
        synchronized(timeline) { timeline.clear() }
        val controlsIntent = Intent(context, WukongService::class.java)
        try {
            context.startForegroundService(controlsIntent)
            publish(AgentEvent(Phase.RUNNING, "正在准备整机任务"))
            executor.submit {
                worker = Thread.currentThread()
                var result = AgentEvent(Phase.FAILED, "任务启动失败")
                try {
                    token.check()
                    control.awaitAgent(token)
                    if (displayId > 0) ShellClient(appContext).request("setControl", JSONObject().put("manual", false))
                    val session = ShellSession(appContext, displayId)
                    shellSession = session
                    token.check()
                    for (attempt in 0 until 40) { token.check(); if (WukongService.instance != null) break; Thread.sleep(50) }
                    val controls = WukongService.instance ?: error("无法启动任务控制条")
                    controls.onMain { controls.showControls() }
                    val driver = AndroidDriver(session, token, trace, control,taskEvidence)
                    activeDriver = driver
                    fun observeStartup(): Screen = try { driver.observe() } catch (error: IllegalStateException) {
                        // An empty virtual display has no snapshot to authorize normal Home execution.
                        // Recovery can only recreate our launcher when the shell finds no app window.
                        if (displayId <= 0 || control.epoch != 0L) throw error
                        val recovery = session.request("recoverVirtualContent") as JSONObject
                        if (!recovery.getBoolean("recovered")) throw error
                        trace?.record("startup_recovery",JSONObject().put("displayId",displayId).put("reason","no_application_window"))
                        token.check()
                        driver.observe()
                    }
                    // Follow-ups keep the current display; earlier summaries never replay actions.
                    val followUp = priorContext != null && JSONArray(priorContext).length() > 0
                    if (control.epoch == 0L && (!followUp || initialPackage != null)) {
                        try {
                            for(attempt in 0 until 6) {
                                token.check();control.awaitAgent(token)
                                val startScreen=observeStartup()
                                if(control.epoch!=0L)break
                                try { driver.execute(initialPackage?.let { Action.Launch(it) } ?: Action.Home,startScreen);break }
                                catch(error:IllegalStateException) {
                                    if(error.message?.startsWith("界面已更新，本次操作未执行")!=true||attempt==5)throw error
                                    trace?.record("startup_navigation_rejected",JSONObject().put("attempt",attempt+1).put("reason","snapshot_changed_before_injection"))
                                }
                            }
                        } catch (error: IllegalStateException) { if (control.epoch == 0L) throw error }
                    }
                    var ready = false
                    for (attempt in 0 until 20) {
                        token.check()
                        control.awaitAgent(token)
                        Thread.sleep(250)
                        if (runCatching { val pkg = observeStartup().packageName; pkg.isNotBlank() && (initialPackage == null || control.epoch > 0 || pkg == initialPackage) }.getOrDefault(false)) { ready = true; break }
                    }
                    require(ready) { "未能读取界面，请确认手机已解锁" }
                    val scripts = com.wukong.phone.script.JsSession(appContext, token, control, trace)
                    scriptSession = scripts
                    result = Agent(model, driver, Approval { action, screen ->
                        trace?.record("approval_requested", JSONObject().put("action", action.toJson()).put("package", screen.packageName))
                        val request = PendingApproval(action, screen)
                        pending = request
                        notifyChanged()
                        try { request.answer.get(120, TimeUnit.SECONDS).also { trace?.record("approval_result", JSONObject().put("allowed", it)) } }
                        catch (_: java.util.concurrent.TimeoutException) { false }
                        finally { pending = null; notifyChanged() }
                    }, maxSteps=50,control = control, completionVerifier = CompletionVerifier(model::verifyCompletion), scriptRunner = scripts).run(goal, token) { value ->
                        trace?.record("agent_event", JSONObject().put("step", value.step).put("phase", value.phase.name).put("message", value.message))
                        publish(value)
                    }
                } catch (_: InterruptedException) {
                    result = AgentEvent(Phase.CANCELLED, "任务已停止").also(::publish)
                } catch (e: Exception) {
                    result = AgentEvent(Phase.FAILED, e.message ?: "执行失败").also(::publish)
                } finally {
                    Thread.interrupted()
                    trace?.record("end", JSONObject().put("phase", result.phase.name).put("message", result.message))
                    runCatching { scriptSession?.close() }; scriptSession = null
                    runCatching { shellSession?.close() }
                    shellSession = null
                    displayId = 0; activeTrace = null; activeDriver = null
                    val record = TaskRecord(goal, "整机任务", result.phase.name, result.message, System.currentTimeMillis(), trace?.name, true)
                    runCatching { HistoryStore(appContext).append(record) }
                    if (threadId != null && turnId != null) runCatching { conversations.finish(threadId, turnId, result.phase.name, result.message, android.os.SystemClock.elapsedRealtime()-startedAt) }
                    conversationId = null; conversationTurnId = null
                    pending = null; client = null; cancellation = null; worker = null
                    busy.set(false)
                    notifyChanged()
                }
            }
        } catch (e: Exception) {
            if (threadId != null && turnId != null) runCatching { conversations.finish(threadId, turnId, "FAILED", e.message.orEmpty(), android.os.SystemClock.elapsedRealtime()-startedAt) }
            conversationId = null; conversationTurnId = null
            busy.set(false)
            context.stopService(controlsIntent)
            throw e
        }
    }

    fun answer(request: PendingApproval, allowed: Boolean) {
        if (pending === request && !stopRequested) request.answer.complete(allowed)
    }

    fun pauseForHuman(value: Boolean) {
        if (!active || displayId == 0) return
        control.pause(value)
        activeDriver?.invalidateVisual()
        scriptSession?.cancelCell()
        pending?.answer?.complete(false)
        activeTrace?.record("control_changed", JSONObject().put("manual", value))
        publish(AgentEvent(if (value) Phase.PAUSED else Phase.RUNNING, if (value) "你已接管，Agent 已暂停" else "已交还 Agent，正在重新读取界面"))
    }

    fun stop() {
        if (!active || stopRequested) return
        stopRequested = true
        cancellation?.cancel()
        val session = shellSession
        if (session != null) canceller.execute { runCatching { session.cancel() } }
        pending?.answer?.complete(false)
        // Do not cancel a queued Future before its finally runs: wait for the worker to clean up.
        worker?.interrupt()
        client?.cancel()
        scriptSession?.cancelCell()
        notifyChanged()
    }
}

class HistoryStore(context: Context, namespace: String = "") {
    init { require(namespace.matches(Regex("(-[a-z]+)?"))) }
    private val prefs = context.getSharedPreferences("history$namespace", Context.MODE_PRIVATE)
    fun read(): List<TaskRecord> = runCatching {
        val array = JSONArray(prefs.getString("records", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            TaskRecord(it.getString("goal"), it.getString("app"), it.getString("phase"), it.getString("result"), it.getLong("timestamp"), if (it.has("logName") && !it.isNull("logName")) it.getString("logName") else null, it.optBoolean("logAssociationKnown", false))
        } }
    }.getOrDefault(emptyList())
    fun append(record: TaskRecord) {
        val array = JSONArray((listOf(record) + read()).take(30).map {
            JSONObject().put("goal", it.goal).put("app", it.app).put("phase", it.phase).put("result", it.result).put("timestamp", it.timestamp).put("logName", it.logName ?: JSONObject.NULL).put("logAssociationKnown", it.logAssociationKnown)
        })
        check(prefs.edit().putString("records", array.toString()).commit())
    }
    fun clear() { prefs.edit().clear().apply() }
}
