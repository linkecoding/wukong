package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandaloneCapabilitiesTest {
    @Test fun unavailableToolsAreNeitherOfferedNorAccepted() {
        val offered=setOf("phone_action","phone_query","phone_wait_until","phone_verify","phone_screenshot","phone_tap_at","phone_swipe","phone_long_press")
        val model=ChatModel(ModelSettings("https://example.invalid","fixture","fixture-not-a-provider-key"),mode=PlanningMode.TOOLS_ONLY,availableTools=offered)
        val tools=model.buildRequest(JSONArray()).getJSONArray("tools")
        val names=(0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        assertEquals(offered,names)
        for(name in listOf("phone_run_js","phone_ocr")) {
            val message=JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("id","test").put("type","function")
                .put("function",JSONObject().put("name",name).put("arguments","{}"))))
            try { model.parseToolCall(message);fail("Unsupported tool accepted") } catch(_:IllegalArgumentException) { }
        }
    }
}
