package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class AgentTest {
    private val screen = Screen("demo.app", "a", listOf(UiNode(1, "搜索", editable = true, clickable = true, scrollable = true)))
    private class Driver(var screen: Screen) : DeviceDriver {
        val executed = mutableListOf<Action>()
        override fun observe() = screen
        override fun execute(action: Action, expected: Screen): String { executed += action; return "accepted" }
    }
    private fun run(driver: Driver, action: Action, approval: Approval = Approval { _, _ -> true }, token: Cancellation = Cancellation(), steps: Int = 2): AgentEvent =
        Agent(Model { _, _, _ -> action }, driver, approval, "demo.app", steps, approvalRequired = { _, _ -> true }).run("搜索", token) {}

    @Test fun refusalNeverExecutes() {
        val driver = Driver(screen)
        assertEquals(Phase.CANCELLED, run(driver, Action.Tap(1), Approval { _, _ -> false }).phase)
        assertTrue(driver.executed.isEmpty())
    }
    @Test fun changedScreenInvalidatesOldApprovalAndRequiresANewDecisionAndApproval() {
        val driver = Driver(screen)
        var approvals = 0
        val model = Model { _, current, history ->
            if (history.lastOrNull()?.result == "accepted") Action.Finish("done") else {
                if (history.isNotEmpty()) {
                    assertTrue(history.last().result.contains("操作未执行"))
                    assertEquals("b", current.fingerprint)
                    assertTrue(driver.executed.isEmpty())
                }
                Action.Tap(1)
            }
        }
        val result = Agent(model, driver, Approval { _, _ ->
            approvals++
            if (approvals == 1) driver.screen = screen.copy(fingerprint = "b")
            true
        }, "demo.app", approvalRequired = { _, _ -> true }).run("task", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase)
        assertEquals(2, approvals); assertEquals(listOf(Action.Tap(1)), driver.executed)
    }
    @Test fun repeatedPreInjectionChangesHaveABoundedReplanningBudget() {
        var decisions = 0; var attempts = 0
        val driver = object : DeviceDriver {
            override fun observe() = screen
            override fun execute(action: Action, expected: Screen): String { attempts++; error("界面已更新，本次操作未执行") }
        }
        val result = Agent(Model { _, _, history ->
            decisions++
            if (decisions > 1) assertTrue(history.last().result.contains("操作未执行"))
            Action.Tap(1)
        }, driver, Approval { _, _ -> true }).run("task", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertEquals(7, decisions); assertEquals(7, attempts)
    }
    @Test fun appSwitchInvalidatesApprovalEvenIfFingerprintMatches() {
        val driver = Driver(screen)
        val result = run(driver, Action.Tap(1), Approval { _, _ -> driver.screen = screen.copy(packageName = "other.app"); true })
        assertEquals(Phase.FAILED, result.phase)
        assertTrue(driver.executed.isEmpty())
    }
    @Test fun rejectsUnknownNodesBeforeApproval() {
        val driver = Driver(screen)
        var asked = false
        assertEquals(Phase.FAILED, run(driver, Action.Input(99, "text"), Approval { _, _ -> asked = true; true }).phase)
        assertFalse(asked)
        assertTrue(driver.executed.isEmpty())
    }
    @Test fun protectedScreensNeverReachModel() {
        val driver = Driver(screen.copy(nodes = listOf(UiNode(1, "[受保护内容]", sensitive = true))))
        var reachedModel = false
        val result = Agent(Model { _, _, _ -> reachedModel = true; Action.Finish("done") }, driver, Approval { _, _ -> true }, "demo.app")
            .run("task", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase)
        assertFalse(reachedModel)
    }
    @Test fun cancellingWhileApprovingNeverExecutes() {
        val driver = Driver(screen)
        val token = Cancellation()
        assertEquals(Phase.CANCELLED, run(driver, Action.Tap(1), Approval { _, _ -> token.cancel(); true }, token).phase)
        assertTrue(driver.executed.isEmpty())
        Thread.interrupted()
    }
    @Test fun cancellationDuringModelCallNeverExecutes() {
        val token = Cancellation()
        val driver = Driver(screen)
        val result = Agent(Model { _, _, _ -> token.cancel(); Action.Tap(1) }, driver, Approval { _, _ -> true }, "demo.app")
            .run("task", token) {}
        assertEquals(Phase.CANCELLED, result.phase)
        assertTrue(driver.executed.isEmpty())
        Thread.interrupted()
    }
    @Test fun stopsAtStepBudget() {
        val driver = Driver(screen)
        assertEquals(Phase.FAILED, run(driver, Action.Tap(1), steps = 3).phase)
        assertEquals(3, driver.executed.size)
    }
    @Test fun finishDoesNotMutateDevice() {
        val driver = Driver(screen)
        val result = run(driver, Action.Finish("找到结果"), Approval { _, _ -> error("must not ask") })
        assertEquals(Phase.COMPLETED, result.phase)
        assertEquals("找到结果", result.message)
        assertTrue(driver.executed.isEmpty())
    }
    @Test fun rejectsExcessiveWait() {
        val driver = Driver(screen)
        assertEquals(Phase.FAILED, run(driver, Action.Wait(60_000)).phase)
        assertTrue(driver.executed.isEmpty())
    }
    @Test fun rejectsInputToReadOnlyNode() {
        val driver = Driver(screen.copy(nodes = listOf(UiNode(1, "title"))))
        assertEquals(Phase.FAILED, run(driver, Action.Input(1, "replacement")).phase)
        assertTrue(driver.executed.isEmpty())
    }
    @Test fun completeLoopIncludesAcceptedActionsInNextModelCall() {
        val driver = Driver(screen)
        var decisions = 0
        val model = Model { _, _, history ->
            if (++decisions == 1) Action.Input(1, "耳机") else {
                assertEquals("accepted", history.single().result)
                Action.Finish("已填入搜索词")
            }
        }
        assertEquals(Phase.COMPLETED, Agent(model, driver, Approval { _, _ -> true }, "demo.app").run("task", Cancellation()) {}.phase)
        assertEquals(listOf(Action.Input(1, "耳机")), driver.executed)
    }

    @Test fun phoneScopeAllowsCrossAppWorkflows() {
        var current = screen
        val actions = mutableListOf<Action>()
        val driver = object : DeviceDriver {
            override fun observe() = current
            override fun execute(action: Action, expected: Screen): String {
                actions += action
                current = screen.copy(packageName = "other.app", fingerprint = "other")
                return "opened"
            }
        }
        val model = Model { _, state, _ -> if (state.packageName == "demo.app") Action.Launch("other.app") else Action.Finish("跨应用完成") }
        val result = Agent(model, driver, Approval { _, _ -> true }).run("task", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase)
        assertEquals(listOf(Action.Launch("other.app")), actions)
    }

    @Test fun rejectsMalformedLaunchPackageBeforeApproval() {
        val driver = Driver(screen)
        assertEquals(Phase.FAILED, run(driver, Action.Launch("app; command")).phase)
        assertTrue(driver.executed.isEmpty())
    }

    @Test fun invalidTargetIsReturnedToModelBeforeAnyInjectionOrApproval() {
        val driver = Driver(screen.copy(nodes = screen.nodes + UiNode(2, "non-clickable label")))
        var calls = 0
        var approvals = 0
        val model = Model { _, _, history ->
            when (++calls) {
                1 -> Action.Tap(2)
                2 -> {
                    assertTrue(history.single().result.contains("操作未执行"))
                    assertTrue(driver.executed.isEmpty())
                    assertEquals(0, approvals)
                    Action.Tap(1)
                }
                else -> Action.Finish("done")
            }
        }
        val result = Agent(model, driver, Approval { _, _ -> approvals++; true }, "demo.app", approvalRequired = { _, _ -> true }).run("task", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase)
        assertEquals(listOf(Action.Tap(1)), driver.executed)
        assertEquals(1, approvals)
    }
    @Test fun repeatedInvalidActionsHaveABoundedRetryBudget() {
        val driver = Driver(screen.copy(nodes = listOf(UiNode(1, "label"))))
        var calls = 0
        val result = Agent(Model { _, _, _ -> calls++; Action.Tap(1) }, driver, Approval { _, _ -> error("must not ask") }).run("task", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase)
        assertEquals(4, calls)
        assertTrue(driver.executed.isEmpty())
    }
    @Test fun ambiguousDriverFailuresAreNotRetriedAsValidationErrors() {
        var calls = 0
        var injections = 0
        val driver = object : DeviceDriver {
            override fun observe() = screen
            override fun execute(action: Action, expected: Screen): String { injections++; error("目标不可点击") }
        }
        val result = Agent(Model { _, _, _ -> calls++; Action.Tap(1) }, driver, Approval { _, _ -> true }).run("task", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase)
        assertEquals(1, calls)
        assertEquals(1, injections)
    }
}
