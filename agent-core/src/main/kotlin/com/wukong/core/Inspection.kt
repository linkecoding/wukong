package com.wukong.core

/** Selectors are re-evaluated on each snapshot; node IDs are never retained across waits. */
data class NodeSelector(
    val textContains: String? = null, val textEquals: String? = null,
    val resourceId: String? = null, val className: String? = null,
    val editable: Boolean? = null, val clickable: Boolean? = null, val scrollable: Boolean? = null,
    val enabled: Boolean? = null, val checked: Boolean? = null, val selected: Boolean? = null,
    val ancestorResourceId: String? = null,
) {
    fun validate() {
        require(listOf(textContains, textEquals, resourceId, className, ancestorResourceId).all { it == null || it.isNotBlank() && it.length <= 300 }) { "查询文字须为 1–300 字" }
        require(textContains == null || textEquals == null) { "文字包含和精确匹配只能选择一个" }
    }
    fun matches(node: UiNode, nodes: Map<Int, UiNode>): Boolean {
        if (textContains != null && !node.text.contains(textContains, ignoreCase = true)) return false
        if (textEquals != null && node.text != textEquals) return false
        if (resourceId != null && node.resourceId != resourceId || className != null && node.className != className) return false
        if (editable != null && node.editable != editable || clickable != null && node.clickable != clickable || scrollable != null && node.scrollable != scrollable) return false
        if (enabled != null && node.enabled != enabled || checked != null && node.checked != checked || selected != null && node.selected != selected) return false
        if (ancestorResourceId != null) {
            var parent = node.parentId; val seen = mutableSetOf<Int>()
            while (parent != null && seen.add(parent)) {
                val ancestor = nodes[parent] ?: break
                if (ancestor.resourceId == ancestorResourceId) return true
                parent = ancestor.parentId
            }
            return false
        }
        return true
    }
}

enum class Presence { PRESENT, ABSENT }
data class ScreenCondition(val selector: NodeSelector? = null, val packageName: String? = null, val state: Presence = Presence.PRESENT, val minCount: Int = 1) {
    fun validate() {
        require(selector != null || packageName != null) { "条件须指定控件或应用包名" }
        selector?.validate()
        require(selector == null || selector != NodeSelector()) { "等待或验证须指定具体控件条件" }
        require(packageName == null || Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName)) { "条件包名无效" }
        require(minCount in 1..100) { "匹配数量须为 1–100" }
        require(state != Presence.ABSENT || selector != null && minCount == 1) { "消失条件须指定控件，且不能指定匹配数量" }
    }
}

enum class CheckState { MET, NOT_MET, UNKNOWN }
data class ConditionEvidence(val condition: ScreenCondition, val state: CheckState, val matches: List<UiNode>, val totalMatches: Int)
data class ConditionReport(val screen: Screen, val checks: List<ConditionEvidence>, val state: CheckState)
data class InspectionResult(val screen: Screen, val nodes: List<UiNode> = emptyList(), val totalMatches: Int = 0, val offset: Int = 0, val report: ConditionReport? = null, val timedOut: Boolean = false, val elapsedMillis: Long = 0, val samples: Int = 1)

object Conditions {
    fun validate(conditions: List<ScreenCondition>) { require(conditions.size in 1..5) { "须提供 1–5 个条件" }; conditions.forEach { it.validate() } }
    fun find(screen: Screen, selector: NodeSelector): List<UiNode> {
        selector.validate()
        val nodes = screen.nodes.associateBy { it.id }
        return screen.nodes.filter { selector.matches(it, nodes) }
    }
    fun evaluate(screen: Screen, conditions: List<ScreenCondition>, matchAll: Boolean): ConditionReport {
        validate(conditions)
        val checks = conditions.map { condition ->
            val matches = condition.selector?.let { find(screen, it) }.orEmpty()
            val state = when {
                condition.packageName != null && screen.packageName != condition.packageName -> CheckState.NOT_MET
                condition.selector == null -> CheckState.MET
                condition.state == Presence.ABSENT && matches.isNotEmpty() -> CheckState.NOT_MET
                condition.state == Presence.PRESENT && matches.size >= condition.minCount -> CheckState.MET
                screen.truncated -> CheckState.UNKNOWN // Missing content cannot prove absence or an insufficient count.
                condition.state == Presence.ABSENT -> CheckState.MET
                else -> CheckState.NOT_MET
            }
            ConditionEvidence(condition, state, matches.take(20), matches.size)
        }
        val states = checks.map { it.state }
        val state = if (matchAll) when {
            CheckState.NOT_MET in states -> CheckState.NOT_MET
            CheckState.UNKNOWN in states -> CheckState.UNKNOWN
            else -> CheckState.MET
        } else when {
            CheckState.MET in states -> CheckState.MET
            CheckState.UNKNOWN in states -> CheckState.UNKNOWN
            else -> CheckState.NOT_MET
        }
        return ConditionReport(screen, checks, state)
    }
}

/** Read-only execution on the caller's worker. No whole-page quiet interval is required. */
class Inspector(
    private val read: () -> Screen, private val check: () -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }, private val pause: (Long) -> Unit = Thread::sleep,
) {
    fun run(action: Action): InspectionResult {
        validate(action)
        val start = now()
        fun sample(): Screen { check(); return read().also { check(); require(it.nodes.none { node -> node.sensitive }) { "检测到受保护输入，请手动处理" } } }
        if (action is Action.Query) {
            val screen = sample(); val matches = Conditions.find(screen, action.selector)
            return InspectionResult(screen, matches.drop(action.offset).take(action.limit), matches.size, action.offset, elapsedMillis = now() - start)
        }
        val conditions = if (action is Action.WaitUntil) action.conditions else (action as Action.Verify).conditions
        val matchAll = if (action is Action.WaitUntil) action.matchAll else (action as Action.Verify).matchAll
        var samples = 0
        while (true) {
            val screen = sample(); samples++
            val report = Conditions.evaluate(screen, conditions, matchAll)
            val elapsed = now() - start
            if (action !is Action.WaitUntil || report.state == CheckState.MET || elapsed >= action.timeoutMillis) {
                return InspectionResult(screen, report = report, timedOut = action is Action.WaitUntil && report.state != CheckState.MET, elapsedMillis = elapsed, samples = samples)
            }
            pause(minOf(150, action.timeoutMillis - elapsed)); check()
        }
    }
    companion object {
        fun validate(action: Action) = when (action) {
            is Action.Query -> { action.selector.validate(); require(action.limit in 1..60 && action.offset in 0..1500) { "查询分页范围无效" } }
            is Action.WaitUntil -> { Conditions.validate(action.conditions); require(action.timeoutMillis in 200..10000) { "条件等待须为 200–10000 毫秒" } }
            is Action.Verify -> Conditions.validate(action.conditions)
            else -> error("不是只读查询操作")
        }
    }
}

enum class TaskOutcome { SUCCESS, INCOMPLETE }
data class CompletionCheck(val verified: Boolean, val detail: String)
fun interface CompletionVerifier { fun verify(goal: String, summary: String, screen: Screen, history: List<Turn>): CompletionCheck }
