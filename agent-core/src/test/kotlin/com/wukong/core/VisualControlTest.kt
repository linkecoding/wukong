package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class VisualControlTest {
    private val frame = VisualFrame("capture", 720, 1280, listOf(OcrLine("搜索", Bounds(100,100,300,200)), OcrLine("立即支付", Bounds(500,500,800,600))))
    private val screen = Screen("demo.app", "one", emptyList(), frame)
    @Test fun coordinateToolsNeedCurrentCaptureAndCannotBypassSensitiveChecks() {
        assertFalse(SensitiveActions.requiresApproval(Action.TapAt(200,150,"capture"), screen))
        assertTrue(SensitiveActions.requiresApproval(Action.TapAt(600,550,"capture"), screen))
        assertFalse(SensitiveActions.requiresApproval(Action.TapAt(900,900,"capture"), screen))
        for (action in listOf(Action.TapAt(-1,150,"capture"), Action.TapAt(200,150,"old"), Action.LongPress(200,150,5,"capture"))) {
            val driver = object : DeviceDriver {
                override fun observe() = screen
                override fun execute(action: Action, expected: Screen): String = error("Invalid visual action must not execute")
            }
            assertEquals(Phase.FAILED, Agent(Model { _,_,_ -> action }, driver, Approval { _,_ -> true }).run("test", Cancellation()) { }.phase)
        }
    }
    @Test fun takeoverDiscardsAnInFlightDecisionAndReobservesOnHandoff() {
        val control = ExecutionControl()
        var observations = 0; var decisions = 0; var executed = 0
        val driver = object : DeviceDriver {
            override fun observe(): Screen { observations++; return screen }
            override fun execute(action: Action, expected: Screen): String { executed++; return "accepted" }
        }
        val model = Model { _,_,history ->
            decisions++
            if (decisions == 1) { control.pause(true); control.pause(false); Action.TapAt(200,150,"capture") }
            else if (history.isEmpty()) Action.TapAt(200,150,"capture") else Action.Finish("done", evidence = listOf(ScreenCondition(packageName = "demo.app")))
        }
        assertEquals(Phase.COMPLETED, Agent(model, driver, Approval { _,_ -> false }, control = control).run("test", Cancellation()) { }.phase)
        assertEquals(1, executed); assertEquals(4, observations); assertEquals(3, decisions)
    }
}
