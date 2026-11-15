package com.wukong.phone

import org.junit.Assert.*
import org.junit.Test

class ModelSettingsTest {
    @Test fun baseUrlsResolveToChatApiWithTheirPrefixPreserved() {
        val cases = mapOf(
            "https://api.deepseek.com" to "https://api.deepseek.com/chat/completions",
            "https://api.deepseek.com/" to "https://api.deepseek.com/chat/completions",
            "https://example.com/v1" to "https://example.com/v1/chat/completions",
            "https://example.com/proxy/custom/" to "https://example.com/proxy/custom/chat/completions"
        )
        for ((input, expected) in cases) {
            val settings = ModelSettings(input, "test-model", "test-key")
            settings.validate()
            assertEquals(expected, settings.requestEndpoint())
        }
    }

    @Test fun fullRequestUrlsAcceptAnyPathWithoutAppendingAnything() {
        for (url in listOf("https://example.com/custom", "https://example.com/custom/", "https://api.deepseek.com/chat/completions")) {
            val settings = ModelSettings(url, "test-model", "test-key", useFullEndpoint = true)
            settings.validate()
            assertEquals(url, settings.requestEndpoint())
        }
    }

    @Test fun modelDiscoveryUsesTheExactBasePrefixAndOnlyKnownFullRoutes() {
        for (base in listOf("https://example.com", "https://example.com/v1/", "https://example.com/odd/prefix")) {
            assertEquals(base.trimEnd('/') + "/models", ModelSettings(base, "", "key").modelsEndpoint())
        }
        assertEquals("https://example.com/odd/models", ModelSettings("https://example.com/odd/chat/completions/", "", "key", true).modelsEndpoint())
        assertNull(ModelSettings("https://example.com/custom-completion", "", "key", true).modelsEndpoint())
    }

    @Test fun legacyConfigurationKeepsCustomRoutesAndRecognizesCommonBases() {
        assertFalse(ModelSettings.legacyUsesFullEndpoint("https://api.deepseek.com/"))
        assertFalse(ModelSettings.legacyUsesFullEndpoint("https://example.com/v1/"))
        assertTrue(ModelSettings.legacyUsesFullEndpoint("https://example.com/v1/chat/completions"))
        assertTrue(ModelSettings.legacyUsesFullEndpoint("https://example.com/custom"))
    }
}
