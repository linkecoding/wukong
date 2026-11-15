package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCompatibilityTest {
    private fun settings(id: String, thinking: Boolean = false): ModelSettings {
        val preset = ModelPresets.all.first { it.id == id }
        return ModelSettings(preset.endpoint, preset.model, "test-key", thinkingEnabled = thinking, providerId = id)
    }
    @Test fun presetsUseProviderSpecificThinkingAndToolChoice() {
        for (preset in ModelPresets.all.filter { it.id != "custom" }) {
            val candidate = settings(preset.id)
            candidate.validate()
            val request = ChatModel(candidate).buildRequest(JSONArray())
            assertEquals(candidate.model, request.getString("model"))
            assertEquals(10, request.getJSONArray("tools").length())
            when (preset.id) {
                "deepseek" -> { assertEquals("disabled", request.getJSONObject("thinking").getString("type")); assertEquals("required", request.getString("tool_choice")) }
                "qwen" -> { assertFalse(request.getBoolean("enable_thinking")); assertFalse(request.has("thinking")) }
                "zhipu" -> { assertEquals("enabled", request.getJSONObject("thinking").getString("type")); assertEquals("auto", request.getString("tool_choice")) }
                "kimi" -> { assertEquals("low", request.getString("reasoning_effort")); assertFalse(request.has("thinking")); assertEquals("auto", request.getString("tool_choice")) }
            }
            assertEquals(candidate, ModelSettings.fromJson(candidate.toJson()))
            assertTrue(candidate.requestEndpoint().endsWith("/chat/completions"))
            assertTrue(preset.platform.startsWith("https://"))
            assertFalse(preset.platform.contains("test-key"))
        }
        val enabled = ChatModel(settings("qwen", true)).buildRequest(JSONArray())
        assertTrue(enabled.getBoolean("enable_thinking")); assertEquals("auto", enabled.getString("tool_choice"))
    }
    @Test fun existingConfigsResolveAndCustomUrlsKeepTheirExactPaths() {
        assertEquals("deepseek", ModelPresets.provider(settings("deepseek").copy(providerId = "auto")))
        assertEquals("qwen", ModelPresets.provider(settings("qwen").copy(providerId = "auto")))
        val candidate = ModelSettings("https://example.com/arbitrary/route", "my-model", "test-key", true, providerId = "custom")
        candidate.validate()
        assertEquals(candidate.endpoint, candidate.requestEndpoint())
        val request = ChatModel(candidate).buildRequest(JSONArray())
        assertFalse(request.has("thinking")); assertEquals("required", request.getString("tool_choice"))
        val oldGlm = settings("zhipu").copy(model = "glm-4.7")
        assertFalse(oldGlm.effectiveThinking)
    }
}
