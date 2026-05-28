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

        TaskMap restored = TaskMap.fromObject(map.toMap());
        Assert.assertNotNull(restored);
        Assert.assertEquals(TaskMap.SCHEMA_V2, restored.schema);
        Assert.assertEquals("tap publish", restored.segments.get(0).steps.get(0).history.get("instruction"));
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
    public void fromObject_restoresPortableAdaptationFields() {
        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash";
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.portableKind = PortableTaskRouteCodec.PORTABLE_KIND_SEMANTIC_TAP;
        step.semanticDescriptor.put("instruction", "点击发布帖子入口");
        step.adaptationStatus = PortableTaskRouteCodec.ADAPTATION_STATUS_FAILED;
        step.adaptationError = "no_match";
        step.materializedFromStepId = "s0001";
        step.materializedAtMs = 8L;
        segment.steps.add(step);
        map.segments.add(segment);

        TaskMap restored = TaskMap.fromObject(map.toMap());

        Assert.assertNotNull(restored);
        Assert.assertEquals(PortableTaskRouteCodec.PORTABLE_KIND_SEMANTIC_TAP, restored.segments.get(0).steps.get(0).portableKind);
        Assert.assertEquals("点击发布帖子入口", restored.segments.get(0).steps.get(0).semanticDescriptor.get("instruction"));
        Assert.assertEquals("no_match", restored.segments.get(0).steps.get(0).adaptationError);
        Assert.assertEquals(8L, restored.segments.get(0).steps.get(0).materializedAtMs);
    }
}
