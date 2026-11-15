package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class InspectionTest {
    private val screen = Screen("demo.app", "one", listOf(
        UiNode(1, "", resourceId = "demo:id/results", className = "Layout"),
        UiNode(2, "耳机 A", clickable = true, resourceId = "demo:id/title", parentId = 1),
        UiNode(3, "耳机 B", clickable = true, resourceId = "demo:id/title", parentId = 1),
        UiNode(4, "搜索", editable = true, enabled = false),
    ))
    private fun condition(text: String, state: Presence = Presence.PRESENT) = ScreenCondition(NodeSelector(textContains = text), state = state)

    @Test fun querySupportsSubtreeStatesAndPagination() {
        val result = Inspector({ screen }).run(Action.Query(NodeSelector(textContains = "耳机", ancestorResourceId = "demo:id/results", enabled = true), limit = 1, offset = 1))
        assertEquals(2, result.totalMatches); assertEquals(listOf(3), result.nodes.map { it.id })
        assertEquals(listOf(4), Conditions.find(screen, NodeSelector(editable = true, enabled = false)).map { it.id })
        assertTrue(Conditions.find(screen, NodeSelector(textEquals = "耳机")).isEmpty())
    }
    @Test fun absentIsUnknownOnTruncatedSourceAndPackageMismatchIsNotAbsence() {
        val missing = condition("不存在", Presence.ABSENT)
        assertEquals(CheckState.MET, Conditions.evaluate(screen, listOf(missing), true).state)
        assertEquals(CheckState.UNKNOWN, Conditions.evaluate(screen.copy(truncated = true), listOf(missing), true).state)
        assertEquals(CheckState.NOT_MET, Conditions.evaluate(screen, listOf(missing.copy(packageName = "other.app")), true).state)
        assertEquals(CheckState.NOT_MET, Conditions.evaluate(screen.copy(truncated = true), listOf(condition("耳机", Presence.ABSENT)), true).state)
        assertEquals(CheckState.MET, Conditions.evaluate(screen.copy(truncated = true), listOf(condition("耳机")), true).state)
    }
    @Test fun emptyConditionsAndWildcardsAndInvalidWaitsAreRejected() {
        for (action in listOf(Action.WaitUntil(emptyList()), Action.Verify(listOf(ScreenCondition(NodeSelector()))), Action.WaitUntil(listOf(condition("耳机")), timeoutMillis = 60000), Action.Query(limit = 1000))) {
            assertTrue(runCatching { Inspector({ error("must not read") }).run(action) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun anyAndAllConditionsDistinguishResultFromError() {
        val alternatives = listOf(condition("耳机"), condition("网络失败"))
        assertEquals(CheckState.MET, Conditions.evaluate(screen, alternatives, false).state)
        assertEquals(CheckState.NOT_MET, Conditions.evaluate(screen, alternatives, true).state)
    }
    @Test fun waitFindsTargetWhileUnrelatedContentKeepsChangingAndRebindsIds() {
        var clock = 0L; var reads = 0
        val inspector = Inspector({
            reads++
            screen.copy(fingerprint = "moving-$reads", nodes = listOf(UiNode(reads, if (clock >= 300) "结果就绪" else "加载中")))
        }, now = { clock }, pause = { clock += it })
        val result = inspector.run(Action.WaitUntil(listOf(condition("结果就绪")), timeoutMillis = 1000))
        assertEquals(300, result.elapsedMillis); assertEquals(3, result.samples)
        assertEquals(CheckState.MET, result.report!!.state); assertEquals(3, result.report!!.checks.single().matches.single().id)
    }
    @Test fun timeoutReturnsEvidenceAndNeverMutates() {
        var clock = 0L
        val result = Inspector({ screen }, now = { clock }, pause = { clock += it }).run(Action.WaitUntil(listOf(condition("等待内容")), timeoutMillis = 500))
        assertTrue(result.timedOut); assertEquals(500, result.elapsedMillis); assertEquals(CheckState.NOT_MET, result.report!!.state)
    }
    @Test fun waitingHonorsCancellationAndTakeoverBetweenReads() {
        var clock = 0L; var reads = 0
        val result = runCatching { Inspector({ reads++; screen }, check = { check(clock < 150) { "用户正在接管" } }, now = { clock }, pause = { clock += it })
            .run(Action.WaitUntil(listOf(condition("等待内容")))) }
        assertEquals("用户正在接管", result.exceptionOrNull()?.message); assertEquals(1, reads)
    }
    @Test fun protectedContentsAreNeverReturnedAsQueryResults() {
        val protected = screen.copy(nodes = listOf(UiNode(1, "hidden", sensitive = true)))
        assertTrue(runCatching { Inspector({ protected }).run(Action.Query()) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun cancellationInterruptsAWaitRatherThanReturningATimeout() {
        val token = Cancellation()
        val failure = runCatching { Inspector({ screen }, check = token::check, pause = { token.cancel() })
            .run(Action.WaitUntil(listOf(condition("未出现")))) }.exceptionOrNull()
        assertTrue(failure is InterruptedException)
    }

}
