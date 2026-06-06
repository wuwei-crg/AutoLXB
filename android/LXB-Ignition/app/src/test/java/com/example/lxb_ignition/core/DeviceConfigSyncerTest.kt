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
                maxTaskSteps = 100
            )
        )

        assertEquals("https://generativelanguage.googleapis.com/v1beta", obj.getString("api_base_url"))
        assertEquals("key", obj.getString("api_key"))
        assertEquals("gemini-2.0-flash", obj.getString("model"))
        assertEquals("gemini_generate_content", obj.getString("request_type"))
        assertEquals("1234", obj.getString("unlock_pin"))
    }
}
