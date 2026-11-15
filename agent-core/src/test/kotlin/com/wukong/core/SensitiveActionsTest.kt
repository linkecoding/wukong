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
        val model = Model { _, _, history -> if (history.isEmpty()) Action.Tap(1) else Action.Finish("done", evidence = listOf(ScreenCondition(packageName = "demo.app"))) }
        val result = Agent(model, driver, Approval { _, _ -> error("普通搜索不应请求确认") }).run("搜索", Cancellation()) { }
        assertEquals(Phase.COMPLETED, result.phase)
        assertEquals(1, executed)
        for (action in listOf(Action.Home, Action.Back, Action.Launch("com.android.settings"), Action.Scroll(1, true), Action.Input(1, "删除文件的方法"))) {
            assertFalse(SensitiveActions.requiresApproval(action, state))
        }
    }

    @Test fun consequentialButtonsAndTheirConfirmationPagesRequireApproval() {
        for (text in listOf("立即支付", "删除文件", "Delete", "清空回收站", "清除数据", "恢复出厂设置", "确认转账", "卸载应用")) {
            assertTrue(text, SensitiveActions.requiresApproval(Action.Tap(1), screen(text)))
        }
        assertTrue(SensitiveActions.requiresApproval(Action.Tap(1), screen("确定", "永久删除文件，不可恢复")))
        assertTrue(SensitiveActions.requiresApproval(Action.Tap(1), screen("继续", "付款金额 100 元")))
        assertTrue(SensitiveActions.requiresApproval(Action.Tap(1), screen("", "确认转账")))
        assertFalse(SensitiveActions.requiresApproval(Action.Tap(1), screen("搜索", "删除的文件")))
    }

    @Test fun routineCommunicationPermissionsAndSettingsAreAlreadyAuthorized() {
        for (text in listOf("发送", "发布", "分享", "上传", "允许", "授权", "安装", "开启", "权限设置", "安全设置", "退出登录", "订阅频道", "删除文字", "清空输入框", "支付方式", "付款记录", "删除文件的方法", "Transfer files", "Payment settings", "Send", "Allow")) {
            assertFalse(text, SensitiveActions.requiresApproval(Action.Tap(1), screen(text)))
        }
        assertFalse(SensitiveActions.requiresApproval(Action.Input(1, "100"), screen("转账金额")))
        assertFalse(SensitiveActions.requiresApproval(Action.Tap(1), screen("允许", "请允许相机权限")))
        assertFalse(SensitiveActions.requiresApproval(Action.Tap(1), screen("确定", "安全设置：通知权限")))
        assertFalse(SensitiveActions.requiresApproval(Action.Tap(1), screen("确定", "删除文件的方法")))
    }

    @Test fun coordinateApprovalUsesTheLocalTargetRatherThanUnrelatedPageWords() {
        val state = Screen("demo.app", "one", listOf(
            UiNode(1,"支付、删除、权限设置",bounds=Bounds(0,0,1000,1000)),
            UiNode(2,"发送",clickable=true,bounds=Bounds(100,100,300,200)),
            UiNode(3,"立即支付",clickable=true,bounds=Bounds(500,500,800,600))
        ))
        assertFalse(SensitiveActions.requiresApproval(Action.TapAt(200,150,"c"), state))
        assertTrue(SensitiveActions.requiresApproval(Action.TapAt(600,550,"c"), state))
        assertFalse(SensitiveActions.requiresApproval(Action.TapAt(900,900,"c"), state))
        assertFalse(SensitiveActions.requiresApproval(Action.Swipe(200,200,200,800,350,"c"), state))
        assertTrue(SensitiveActions.requiresApproval(Action.Swipe(200,200,800,200,350,"c"), screen("滑动确认支付")))
        val nested = Screen("demo.app", "one", listOf(UiNode(1,"",clickable=true,bounds=Bounds(100,100,400,200)), UiNode(2,"立即支付",parentId=1,bounds=Bounds(180,130,320,170))))
        assertTrue(SensitiveActions.requiresApproval(Action.Tap(1), nested))
        assertTrue(SensitiveActions.requiresApproval(Action.TapAt(110,110,"c"), nested))
        val dialog = screen("", "永久删除文件，不可恢复")
        assertTrue(SensitiveActions.requiresApproval(Action.TapAt(900,900,"c"), dialog))
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
        val model = Model { _, _, history -> decisions++; if (history.lastOrNull()?.result == "accepted") Action.Finish("done", evidence = listOf(ScreenCondition(packageName = "demo.app"))) else Action.Tap(1) }
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
