package com.wukong.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunLogsTest {
    @Test fun logSwitchRedactionTokenDetailsAndRetentionWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RunLogStore(context, "-test")
        store.clear(); store.enabled = true
        try {
            val settings = ModelSettings("https://example.com", "test-model", "secret-\"test\\key")
            val log = store.start("test", "log test", settings)!!
            log.step = 2
            log.record("tool_call", JSONObject().put("arguments", JSONObject().put("text", settings.apiKey)))
            log.record("model_response", JSONObject().put("usage", JSONObject().put("prompt_tokens", 100).put("completion_tokens", 12).put("total_tokens", 112).put("prompt_cache_hit_tokens", 50)))
            val before = store.read(log.name)
            assertFalse(before.toString().contains(settings.apiKey))
            assertEquals("[已隐藏]", before[1].getJSONObject("data").getJSONObject("arguments").getString("text"))
            assertEquals(2, before[1].getJSONObject("data").getInt("step"))
            assertEquals(112, before[2].getJSONObject("data").getJSONObject("usage").getInt("total_tokens"))
            store.enabled = false
            log.record("must_not_save")
            assertEquals(before.size, store.read(log.name).size)
            assertNull(store.start("test", "disabled", settings))
            store.enabled = true
            log.record("model_response", JSONObject().put("usage", JSONObject.NULL))
            assertTrue(store.read(log.name).last().getJSONObject("data").isNull("usage"))
            repeat(24) { store.start("test", "retention", settings) }
            assertEquals(20, store.files().size)
            val limited = store.start("test", "size-limit", settings)!!
            repeat(100) { limited.record("large", JSONObject().put("text", "x".repeat(10000))) }
            assertTrue(store.files().single { it.name == limited.name }.length() <= 512 * 1024)
        } finally { store.clear(); store.enabled = true }
    }
}
