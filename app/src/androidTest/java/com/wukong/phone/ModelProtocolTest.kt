package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelProtocolTest {
    @Test fun thinkingChoiceSurvivesSerializationAndUsesCompatibleToolSelection() {
        val original = ModelSettings("https://api.deepseek.com", "deepseek-flash", "test-key")
        for (enabled in listOf(true, false)) {
            val saved = original.copy(thinkingEnabled = enabled)
            val restored = ModelSettings.fromJson(JSONObject(saved.toJson().toString()))
            assertEquals(saved, restored)
            val request = ChatModel(restored).buildRequest(JSONArray())
            assertEquals(if (enabled) "enabled" else "disabled", request.getJSONObject("thinking").getString("type"))
            if (enabled) assertEquals("auto", request.getString("tool_choice"))
            else assertEquals("required", request.getString("tool_choice"))
            assertEquals(6, request.getJSONArray("tools").length())
        }
        val legacy = original.toJson().apply { remove("thinkingEnabled") }
        assertFalse(original.thinkingEnabled)
        assertFalse(ModelSettings.fromJson(legacy).thinkingEnabled)
    }
}
