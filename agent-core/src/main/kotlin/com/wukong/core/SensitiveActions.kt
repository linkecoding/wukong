package com.wukong.core

/** Local approval rules use observed UI, never the model's claim that an action is safe. */
object SensitiveActions {
    private val consequence = Regex(
        "支付|付款|转账|汇款|下单|提交订单|确认订单|结算|购买|充值|提现|扣款|订阅|删除|移除|卸载|清空|清除数据|格式化|恢复出厂|注销|退出登录|发送|发布|发表|分享|上传|授权|安装|" +
            "\\b(pay|payment|transfer|purchase|buy|checkout|subscribe|delete|remove|uninstall|erase|format|factory reset|send|publish|post|share|authorize|install)\\b",
        RegexOption.IGNORE_CASE
    )
    private val security = Regex("密码|验证码|权限|安全设置|设备管理|调试授权|password|permission|security|device admin", RegexOption.IGNORE_CASE)
    private val confirm = Regex("^(确定|确认|是|好|好的|继续|完成|提交|同意|允许|立即|关闭|开启|启用|停用|禁用|确认.*|继续.*|yes|ok|confirm|continue|done|submit|allow|accept|enable|disable)$", RegexOption.IGNORE_CASE)

    fun requiresApproval(action: Action, screen: Screen): Boolean {
        val allText = screen.nodes.map { it.text } + screen.visual?.lines.orEmpty().map { it.text }
        val consequentialPage = allText.any { consequence.containsMatchIn(it) || security.containsMatchIn(it) }
        if (action is Action.Swipe) return consequentialPage
        val point = when (action) {
            is Action.TapAt -> action.x to action.y
            is Action.LongPress -> action.x to action.y
            else -> null
        }
        if (point != null) {
            val targets = screen.nodes.filter { it.bounds?.contains(point.first, point.second) == true }.map { it.text } +
                screen.visual?.lines.orEmpty().filter { line ->
                    // OCR boxes describe glyphs, while a visually chosen tap can land
                    // just outside their antialiased edge. Keep a small normalized margin.
                    val b = line.bounds
                    Bounds(b.left-8,b.top-8,b.right+8,b.bottom+8).contains(point.first,point.second)
                }.map { it.text }
            // A coordinate with no readable target cannot be classified as ordinary.
            if (targets.none { it.isNotBlank() }) return true
            if (targets.any { consequence.containsMatchIn(it) || security.containsMatchIn(it) }) return true
            return consequentialPage && targets.any { confirm.matches(it.trim()) }
        }
        val id = when (action) {
            is Action.Tap -> action.nodeId
            is Action.Input -> action.nodeId
            else -> return false
        }
        val target = screen.nodes.singleOrNull { it.id == id } ?: return true
        if (target.sensitive) return true
        // Search/query text is data, rather than an instruction to pay or delete.
        if (action is Action.Input) return security.containsMatchIn(target.text)
        if (consequence.containsMatchIn(target.text) || security.containsMatchIn(target.text)) return true
        return consequentialPage && (target.text.isBlank() || confirm.matches(target.text.trim()))
    }
}
