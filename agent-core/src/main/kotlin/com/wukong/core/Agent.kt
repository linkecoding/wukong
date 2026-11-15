package com.wukong.core

import java.util.concurrent.atomic.AtomicBoolean

/** Platform independent contract shared by the app and a future MCP/skill bridge. */
data class UiNode(
    val id: Int, val text: String, val editable: Boolean = false,
    val clickable: Boolean = false, val scrollable: Boolean = false,
    val sensitive: Boolean = false,
    val bounds: Bounds? = null,
    val resourceId: String = "", val className: String = "", val parentId: Int? = null,
    val enabled: Boolean = true, val checked: Boolean? = null, val selected: Boolean = false,
    val rawText: String? = null,
)

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
}
data class OcrLine(val text: String, val bounds: Bounds)
data class VisualFrame(val id: String, val width: Int, val height: Int, val lines: List<OcrLine>, val jpegBase64: String? = null)
data class Observation(val settled: Boolean, val samples: Int, val elapsedMillis: Long, val uiEvents: Boolean = false)
data class Screen(val packageName: String, val fingerprint: String, val nodes: List<UiNode>, val visual: VisualFrame? = null, val observation: Observation? = null, val uiRevision: Long = 0, val uiQuietMillis: Long = Long.MAX_VALUE, val uiEvents: Boolean = false, val truncated: Boolean = false)

sealed interface Action {
    data class Tap(val nodeId: Int) : Action
    data class Input(val nodeId: Int, val text: String) : Action
    data class Scroll(val nodeId: Int, val forward: Boolean) : Action
    data class Launch(val packageName: String) : Action
    data object Home : Action
    data object Back : Action
    data class Wait(val milliseconds: Long) : Action
    data class Finish(val summary: String, val outcome: TaskOutcome = TaskOutcome.SUCCESS, val evidence: List<ScreenCondition> = emptyList()) : Action
    data class Script(val code: String) : Action
    data class Query(val selector: NodeSelector = NodeSelector(), val limit: Int = 20, val offset: Int = 0) : Action
    data class WaitUntil(val conditions: List<ScreenCondition>, val matchAll: Boolean = false, val timeoutMillis: Long = 5000) : Action
    data class Verify(val conditions: List<ScreenCondition>, val matchAll: Boolean = true) : Action
    data object Screenshot : Action
    data object Ocr : Action
    data class TapAt(val x: Int, val y: Int, val captureId: String) : Action
    data class Swipe(val x: Int, val y: Int, val endX: Int, val endY: Int, val duration: Long, val captureId: String) : Action
    data class LongPress(val x: Int, val y: Int, val duration: Long, val captureId: String) : Action
}

fun Action.label(): String = when (this) {
    is Action.Tap -> "点击节点 $nodeId"
    is Action.Input -> "向节点 $nodeId 输入文本"
    is Action.Scroll -> if (forward) "向下滚动节点 $nodeId" else "向上滚动节点 $nodeId"
    is Action.Launch -> "打开应用 $packageName"
    Action.Home -> "返回桌面"
    Action.Back -> "返回上一页"
    is Action.Wait -> "等待界面更新"
    is Action.Finish -> "验证并结束任务"
    is Action.Script -> "执行手机 JS 短流程"
    is Action.Query -> "查询页面控件"
    is Action.WaitUntil -> "等待目标条件"
    is Action.Verify -> "核对页面结果"
    Action.Screenshot -> "截取当前画面"
    Action.Ocr -> "识别画面文字"
    is Action.TapAt -> "点击坐标 ($x, $y)"
    is Action.Swipe -> "从 ($x, $y) 滑动到 ($endX, $endY)"
    is Action.LongPress -> "长按坐标 ($x, $y)"
}

data class Turn(val screen: Screen, val action: Action, val result: String)
fun interface Model { fun decide(goal: String, screen: Screen, history: List<Turn>): Action }
interface DeviceDriver {
    fun observe(): Screen
    fun observeNow(): Screen = observe()
    /** Must independently verify app scope and snapshot immediately before acting. */
    fun execute(action: Action, expected: Screen): String
    fun verify(conditions: List<ScreenCondition>, screen: Screen): ConditionReport = Conditions.evaluate(screen, conditions, true)
}
fun interface Approval { fun request(action: Action, screen: Screen): Boolean }

enum class Phase { IDLE, RUNNING, PAUSED, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED }
data class AgentEvent(val phase: Phase, val message: String, val step: Int = 0)

class Cancellation {
    private val stopped = AtomicBoolean(false)
    fun cancel() { stopped.set(true) }
    fun check() { if (stopped.get() || Thread.currentThread().isInterrupted) throw InterruptedException("任务已停止") }
}

/** Ordinary actions run automatically; consequential actions need local approval. */
class Agent(
    private val model: Model, private val driver: DeviceDriver, private val approval: Approval,
    private val allowedPackage: String? = null, private val maxSteps: Int = 30,
    private val approvalRequired: (Action, Screen) -> Boolean = SensitiveActions::requiresApproval,
    private val control: ExecutionControl = ExecutionControl(),
    private val completionVerifier: CompletionVerifier? = null,
    private val scriptRunner: ScriptRunner? = null,
) {
    init { require(allowedPackage == null || allowedPackage.isNotBlank()); require(maxSteps in 1..100) }

    fun run(goal: String, cancellation: Cancellation, emit: (AgentEvent) -> Unit): AgentEvent {
        var step = 0
        fun event(phase: Phase, message: String) = AgentEvent(phase, message, step).also(emit)
        return try {
            require(goal.isNotBlank() && goal.length <= 4000) { "任务描述须为 1–4000 字" }
            val history = mutableListOf<Turn>()
            fun remember(turn: Turn) { history += turn; while (history.size > 8) history.removeAt(0) }
            var staleRetries = 0
            var validationRetries = 0
            var completionRetries = 0
            var scriptReads = 0
            var scriptMutations = 0
            event(Phase.RUNNING, "正在读取目标应用")
            repeat(maxSteps) {
                step = it + 1
                cancellation.check()
                control.awaitAgent(cancellation)
                val epoch = control.epoch
                val screen = try { driver.observe() } catch (error: IllegalStateException) {
                    if (control.epoch != epoch) return@repeat
                    throw error
                }
                require(allowedPackage == null || screen.packageName == allowedPackage) { "已离开授权应用，请接管后重新开始" }
                require(screen.nodes.none { node -> node.sensitive }) { "检测到受保护输入，请手动处理" }
                val action = model.decide(goal, screen, history.takeLast(8))
                cancellation.check()
                if (control.epoch != epoch) return@repeat
                try {
                    validate(action, screen)
                } catch (error: RuntimeException) {
                    if (error !is IllegalArgumentException && error !is IllegalStateException) throw error
                    // Validation is entirely local and runs before approval or device injection.
                    // Return a failed tool result, not a replay of a potentially accepted action.
                    if (validationRetries++ >= 3) throw error
                    remember(Turn(screen, action, "操作未执行（校验拒绝）：${error.message}。请重新观察，选择可操作节点；必要时先用 OCR 或截图，再使用坐标工具。不要重复无效操作。"))
                    event(Phase.RUNNING, "操作未执行，正在重新观察并调整")
                    return@repeat
                }
                validationRetries = 0
                if (action is Action.Script) {
                    val runner = scriptRunner ?: error("JS 执行器尚未配置")
                    val scriptStarted = System.nanoTime()
                    require(runner.timeoutMillis in 200..180000)
                    var aborted = false
                    fun guard() {
                        cancellation.check()
                        check(control.epoch == epoch && !control.paused) { "用户正在接管，旧脚本已停止" }
                        check(!aborted) { "本段脚本已中止，不能继续执行或重放" }
                        check((System.nanoTime() - scriptStarted) / 1_000_000 < runner.timeoutMillis) { "脚本已超时，不能继续设备操作" }
                    }
                    val host = object : ScriptHost {
                        override fun observe(): Screen {
                            guard(); check(++scriptReads <= 1000) { "脚本读取次数已达任务上限" }
                            val current = driver.observeNow()
                            guard()
                            require(allowedPackage == null || current.packageName == allowedPackage) { "已离开授权应用，请接管后重新开始" }
                            require(current.nodes.none { node -> node.sensitive }) { "检测到受保护输入，请手动处理" }
                            return current
                        }
                        override fun execute(action: Action, expected: Screen): String {
                            guard()
                            try {
                                require(action !is Action.Script && action !is Action.Finish) { "脚本不能嵌套执行或直接宣告任务完成" }
                                validate(action, expected)
                                require(allowedPackage == null || expected.packageName == allowedPackage) { "已离开授权应用，请接管后重新开始" }
                                require(expected.nodes.none { node -> node.sensitive }) { "检测到受保护输入，请手动处理" }
                                if (action !is Action.Query && action !is Action.WaitUntil && action !is Action.Verify && action !is Action.Wait && action !is Action.Screenshot && action !is Action.Ocr) {
                                    check(++scriptMutations <= 100) { "脚本设备操作次数已达任务上限" }
                                }
                                if (approvalRequired(action, expected)) {
                                    event(Phase.AWAITING_APPROVAL, action.label())
                                    if (!approval.request(action, expected)) {
                                        guard(); throw ScriptApprovalRejected()
                                    }
                                    guard()
                                    val fresh = observe()
                                    check(fresh.packageName == expected.packageName && fresh.fingerprint == expected.fingerprint) { "确认期间页面变化，旧确认已失效；脚本停止，请重新检查" }
                                }
                                guard()
                                event(Phase.RUNNING, action.label())
                                return driver.execute(action, expected).also { guard(); remember(Turn(expected, action, it)) }
                            } catch (error: Exception) { aborted = true; throw error }
                        }
                    }
                    event(Phase.RUNNING, action.label())
                    val result = try { runner.run(action.code, host) }
                    catch (error: InterruptedException) { throw error }
                    catch (_: ScriptApprovalRejected) { return event(Phase.CANCELLED, "已拒绝操作，任务停止") }
                    catch (error: Exception) {
                        cancellation.check()
                        if (control.epoch != epoch) return@repeat
                        "脚本已停止：${error.message?.take(1000)}。先前已接受的操作不会撤销；请观察当前结果，不要整段重放，尤其不能重复支付、发送或删除。"
                    }
                    cancellation.check()
                    if (control.epoch != epoch) return@repeat
                    remember(Turn(screen, action, result))
                    return@repeat
                }
                if (action is Action.Finish) {
                    try {
                        require(action.summary.isNotBlank() && action.summary.length <= 2000) { "模型返回的结果无效" }
                        if (action.outcome == TaskOutcome.INCOMPLETE) return event(Phase.FAILED, action.summary)
                        event(Phase.RUNNING, "正在核对任务完成证据")
                        var fresh = driver.observe()
                        cancellation.check()
                        if (control.epoch != epoch) return@repeat
                        require(allowedPackage == null || fresh.packageName == allowedPackage) { "已离开授权应用，请接管后重新开始" }
                        require(fresh.nodes.none { node -> node.sensitive }) { "检测到受保护输入，请手动处理" }
                        var verdict = if (action.evidence.isNotEmpty()) {
                            val report = driver.verify(action.evidence, fresh)
                            CompletionCheck(report.state == CheckState.MET, "当前页面条件：${report.state}；${report.checks.map { it.state }}")
                        } else if (completionVerifier != null) {
                            // Visual/semantic completion needs fresh evidence, not a cached old image.
                            driver.execute(Action.Screenshot, fresh)
                            fresh = driver.observe()
                            cancellation.check()
                            if (control.epoch != epoch) return@repeat
                            require(allowedPackage == null || fresh.packageName == allowedPackage) { "已离开授权应用，请接管后重新开始" }
                            require(fresh.nodes.none { node -> node.sensitive }) { "检测到受保护输入，请手动处理" }
                            completionVerifier.verify(goal, action.summary, fresh, history.takeLast(8))
                        } else CompletionCheck(false, "缺少完成证据，请提供与任务目标对应的页面条件")
                        if (action.evidence.isEmpty() && completionVerifier != null) {
                            val afterReview = driver.observe()
                            if (afterReview.packageName != fresh.packageName || afterReview.fingerprint != fresh.fingerprint) {
                                verdict = CompletionCheck(false, "复核期间页面发生变化，请核对最新结果")
                            }
                            fresh = afterReview
                            require(allowedPackage == null || fresh.packageName == allowedPackage) { "已离开授权应用，请接管后重新开始" }
                            require(fresh.nodes.none { node -> node.sensitive }) { "检测到受保护输入，请手动处理" }
                        }
                        cancellation.check()
                        if (control.epoch != epoch) return@repeat
                        if (verdict.verified) return event(Phase.COMPLETED, action.summary)
                        remember(Turn(fresh, action, "完成验证未通过：${verdict.detail}。没有因此重放任何操作。请检查当前结果，继续完成任务；无法确认时用 incomplete 结束，不能再次盲目发送或支付。"))
                        if (++completionRetries >= 3) return event(Phase.FAILED, "无法确认任务完成：${verdict.detail}")
                        event(Phase.RUNNING, "尚未确认完成，正在检查结果")
                        return@repeat
                    } catch (error: IllegalStateException) {
                        if (control.epoch != epoch) return@repeat
                        if ((error.message?.startsWith("界面已更新，本次操作未执行") == true || error.message?.startsWith("截图已过期") == true) && staleRetries++ < 6) {
                            remember(Turn(screen, action, "完成验证未执行：界面已更新，请根据新页面核对结果，没有重放设备操作。"))
                            return@repeat
                        }
                        throw error
                    }
                }
                if (approvalRequired(action, screen)) {
                    event(Phase.AWAITING_APPROVAL, action.label())
                    val allowed = approval.request(action, screen)
                    if (control.epoch != epoch) return@repeat
                    if (!allowed) return event(Phase.CANCELLED, "已拒绝操作，任务停止")
                    cancellation.check()
                    val fresh = driver.observe()
                    if (fresh.packageName != screen.packageName || fresh.fingerprint != screen.fingerprint) {
                        if (staleRetries++ >= 6) error("界面持续变化，请接管或稍后重试")
                        remember(Turn(screen, action, "操作未执行：确认期间界面发生变化，旧确认已失效。请根据新界面重新决策；敏感操作仍需重新确认。"))
                        event(Phase.RUNNING, "界面已变化，正在重新读取并规划")
                        return@repeat
                    }
                }
                cancellation.check()
                if (control.epoch != epoch) return@repeat
                event(Phase.RUNNING, action.label())
                val result = try { driver.execute(action, screen) } catch (e: IllegalStateException) {
                    if (control.epoch != epoch && e.message?.contains("用户正在接管") == true) return@repeat
                    // The driver explicitly guarantees this error occurs before injection.
                    // Re-observe/re-plan, never replay an accepted or ambiguous action.
                    if ((e.message?.startsWith("界面已更新，本次操作未执行") == true || e.message?.startsWith("截图已过期") == true) && staleRetries++ < 6) {
                        remember(Turn(screen, action, "操作未执行：界面或截图已更新，没有注入设备操作。请根据新界面重新选择目标；界面仍在加载时可先等待，不要重放旧编号或旧坐标。"))
                        event(Phase.RUNNING, "界面已变化，正在重新读取并规划")
                        return@repeat
                    }
                    throw e
                }
                staleRetries = 0
                remember(Turn(screen, action, result))
            }
            event(Phase.FAILED, "达到 $maxSteps 步上限，请缩小任务范围")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            event(Phase.CANCELLED, "任务已停止")
        } catch (e: Exception) {
            try { cancellation.check(); event(Phase.FAILED, e.message ?: "任务执行失败") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); event(Phase.CANCELLED, "任务已停止") }
        }
    }

    private fun validate(action: Action, screen: Screen) {
        fun coordinates(x: Int, y: Int, captureId: String) {
            require(x in 0..1000 && y in 0..1000) { "坐标须在 0–1000 范围内" }
            require(captureId.isNotBlank() && screen.visual?.id == captureId) { "请先截图或 OCR 获取当前画面，再使用坐标操作" }
        }
        val nodeId = when (action) {
            is Action.Tap -> action.nodeId
            is Action.Input -> action.nodeId
            is Action.Scroll -> action.nodeId
            else -> null
        }
        val node = nodeId?.let { id -> screen.nodes.singleOrNull { it.id == id } ?: error("模型引用了不存在的节点") }
        require(node == null || node.enabled) { "目标控件尚未启用，请等待可操作状态" }
        when (action) {
            is Action.TapAt -> coordinates(action.x, action.y, action.captureId)
            is Action.LongPress -> { coordinates(action.x, action.y, action.captureId); require(action.duration in 400..2000) { "长按时长须为 400–2000 毫秒" } }
            is Action.Swipe -> {
                coordinates(action.x, action.y, action.captureId); coordinates(action.endX, action.endY, action.captureId)
                require(action.duration in 100..2000 && (action.x != action.endX || action.y != action.endY)) { "滑动距离或时长无效" }
            }
            is Action.Tap -> require(node!!.clickable) { "目标节点不可点击" }
            is Action.Input -> {
                require(node!!.editable && !node.sensitive) { "目标节点不可输入" }
                require(action.text.length in 1..2000) { "输入文本长度无效" }
            }
            is Action.Scroll -> require(node!!.scrollable) { "目标节点不可滚动" }
            is Action.Script -> require(action.code.isNotBlank() && action.code.length <= 16000) { "脚本须为 1–16000 字符" }
            is Action.Query, is Action.WaitUntil, is Action.Verify -> Inspector.validate(action)
            is Action.Finish -> { require(action.summary.isNotBlank() && action.summary.length <= 2000) { "模型返回的结果无效" }; if (action.evidence.isNotEmpty()) Conditions.validate(action.evidence) }
            is Action.Wait -> require(action.milliseconds in 200..2000) { "等待时间超出范围" }
            is Action.Launch -> require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(action.packageName)) { "应用包名无效" }
            else -> Unit
        }
    }
}
