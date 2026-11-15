package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class ScriptTest {
    private val screen = Screen("demo.app", "one", listOf(UiNode(1, "搜索", clickable = true), UiNode(2, "输入", editable = true)))
    private class Driver(private val screen: Screen, val fail: Boolean = false) : DeviceDriver {
        val actions = mutableListOf<Action>()
        override fun observe() = screen
        override fun execute(action: Action, expected: Screen): String { actions += action; if (fail) error("结果未知") else return "accepted" }
    }
    private fun model() = Model { _, _, history -> assertTrue("Script snapshots must remain bounded", history.size <= 8); if (history.none { it.action is Action.Script }) Action.Script("script") else Action.Finish("done", evidence = listOf(ScreenCondition(packageName = "demo.app"))) }
    @Test fun scriptHostKeepsPerActionValidationAndSuccessfulHistory() {
        val driver = Driver(screen)
        val result = Agent(model(), driver, Approval { _, _ -> error("ordinary action") }, scriptRunner = ScriptRunner { _, host ->
            host.execute(Action.Input(2, "耳机"), host.observe()); host.execute(Action.Tap(1), host.observe()); "找到结果"
        }).run("搜索", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase); assertEquals(listOf(Action.Input(2, "耳机"), Action.Tap(1)), driver.actions)
    }
    @Test fun refusalInsideAScriptStopsTheTaskWithoutInjection() {
        val driver = Driver(screen.copy(nodes = listOf(UiNode(1, "立即支付", clickable = true))))
        val result = Agent(model(), driver, Approval { _, _ -> false }, scriptRunner = ScriptRunner { _, host -> host.execute(Action.Tap(1), host.observe()) }).run("支付", Cancellation()) {}
        assertEquals(Phase.CANCELLED, result.phase); assertTrue(driver.actions.isEmpty())
    }
    @Test fun changedApprovalAbortsCellAndCannotBeCaughtToContinue() {
        var current = screen; var injections = 0
        val driver = object : DeviceDriver {
            override fun observe() = current
            override fun execute(action: Action, expected: Screen): String { injections++; return "accepted" }
        }
        val runner = ScriptRunner { _, host ->
            val failure = runCatching { host.execute(Action.Tap(1), host.observe()) }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("旧确认已失效"))
            host.execute(Action.Tap(1), screen)
        }
        val result = Agent(model(), driver, Approval { _, _ -> current = screen.copy(fingerprint = "changed"); true }, approvalRequired = { _, _ -> true }, scriptRunner = runner).run("task", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase); assertEquals(0, injections)
    }
    @Test fun ambiguousAcceptedFailuresNeverPermitFurtherCellActions() {
        val driver = Driver(screen, fail = true)
        val result = Agent(model(), driver, Approval { _, _ -> true }, scriptRunner = ScriptRunner { _, host ->
            runCatching { host.execute(Action.Tap(1), host.observe()) }
            host.execute(Action.Tap(1), screen)
        }).run("task", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase); assertEquals(1, driver.actions.size)
    }
    @Test fun scriptCannotDirectlyFinishOrNestAScript() {
        val driver = Driver(screen)
        val result = Agent(model(), driver, Approval { _, _ -> true }, scriptRunner = ScriptRunner { _, host ->
            host.execute(Action.Finish("fake success"), host.observe())
        }).run("task", Cancellation()) {}
        assertEquals(Phase.COMPLETED, result.phase); assertTrue(driver.actions.isEmpty())
    }
    @Test fun takeoverStopsRemainingOperationsAndNewDecisionIsRequired() {
        val control = ExecutionControl(); val driver = Driver(screen); var decisions = 0
        val result = Agent(Model { _, _, _ -> if (++decisions == 1) Action.Script("script") else Action.Finish("人已接管", TaskOutcome.INCOMPLETE) }, driver, Approval { _, _ -> true }, control = control,
            scriptRunner = ScriptRunner { _, host ->
                host.execute(Action.Tap(1), host.observe()); control.pause(true); control.pause(false)
                host.execute(Action.Tap(1), screen)
            }).run("task", Cancellation()) {}
        assertEquals(Phase.FAILED, result.phase); assertEquals(1, driver.actions.size); assertEquals(2, decisions)
    }
    @Test fun invalidNodeCannotBeApprovedFromAScript() {
        val driver = Driver(screen)
        Agent(model(), driver, Approval { _, _ -> error("invalid node must not ask") }, scriptRunner = ScriptRunner { _, host -> host.execute(Action.Tap(99), host.observe()) }).run("task", Cancellation()) {}
        assertTrue(driver.actions.isEmpty())
    }
    @Test fun taskWideMutationBudgetSurvivesCaughtErrors() {
        val driver = Driver(screen)
        Agent(model(), driver, Approval { _, _ -> true }, scriptRunner = ScriptRunner { _, host ->
            repeat(150) { runCatching { host.execute(Action.Tap(1), screen) } }; "done"
        }).run("task", Cancellation()) {}
        assertEquals(100, driver.actions.size)
    }
}
