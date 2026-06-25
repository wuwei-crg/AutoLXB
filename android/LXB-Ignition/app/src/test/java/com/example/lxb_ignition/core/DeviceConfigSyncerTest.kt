package com.example.lxb_ignition.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceConfigSyncerTest {

    @Test
    fun buildConfigJsonObject_writesRequestTypeAndTrimsBaseFields() {
        val obj = DeviceConfigSyncer.buildConfigJsonObject(
            DeviceLlmSettings(
                apiBaseUrl = " https://generativelanguage.googleapis.com/v1beta ",
                apiKey = "key",
                model = " gemini-2.0-flash ",
                requestType = "gemini_generate_content",
                autoUnlockBeforeRoute = true,
                autoLockAfterTask = false,
                unlockPin = " 1234 ",
                useMap = true,
                mapSource = "stable",
                taskDndMode = "none",
                maxTaskSteps = 100,
                providers = listOf(
                    DeviceLlmProviderSettings(
                        providerId = " semantic ",
                        name = " Semantic ",
                        apiBaseUrl = " https://semantic.test/v1 ",
                        apiKey = "semantic-key",
                        model = " semantic-model ",
                        requestType = "openai_chat_completions",
                        updatedAt = 10L
                    ),
                    DeviceLlmProviderSettings(
                        providerId = "vision",
                        name = "Vision",
                        apiBaseUrl = "https://vision.test/v1",
                        apiKey = "vision-key",
                        model = "vision-model",
                        requestType = "anthropic_messages",
                        updatedAt = 20L
                    )
                ),
                activeProviderId = "semantic",
                routingMode = "split",
                unifiedProviderId = "semantic",
                semanticLocatorProviderId = "semantic",
                visionActProviderId = "vision"
            )
        )

        assertEquals("https://generativelanguage.googleapis.com/v1beta", obj.getString("api_base_url"))
        assertEquals("key", obj.getString("api_key"))
        assertEquals("gemini-2.0-flash", obj.getString("model"))
        assertEquals("gemini_generate_content", obj.getString("request_type"))
        assertEquals("1234", obj.getString("unlock_pin"))
        assertEquals("semantic", obj.getString("active_provider_id"))

        val providers = obj.getJSONArray("providers")
        assertEquals(2, providers.length())
        assertEquals("semantic", providers.getJSONObject(0).getString("provider_id"))
        assertEquals("Semantic", providers.getJSONObject(0).getString("name"))
        assertEquals("https://semantic.test/v1", providers.getJSONObject(0).getString("api_base_url"))
        assertEquals("semantic-model", providers.getJSONObject(0).getString("model"))

        val routing = obj.getJSONObject("model_routing")
        assertEquals("split", routing.getString("mode"))
        val scriptAction = routing.getJSONObject("script_action")
        assertEquals("semantic", scriptAction.getString("unified_provider_id"))
        assertEquals("semantic", scriptAction.getString("semantic_locator_provider_id"))
        assertEquals("vision", scriptAction.getString("vision_act_provider_id"))
    }
}
