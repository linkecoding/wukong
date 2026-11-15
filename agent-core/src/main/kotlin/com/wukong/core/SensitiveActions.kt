package com.wukong.core

/** User-authorized routine actions are autonomous; only financial/destructive effects ask locally. */
object SensitiveActions {
    private val highRisk = Regex(
        "支付|付款|转账|汇款|下单|提交订单|确认订单|结算|购买|充值|提现|扣款|付费订阅|删除|卸载|清空回收站|清除.*数据|清空.*数据|格式化.*(磁盘|存储|设备)|恢复出厂|注销账户|注销账号|移除文件|" +
            "\\b(pay|payment|transfer|purchase|buy|checkout|delete|uninstall|erase|factory reset)\\b|format (disk|drive|storage)", RegexOption.IGNORE_CASE)
    private val ordinaryLabel = Regex(
        "(支付|付款|转账|购买|订阅)(方式|设置|记录|历史|帮助|说明|管理)|教程|使用指南|如何|怎么|的方法|" +
            "^(清空|清除|删除|移除)(输入|文字|文本|搜索词|搜索框|关键字|关键词|筛选|过滤|收藏)|" +
            "\\b(payment methods?|payment settings?|payment history|purchase history|manage subscriptions?|transfer files?|file transfer|how to|tutorial|clear (text|input|search|filter)|delete (text|character))\\b", RegexOption.IGNORE_CASE)
    // Generic OK/Allow is only risky when the visible UI describes an actual dangerous confirmation.
    private val confirmationContext = Regex(
        "永久删除|不可恢复|是否.*删除|确定.*删除|确认.*删除|付款金额|支付金额|转账金额|确认转账|确认付款|确认支付|恢复出厂|清除所有数据|" +
            "\\b(permanently delete|delete permanently|cannot be undone|payment amount|transfer amount|confirm (payment|transfer|deletion)|factory reset|erase all data)\\b", RegexOption.IGNORE_CASE)
    private val confirm = Regex("^(确定|确认|是|好|好的|继续|完成|提交|同意|允许|立即|确认.*|继续.*|yes|ok|confirm|continue|done|submit|allow|accept)$", RegexOption.IGNORE_CASE)
    private val gestureCommit = Regex("滑动.*(支付|付款|转账|删除)|swipe to (pay|delete)|slide to (pay|confirm payment)", RegexOption.IGNORE_CASE)
    private fun risky(text: String) = !ordinaryLabel.containsMatchIn(text) && highRisk.containsMatchIn(text)

    private fun labels(node: UiNode, screen: Screen): List<String> {
        if (node.text.isNotBlank()) return listOf(node.text)
        var children = listOf(node)
        val result = mutableListOf<String>()
        repeat(3) {
            val ids = children.map { it.id }.toSet()
            children = screen.nodes.filter { it.parentId in ids }.take(20)
            result += children.map { it.text }.filter { it.isNotBlank() }
        }
        return result
    }

    fun requiresApproval(action: Action, screen: Screen): Boolean {
        val allText = screen.nodes.map { it.text } + screen.visual?.lines.orEmpty().map { it.text }
        val dangerousConfirmation = allText.any { !ordinaryLabel.containsMatchIn(it) && confirmationContext.containsMatchIn(it) }
        if (action is Action.Swipe) return allText.any { gestureCommit.containsMatchIn(it) }
        val point = when (action) {
            is Action.TapAt -> action.x to action.y
            is Action.LongPress -> action.x to action.y
            else -> null
        }
        if (point != null) {
            // A large ancestor can mention unrelated payments/deletion elsewhere on the page.
            val local = screen.nodes.filter { it.bounds?.contains(point.first, point.second) == true }
                .sortedBy { it.bounds!!.let { b -> (b.right - b.left).toLong() * (b.bottom - b.top) } }
            val target = local.firstOrNull { node -> node.clickable || node.bounds!!.let { b -> (b.right-b.left).toLong() * (b.bottom-b.top) <= 250000 } }
            val labels = target?.let { labels(it, screen) }.orEmpty() + screen.visual?.lines.orEmpty().filter { line ->
                val b = line.bounds
                Bounds(b.left - 8, b.top - 8, b.right + 8, b.bottom + 8).contains(point.first, point.second)
            }.map { it.text }
            if (labels.any(::risky)) return true
            // Unreadable coordinates alone no longer require confirmation.
            return dangerousConfirmation && (labels.none { it.isNotBlank() } || labels.any { confirm.matches(it.trim()) })
        }
        // Entering text, opening apps, back/home and scrolling do not commit a payment/deletion.
        if (action !is Action.Tap) return false
        val target = screen.nodes.singleOrNull { it.id == action.nodeId } ?: return false // Validator rejects invalid IDs first.
        val labels = labels(target, screen)
        if (labels.any(::risky)) return true
        return dangerousConfirmation && (labels.isEmpty() || labels.any { confirm.matches(it.trim()) })
    }
}
