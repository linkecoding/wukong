package com.wukong.phone

import java.net.URI

/** Official standard API endpoints, checked 2026-11-15. No subscription/coding-plan keys. */
data class ModelPreset(val id: String, val title: Int, val endpoint: String, val model: String, val platform: String)
object ModelPresets {
    val all = listOf(
        ModelPreset("deepseek", R.string.provider_deepseek, "https://api.deepseek.com", "deepseek-flash", "https://platform.deepseek.com/api_keys"),
        ModelPreset("qwen", R.string.provider_qwen, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key"),
        ModelPreset("zhipu", R.string.provider_zhipu, "https://open.bigmodel.cn/api/paas/v4", "glm-5.3-flash", "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"),
        ModelPreset("kimi", R.string.provider_kimi, "https://api.moonshot.cn/v1", "kimi-k3.1-pro", "https://platform.kimi.com/console/api-keys"),
        ModelPreset("custom", R.string.setup_custom, "", "", "")
    )
    fun provider(settings: ModelSettings): String {
        if (settings.providerId != "auto") return settings.providerId
        val host = runCatching { URI(settings.endpoint).host.orEmpty().lowercase(java.util.Locale.ROOT) }.getOrDefault("")
        return when {
            host == "api.deepseek.com" -> "deepseek"
            host == "dashscope.aliyuncs.com" || host.endsWith(".maas.aliyuncs.com") || host == "dashscope-intl.aliyuncs.com" -> "qwen"
            host == "open.bigmodel.cn" -> "zhipu"
            host == "api.moonshot.cn" || host == "api.moonshot.ai" -> "kimi"
            else -> "custom"
        }
    }
    fun thinkingRequired(settings: ModelSettings): Boolean = when (provider(settings)) {
        "zhipu" -> settings.model.lowercase().startsWith("glm-5.3")
        "kimi" -> settings.model.lowercase().startsWith("kimi-k3")
        else -> false
    }
}
