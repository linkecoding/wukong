package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wukong.core.*
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
            assertEquals(10, request.getJSONArray("tools").length())
        }
        val legacy = original.toJson().apply { remove("thinkingEnabled") }
        assertFalse(original.thinkingEnabled)
        assertFalse(ModelSettings.fromJson(legacy).thinkingEnabled)
    }
    @Test fun malformedCallsReturnReplanActionsAndUnambiguousConditionShorthandNormalizes() {
        val model = ChatModel(ModelSettings("https://example.com/v1", "fixture", "fixture-key"))
        fun call(name: String, args: String) = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "fixture-call").put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args))))
        for (args in listOf("not-json", "{\"selector\":{\"recourceId\":\"demo:id/x\"}}", "{\"selector\":\"demo:id/x\"}")) {
            val action = model.parseToolCall(call("phone_query", args))
            assertTrue(action is Action.Invalid); assertTrue((action as Action.Invalid).reason.contains("INVALID_ARGUMENT"))
        }
        val wait = model.parseToolCall(call("phone_wait_until", """{"conditions":[{"resourceId":"demo.app:id/result","textContains":"Ready"}],"timeoutMs":5000}""")) as Action.WaitUntil
        assertEquals("demo.app:id/result", wait.conditions.single().selector!!.resourceId)
        val finish = model.parseToolCall(call("phone_action", """{"action":"finish","summary":"done","evidence":[{"textContains":"Ready"}]}""")) as Action.Finish
        assertEquals("Ready", finish.evidence.single().selector!!.textContains)
        assertTrue(model.parseToolCall(call("phone_wait_until", """{"conditions":[{"selector":{"textContains":"Ready"},"timeuotMs":5}]}""")) is Action.Invalid)
    }

}
