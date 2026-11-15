package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class SensitiveActionsTest {
    private fun screen(label: String, context: String = "") = Screen("demo.app", "one", listOf(
        UiNode(1, label, clickable = true, editable = true), UiNode(2, context)
    ))

    @Test fun ordinaryActionsExecuteWithoutApproval() {
        val state = screen("搜索")
        var executed = 0
        val driver = object : DeviceDriver {
            override fun observe() = state
            override fun execute(action: Action, expected: Screen): String { executed++; return "accepted" }
        }
        val model = Model { _, _, history -> if (history.isEmpty()) Action.Tap(1) else Action.Finish("done") }
        val result = Agent(model, driver, Approval { _, _ -> error("普通搜索不应请求确认") }).run("搜索", Cancellation()) { }
        assertEquals(Phase.COMPLETED, result.phase)
        assertEquals(1, executed)
        for (action in listOf(Action.Home, Action.Back, Action.Launch("com.android.settings"), Action.Scroll(1, true), Action.Input(1, "删除文件的方法"))) {
            assertFalse(SensitiveActions.requiresApproval(action, state))
        }
    }

    @Test fun consequentialButtonsAndTheirConfirmationPagesRequireApproval() {
        for (text in listOf("立即支付", "删除文件", "Delete", "清空回收站", "发送", "发布", "权限设置")) {
            assertTrue(text, SensitiveActions.requiresApproval(Action.Tap(1), screen(text)))
        }
        assertTrue(SensitiveActions.requiresApproval(Action.Tap(1), screen("确定", "永久删除文件，不可恢复")))
        assertTrue(SensitiveActions.requiresApproval(Action.Tap(1), screen("继续", "付款金额 100 元")))
        assertTrue(SensitiveActions.requiresApproval(Action.Tap(1), screen("", "确认转账")))
        assertFalse(SensitiveActions.requiresApproval(Action.Tap(1), screen("搜索", "删除的文件")))
    }

    @Test fun refusingPaymentDoesNotExecute() {
        val state = screen("确认支付")
        val driver = object : DeviceDriver {
            override fun observe() = state
            override fun execute(action: Action, expected: Screen): String = error("拒绝后不能支付")
        }
        val result = Agent(Model { _, _, _ -> Action.Tap(1) }, driver, Approval { _, _ -> false })
            .run("支付", Cancellation()) { }
        assertEquals(Phase.CANCELLED, result.phase)
    }

    @Test fun staleUnexecutedActionIsReplannedButOtherFailuresAreNotReplayed() {
        val state = screen("搜索")
        var attempts = 0
        var decisions = 0
        val driver = object : DeviceDriver {
            override fun observe() = state
            override fun execute(action: Action, expected: Screen): String {
                if (attempts++ == 0) throw IllegalStateException("界面已更新，本次操作未执行")
                return "accepted"
            }
        }
        val model = Model { _, _, history -> decisions++; if (history.lastOrNull()?.result == "accepted") Action.Finish("done") else Action.Tap(1) }
        assertEquals(Phase.COMPLETED, Agent(model, driver, Approval { _, _ -> error("普通操作") }).run("搜索", Cancellation()) { }.phase)
        assertEquals(2, attempts)
        assertEquals(3, decisions)
        val failing = object : DeviceDriver {
            override fun observe() = state
            override fun execute(action: Action, expected: Screen): String { throw IllegalStateException("网络中断，结果未知") }
        }
        decisions = 0
        assertEquals(Phase.FAILED, Agent(model, failing, Approval { _, _ -> true }).run("搜索", Cancellation()) { }.phase)
        assertEquals(1, decisions)
    }
}
