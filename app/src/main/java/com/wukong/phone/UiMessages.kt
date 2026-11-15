package com.wukong.phone

import android.content.Context

/** Translate presentation of known engine statuses; never rewrite protocol messages. */
object UiMessages {
    private val resources = mapOf(
        "正在核对结果中的计算" to R.string.status_check_calculations,
        "操作未执行，正在重新观察并调整" to R.string.onboarding_action_replan,
        "先开启无线调试，再配对悟空" to R.string.status_01,
        "已找到本机配对端口，可从通知栏输入配对码" to R.string.status_02,
        "已找到本机无线调试连接" to R.string.status_03,
        "请输入六位数字配对码" to R.string.status_04,
        "还未发现配对窗口，请在系统无线调试中打开“使用配对码配对设备”" to R.string.status_05,
        "正在与这台手机配对…" to R.string.status_06,
        "配对成功，正在查找连接端口…" to R.string.status_07,
        "配对已完成；请回到悟空填写无线调试主页的连接端口" to R.string.status_08,
        "未发现连接端口，请填写无线调试主页的端口" to R.string.status_09,
        "正在连接本机无线调试…" to R.string.status_10,
        "激活成功 · 可以开始手机任务" to R.string.status_11,
        "正在处理上一次激活请求" to R.string.status_12,
        "未能完成配对或激活。请确认无线调试开启、端口正确；配对码过期时重新打开系统配对窗口。" to R.string.status_13,
        "请输入有效配对端口和六位配对码" to R.string.status_14,
        "无线调试连接失败" to R.string.status_15,
        "设备返回内容过大" to R.string.status_16,
        "上传执行引擎失败" to R.string.status_17,
        "无线调试没有提供 shell 身份" to R.string.status_18,
        "正在准备手机执行引擎…" to R.string.status_19,
        "执行引擎校验失败，请重试" to R.string.status_20,
        "正在启动执行引擎…" to R.string.status_21,
        "执行引擎未连接，请重试激活" to R.string.status_22,
        "请输入 1–4000 字的任务" to R.string.status_23,
        "请等待执行引擎激活完成" to R.string.status_24,
        "请先允许悟空显示悬浮控制条" to R.string.status_25,
        "无法打开这个应用" to R.string.status_26,
        "请先启动虚拟屏，等待画面就绪" to R.string.status_27,
        "正在准备整机任务" to R.string.status_28,
        "任务启动失败" to R.string.status_29,
        "无法启动任务控制条" to R.string.status_30,
        "未能读取界面，请确认手机已解锁" to R.string.status_31,
        "执行失败" to R.string.status_32,
        "你已接管，Agent 已暂停" to R.string.status_33,
        "已交还 Agent，正在重新读取界面" to R.string.status_34,
        "整机任务" to R.string.status_35,
        "虚拟屏未启动" to R.string.status_36,
        "准备好后，把任务交给悟空" to R.string.status_37,
        "请输入 HTTPS 接口地址，不含账号、查询参数或片段" to R.string.status_38,
        "请填写模型名称" to R.string.status_39,
        "请填写有效的 API Key" to R.string.status_40,
        "接口连接和工具调用测试成功，未执行设备动作" to R.string.status_41,
        "无法连接模型服务，请检查网络或稍后重试" to R.string.status_42,
        "请检查 Base URL 和完整请求地址选项" to R.string.status_43,
        "请检查接口、模型和请求参数" to R.string.status_44,
        "请求已取消" to R.string.status_45,
        "模型响应过大" to R.string.status_46,
        "模型未返回 phone_action 工具调用，请使用支持工具调用的模型" to R.string.status_47,
        "模型必须每次只返回一个操作" to R.string.status_48,
        "模型返回未知操作" to R.string.status_49,
        "模型返回未知工具" to R.string.status_50,
        "模型未按要求完成工具调用测试，没有执行设备动作" to R.string.status_51
    )
    fun text(context: Context, value: String): String {
        resources[value]?.let { return context.getString(it) }
        if (UiLanguage.effectiveCode(context) == "en" && value.startsWith("模型请求失败（HTTP ")) {
            val parts = Regex("^模型请求失败（HTTP ([0-9]+)）：(.*)$").matchEntire(value)
            if (parts != null) return "Model request failed (HTTP ${parts.groupValues[1]}): ${text(context, parts.groupValues[2])}"
        }
        return value
    }
}
