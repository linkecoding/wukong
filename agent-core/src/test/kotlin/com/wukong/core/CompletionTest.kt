package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class CompletionTest {
    private val condition = ScreenCondition(NodeSelector(textEquals = "结果就绪"))
    private val screen = Screen("demo.app", "one", listOf(UiNode(1, "结果就绪")))
    private class Driver(var current: Screen) : DeviceDriver {
        var reads = 0; val actions = mutableListOf<Action>()
        var onRead: () -> Unit = {}
        override fun observe(): Screen { reads++; onRead(); return current }
        override fun execute(action: Action, expected: Screen): String { actions += action; return "accepted" }
    }
    private val approval = Approval { _: Action, _: Screen -> error("Verification must not ask for mutation approval") }
    @Test fun successfulFinishRequiresAFreshCheckNotOldObservation() {
        val driver = Driver(screen)
        var calls = 0
        val result = Agent(Model { _, _, history ->
            if (++calls == 1) {
                driver.current = screen.copy(fingerprint = "two", nodes = listOf(UiNode(1, "加载中")))
                Action.Finish("完成", evidence = listOf(condition))
            } else {
                assertTrue(history.last().result.contains("完成验证未通过"))
                Action.Finish("结果仍在加载，未完成", TaskOutcome.INCOMPLETE)
            }
        }, driver, approval).run("查看结果", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertTrue(driver.actions.isEmpty()); assertEquals(2, calls)
    }
    @Test fun verifiedFinishUsesNoMutationOrExtraModelCall() {
        val driver = Driver(screen); var calls = 0
        val result = Agent(Model { _, _, _ -> calls++; Action.Finish("完成", evidence = listOf(condition)) }, driver, approval).run("查看结果", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase); assertEquals(1, calls); assertEquals(2, driver.reads); assertTrue(driver.actions.isEmpty())
    }
    @Test fun unsupportedSuccessCannotSilentlyPassAndHasABoundedBudget() {
        val driver = Driver(screen); var calls = 0
        val result = Agent(Model { _, _, _ -> calls++; Action.Finish("完成") }, driver, approval).run("查看结果", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertEquals(3, calls); assertTrue(driver.actions.isEmpty())
    }
    @Test fun explicitIncompleteDoesNotRunVerificationOrClaimCompletion() {
        val driver = Driver(screen)
        val result = Agent(Model { _, _, _ -> Action.Finish("无法确认", TaskOutcome.INCOMPLETE) }, driver, approval).run("查看结果", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertEquals(1, driver.reads); assertTrue(driver.actions.isEmpty())
    }
    @Test fun semanticReviewGetsFreshCaptureAndCanRejectWithoutReplayingEffects() {
        val driver = Driver(screen); var reviews = 0
        val result = Agent(Model { _, _, _ -> Action.Finish("完成") }, driver, approval,
            completionVerifier = CompletionVerifier { goal, summary, current, _ ->
                assertEquals("查看结果", goal); assertEquals("完成", summary); assertEquals(screen, current)
                reviews++; CompletionCheck(false, "缺少目标证据")
            }).run("查看结果", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertEquals(3, reviews); assertEquals(List(3) { Action.Screenshot }, driver.actions)
    }
    @Test fun takeoverDuringVerificationDiscardsTheOldCompletion() {
        val driver = Driver(screen); val control = ExecutionControl(); var decisions = 0
        driver.onRead = { if (driver.reads == 2) { control.pause(true); control.pause(false) } }
        val result = Agent(Model { _, _, _ ->
            if (++decisions == 1) Action.Finish("完成", evidence = listOf(condition)) else Action.Finish("用户变更了页面", TaskOutcome.INCOMPLETE)
        }, driver, approval, control = control).run("查看结果", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertEquals(2, decisions); assertTrue(driver.actions.isEmpty())
    }
    @Test fun pageChangedDuringSemanticReviewInvalidatesEvenAnApprovedVerdict() {
        val driver = Driver(screen); var decisions = 0
        val result = Agent(Model { _, _, history ->
            if (++decisions == 1) Action.Finish("完成") else {
                assertTrue(history.last().result.contains("复核期间页面发生变化"))
                Action.Finish("页面已变化，无法确认", TaskOutcome.INCOMPLETE)
            }
        }, driver, approval, completionVerifier = CompletionVerifier { _, _, _, _ ->
            driver.current = screen.copy(fingerprint = "changed", nodes = listOf(UiNode(1, "网络错误")))
            CompletionCheck(true, "Old evidence was sufficient")
        }).run("查看结果", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertEquals(2, decisions)
        assertEquals(listOf(Action.Screenshot), driver.actions)
    }
    @Test fun disabledControlsAreRejectedBeforeInjection() {
        val driver = Driver(screen.copy(nodes = listOf(UiNode(1, "查询", clickable = true, enabled = false))))
        val result = Agent(Model { _, _, _ -> Action.Tap(1) }, driver, approval).run("查询", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertTrue(driver.actions.isEmpty())
    }

    @Test fun matchingPageConditionCannotBypassSummaryReview() {
        val driver=Driver(screen);var decisions=0;var reviews=0
        val condition=ScreenCondition(packageName=screen.packageName)
        val result=Agent(Model { _,_,_ ->
            decisions++;Action.Finish(if(decisions==1)"Wrong reported number" else "Correct reported number",evidence=listOf(condition))
        },driver,approval,completionVerifier=CompletionVerifier { _,summary,_,_ ->
            reviews++;CompletionCheck(summary=="Correct reported number","Check the reported values")
        }).run("Read the result accurately",Cancellation()) {}
        assertEquals(Phase.COMPLETED,result.phase);assertEquals(2,reviews);assertEquals(2,decisions)
        assertTrue("Native conditions need no extra capture",driver.actions.isEmpty())
    }

    @Test fun inconsistentArithmeticCannotPassEvenIfTheSemanticReviewerWouldAgree() {
        val driver=Driver(screen);var decisions=0;var reviews=0
        val result=Agent(Model { _,_,history ->
            if(++decisions==1)Action.Finish("(128×3+59×2)×0.85=484.3",evidence=listOf(ScreenCondition(packageName=screen.packageName)))
            else { assertTrue(history.last().result.contains("426.7"));Action.Finish("需要重新核对算式",TaskOutcome.INCOMPLETE) }
        },driver,approval,completionVerifier=CompletionVerifier { _,_,_,_ -> reviews++;CompletionCheck(true,"would incorrectly agree") }).run("核对计算",Cancellation()) {}
        assertEquals(Phase.FAILED,result.phase);assertEquals(0,reviews);assertTrue(driver.actions.isEmpty())
    }

}
