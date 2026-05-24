package com.example.lxb_ignition.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceEventMapperTest {

    @Test
    fun map_fsmStateEnter_appResolve() {
        val obj = JSONObject()
            .put("event", "fsm_state_enter")
            .put("state", "APP_RESOLVE")
            .put("task_id", "tid-1")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertEquals("fsm_state_enter", mapped?.event)
        assertEquals("tid-1", mapped?.taskId)
        assertTrue(mapped?.messages?.any { it.contains("APP_RESOLVE") } == true)
        assertEquals("APP_RESOLVE", mapped?.runtimeUpdate?.phase)
        assertEquals(false, mapped?.runtimeUpdate?.stopAfter)
    }

    @Test
    fun map_failureEvent_setsStopRuntime() {
        val obj = JSONObject()
            .put("event", "fsm_app_enter_failed")
            .put("reason", "launch failed")
            .put("task_id", "tid-2")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.joinToString(" ")?.contains("APP_ENTER failed") == true)
        assertEquals("FAILED", mapped?.runtimeUpdate?.phase)
        assertTrue(mapped?.runtimeUpdate?.stopAfter == true)
    }

    @Test
    fun map_emptyEvent_returnsNull() {
        val obj = JSONObject().put("event", "")
        val mapped = TraceEventMapper.map(obj)
        assertNull(mapped)
    }

    @Test
    fun map_unknownEvent_returnsNonNullWithoutRuntime() {
        val obj = JSONObject().put("event", "unknown_event")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertEquals("unknown_event", mapped?.event)
        assertTrue(mapped?.messages?.isEmpty() == true)
        assertNull(mapped?.runtimeUpdate)
    }


    @Test
    fun map_fsmStateEnter_devicePrepare() {
        val obj = JSONObject()
            .put("event", "fsm_state_enter")
            .put("state", "DEVICE_PREPARE")
            .put("task_id", "tid-device")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.any { it.contains("DEVICE_PREPARE") } == true)
        assertEquals("DEVICE_PREPARE", mapped?.runtimeUpdate?.phase)
    }

    @Test
    fun map_fsmStateEnter_appEnter() {
        val obj = JSONObject()
            .put("event", "fsm_state_enter")
            .put("state", "APP_ENTER")
            .put("task_id", "tid-app")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.any { it.contains("APP_ENTER") } == true)
        assertEquals("APP_ENTER", mapped?.runtimeUpdate?.phase)
    }

    @Test
    fun map_fsmStateEnter_scriptAct() {
        val obj = JSONObject()
            .put("event", "fsm_state_enter")
            .put("state", "SCRIPT_ACT")
            .put("task_id", "tid-script")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.any { it.contains("SCRIPT_ACT") } == true)
        assertEquals("SCRIPT_ACT", mapped?.runtimeUpdate?.phase)
    }

    @Test
    fun map_scriptActResult_replayed() {
        val obj = JSONObject()
            .put("event", "fsm_script_act_result")
            .put("task_id", "tid-script")
            .put("result", "REPLAYED")
            .put("steps", 2)

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.joinToString(" ")?.contains("REPLAYED") == true)
        assertEquals("SCRIPT_ACT", mapped?.runtimeUpdate?.phase)
    }

    @Test
    fun map_scriptActResult_doneStopsRuntime() {
        val obj = JSONObject()
            .put("event", "fsm_script_act_result")
            .put("task_id", "tid-script")
            .put("result", "DONE")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.joinToString(" ")?.contains("DONE") == true)
        assertEquals("DONE", mapped?.runtimeUpdate?.phase)
        assertTrue(mapped?.runtimeUpdate?.stopAfter == true)
    }

    @Test
    fun map_scriptActResult_fallbackVision() {
        val obj = JSONObject()
            .put("event", "fsm_script_act_result")
            .put("task_id", "tid-script")
            .put("result", "FALLBACK_VISION")
            .put("reason", "semantic_adaptation_failed")

        val mapped = TraceEventMapper.map(obj)

        assertNotNull(mapped)
        assertTrue(mapped?.messages?.joinToString(" ")?.contains("FALLBACK_VISION") == true)
        assertEquals("VISION_ACT", mapped?.runtimeUpdate?.phase)
    }

}
