package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class TaskMapTest {

    @Test
    public void fromObject_restoresFinishAfterReplayFlag() {
        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash";
        map.finishAfterReplay = true;

        TaskMap restored = TaskMap.fromObject(map.toMap());

        Assert.assertNotNull(restored);
        Assert.assertTrue(restored.finishAfterReplay);
    }

    @Test
    public void newMapsDefaultToV2AndRoundTripHistory() {
        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash";
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.history.put("instruction", "tap publish");
        step.history.put("expected", "publish page opens");
        step.xmlLocator.put("resource_id", "publish_button");
        step.semanticLocator.put("instruction", "tap publish");
        segment.steps.add(step);
        map.segments.add(segment);

        @SuppressWarnings("unchecked")
        java.util.List<Object> segments = (java.util.List<Object>) map.toMap().get("segments");
        @SuppressWarnings("unchecked")
        Map<String, Object> serializedSegment = (Map<String, Object>) segments.get(0);
        @SuppressWarnings("unchecked")
        java.util.List<Object> steps = (java.util.List<Object>) serializedSegment.get("steps");
        @SuppressWarnings("unchecked")
        Map<String, Object> serializedStep = (Map<String, Object>) steps.get(0);

        Assert.assertEquals(TaskMap.SCHEMA_V2, map.toMap().get("schema"));
        Assert.assertTrue(serializedStep.containsKey("history"));
        Assert.assertTrue(serializedStep.containsKey("xml_locator"));
        Assert.assertTrue(serializedStep.containsKey("semantic_locator"));

        TaskMap restored = TaskMap.fromObject(map.toMap());
        Assert.assertNotNull(restored);
        Assert.assertEquals(TaskMap.SCHEMA_V2, restored.schema);
        Assert.assertEquals("tap publish", restored.segments.get(0).steps.get(0).history.get("instruction"));
        Assert.assertEquals("publish_button", restored.segments.get(0).steps.get(0).xmlLocator.get("resource_id"));
        Assert.assertEquals("tap publish", restored.segments.get(0).steps.get(0).semanticLocator.get("instruction"));
    }

    @Test
    public void fromObject_missingSchemaDefaultsToV1() {
        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash";
        Map<String, Object> raw = map.toMap();
        raw.remove("schema");

        TaskMap restored = TaskMap.fromObject(raw);

        Assert.assertNotNull(restored);
        Assert.assertEquals(TaskMap.SCHEMA_V1, restored.schema);
    }

    @Test
    public void fromObject_acceptsLegacyLocatorFieldNames() {
        Map<String, Object> raw = new java.util.LinkedHashMap<String, Object>();
        raw.put("schema", TaskMap.SCHEMA_V2);
        raw.put("route_id", "hash");
        raw.put("mode", "ai");
        raw.put("last_replay_status", "unused");

        Map<String, Object> segment = new java.util.LinkedHashMap<String, Object>();
        segment.put("segment_id", "seg0001");
        segment.put("package_name", "com.demo");

        Map<String, Object> step = new java.util.LinkedHashMap<String, Object>();
        step.put("step_id", "s0001");
        step.put("op", "TAP");
        step.put("locator", java.util.Collections.singletonMap("resource_id", "publish_button"));
        step.put("semantic_descriptor", java.util.Collections.singletonMap("instruction", "点击发布帖子入口"));
        segment.put("steps", java.util.Collections.singletonList(step));

        raw.put("segments", java.util.Collections.singletonList(segment));

        TaskMap restored = TaskMap.fromObject(raw);

        Assert.assertNotNull(restored);
        Assert.assertEquals("publish_button", restored.segments.get(0).steps.get(0).xmlLocator.get("resource_id"));
        Assert.assertEquals("点击发布帖子入口", restored.segments.get(0).steps.get(0).semanticLocator.get("instruction"));
    }
}
