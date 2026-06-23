package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class TraceLoggerTest {

    @Test
    public void event_withoutExplicitEnvelopeAddsLoggerLevelAndMessage() {
        TraceLogger trace = new TraceLogger(8);

        emit(trace, "list_apps_empty", null);

        Map<String, Object> row = lastTraceObject(trace);
        Assert.assertEquals("TraceLoggerTest", row.get("logger"));
        Assert.assertEquals("warn", row.get("level"));
        Assert.assertEquals("list_apps_empty", row.get("message"));
        Assert.assertEquals("list_apps_empty", row.get("event"));
        Assert.assertTrue(String.valueOf(row.get("ts")).length() > 10);
    }

    @Test
    public void event_withErrorFieldInfersErrorLevel() {
        TraceLogger trace = new TraceLogger(8);
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("error", "boom");

        emit(trace, "custom_trace", fields);

        Map<String, Object> row = lastTraceObject(trace);
        Assert.assertEquals("TraceLoggerTest", row.get("logger"));
        Assert.assertEquals("error", row.get("level"));
        Assert.assertEquals("custom_trace", row.get("message"));
        Assert.assertEquals("boom", row.get("error"));
    }

    @Test
    public void event_withExplicitEnvelopePreservesProvidedValues() {
        TraceLogger trace = new TraceLogger(8);
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("logger", "ExecutionEngine");
        fields.put("level", "info");
        fields.put("message", "List apps request started");
        fields.put("filter", 1);

        emit(trace, "list_apps_start", fields);

        Map<String, Object> row = lastTraceObject(trace);
        Assert.assertEquals("ExecutionEngine", row.get("logger"));
        Assert.assertEquals("info", row.get("level"));
        Assert.assertEquals("List apps request started", row.get("message"));
        Assert.assertEquals(1, row.get("filter"));
    }

    private static void emit(TraceLogger trace, String event, Map<String, Object> fields) {
        trace.event(event, fields);
    }

    private static Map<String, Object> lastTraceObject(TraceLogger trace) {
        TraceLogger.PullPage page = trace.pullTail(1);
        Assert.assertEquals(1, page.items.size());
        return Json.parseObject(page.items.get(0).line);
    }
}
