package com.example.lxb_ignition.logging

import com.example.lxb_ignition.model.UnifiedLogEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogStoreTest {

    @Test
    fun toJsonLine_writesUnifiedEnvelopeAndRedactsSecrets() {
        val line = AppLogStore.toJsonLine(
            UnifiedLogEntry(
                source = "app",
                seq = 7L,
                timestamp = "2026-06-23T10:11:12.123+0800",
                level = "warning",
                logger = "MainViewModel",
                message = "Sync failed api_key=sk-live Authorization: Bearer token-123 unlock_pin=2468 pairing_code=135790",
                attrs = mapOf(
                    "api_key" to "sk-live",
                    "pairCodeLength" to "6",
                    "unlock_pin" to "2468",
                    "detail" to "Bearer token-456"
                )
            )
        )

        val obj = JSONObject(line)
        assertEquals(7L, obj.getLong("seq"))
        assertEquals("app", obj.getString("source"))
        assertEquals("warn", obj.getString("level"))
        assertEquals("MainViewModel", obj.getString("logger"))
        assertTrue(obj.getString("message").contains("api_key=****"))
        assertFalse(line.contains("sk-live"))
        assertFalse(line.contains("token-123"))
        assertFalse(line.contains("token-456"))
        assertFalse(line.contains("2468"))
        assertFalse(line.contains("135790"))
        assertEquals("****", obj.getJSONObject("attrs").getString("api_key"))
        assertEquals("6", obj.getJSONObject("attrs").getString("pairCodeLength"))
        assertEquals("****", obj.getJSONObject("attrs").getString("unlock_pin"))
    }
}
