package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PortableTaskRouteCodecTest {

    @SuppressWarnings("unchecked")
    @Test
    public void export_locatorBackedTap_preservesExistingLocatorPayload() {
        TaskMap map = baseMap();
        map.finishAfterReplay = true;
        TaskMap.Step step = tapStep("s0001", "a0001");
        step.xmlLocator.put("resource_id", "discover_tab");
        step.xmlLocator.put("class", "TextView");
        map.segments.get(0).steps.add(step);

        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = map.taskKeyHash;
        record.taskId = "task-1";
        record.rootTask = "??";
        record.packageName = "com.demo";
        record.source = "schedule";
        record.sourceId = "schedule-1";
        TaskRouteRecord.Action action = new TaskRouteRecord.Action();
        action.actionId = "a0001";
        action.op = "TAP";
        action.rawCommand = "TAP 100 200";
        action.createdPageSemantics = "当前页面是首页";
        record.actions.add(action);

        PortableTaskRouteCodec.ExportResult exported = PortableTaskRouteCodec.exportPortable(map, record);
        Map<String, Object> stepRow = firstStep(exported.bundle);

        Map<String, Object> taskInfo = (Map<String, Object>) exported.bundle.get("task_info");
        Assert.assertEquals("??", taskInfo.get("user_task"));
        Assert.assertEquals("com.demo", taskInfo.get("package_name"));
        Assert.assertEquals(Boolean.TRUE, exported.bundle.get("finish_after_replay"));
        Assert.assertFalse("portable bundle must not carry export/run timestamps", exported.bundle.containsKey("created_at_ms"));
        Assert.assertFalse("portable bundle must not duplicate task_info as source_task", exported.bundle.containsKey("source_task"));
        Assert.assertFalse("portable task_info must not carry timestamps", taskInfo.containsKey("created_at_ms"));
        Assert.assertFalse("portable task_info must not carry schedule/source ids", taskInfo.containsKey("source_id"));
        Assert.assertFalse("portable task_info must not carry source task ids", taskInfo.containsKey("task_id"));
        Assert.assertFalse("portable task_info must not carry route ids", taskInfo.containsKey("route_id"));
        Assert.assertEquals(PortableTaskRouteCodec.PORTABLE_SCHEMA, exported.bundle.get("schema"));
        Assert.assertEquals("discover_tab", ((Map<String, Object>) stepRow.get("xml_locator")).get("resource_id"));
        Assert.assertFalse(stepRow.containsKey("portable_kind"));
        Assert.assertEquals("当前页面是首页", ((Map<String, Object>) stepRow.get("semantic_locator")).get("instruction"));
        Assert.assertEquals(1, exported.xmlLocatorStepCount);
        Assert.assertEquals(1, exported.semanticLocatorStepCount);
    }

    @Test
    public void export_semanticLocatorWithoutXmlLocator_buildsSemanticLocatorPayload() {
        TaskMap map = baseMap();
        TaskMap.Step step = tapStep("s0001", "a0001");
        step.semanticNote = "当前页面是贴吧首页";
        step.expected = "进入发布帖子页面";
        map.segments.get(0).steps.add(step);

        TaskRouteRecord record = new TaskRouteRecord();
        TaskRouteRecord.Action action = new TaskRouteRecord.Action();
        action.actionId = "a0001";
        action.op = "TAP";
        action.rawCommand = "TAP 930 920";
        action.createdPageSemantics = "右下角有一个加号按钮";
        record.actions.add(action);

        PortableTaskRouteCodec.ExportResult exported = PortableTaskRouteCodec.exportPortable(map, record);
        Map<String, Object> stepRow = firstStep(exported.bundle);

        Assert.assertFalse(stepRow.containsKey("portable_kind"));
        Assert.assertFalse(stepRow.containsKey("xml_locator"));
        Assert.assertFalse(stepRow.containsKey("tap_point"));
        Assert.assertFalse(stepRow.containsKey("container_probe"));
        Assert.assertTrue(stepRow.containsKey("semantic_locator"));
        Assert.assertEquals("当前页面是贴吧首页", ((Map<String, Object>) stepRow.get("semantic_locator")).get("instruction"));
        Assert.assertEquals(0, exported.xmlLocatorStepCount);
        Assert.assertEquals(1, exported.semanticLocatorStepCount);
    }

    @Test
    public void import_semanticLocator_containsXmlAndSemanticLocators() {
        String bundleJson = "{"
                + "\"schema\":\"task_route_asset.v1\","
                + "\"finish_after_replay\":true,"
                + "\"bundle_id\":\"b1\","
                + "\"created_at_ms\":1,"
                + "\"task_info\":{\"task_id\":\"source-task\",\"route_id\":\"source\",\"package_name\":\"com.demo\",\"package_label\":\"Demo\",\"user_task\":\"post\"},"
                + "\"segments\":[{\"segment_id\":\"seg0001\",\"sub_task_id\":\"default\",\"sub_task_index\":0,"
                + "\"sub_task_description\":\"发帖\",\"success_criteria\":\"\",\"package_name\":\"com.demo\",\"package_label\":\"Demo\","
                + "\"inputs\":[],\"outputs\":[],"
                + "\"steps\":[{\"step_id\":\"s0001\",\"source_action_id\":\"a0001\",\"op\":\"TAP\",\"args\":[],"
                + "\"xml_locator\":{\"resource_id\":\"publish_button\",\"class\":\"Button\"},"
                + "\"semantic_locator\":{\"instruction\":\"点击发布帖子入口\",\"expected_after_tap\":\"进入发布页面\"},"
                + "\"expected\":\"进入发布页面\"}]}]}";

        PortableTaskRouteCodec.ImportResult imported = PortableTaskRouteCodec.importPortable("target-hash", "com.demo", bundleJson);
        TaskMap.Step step = imported.map.segments.get(0).steps.get(0);

        Assert.assertEquals("target-hash", imported.map.taskKeyHash);
        Assert.assertTrue(imported.map.finishAfterReplay);
        Assert.assertEquals("source-task", imported.taskInfo.get("task_id"));
        Assert.assertEquals("post", imported.taskInfo.get("user_task"));
        Assert.assertEquals("publish_button", step.xmlLocator.get("resource_id"));
        Assert.assertEquals("Button", step.xmlLocator.get("class"));
        Assert.assertEquals("点击发布帖子入口", step.semanticLocator.get("instruction"));
        Assert.assertEquals("进入发布页面", step.semanticLocator.get("expected_after_tap"));
        Assert.assertEquals(1, imported.xmlLocatorStepCount);
        Assert.assertEquals(1, imported.semanticLocatorStepCount);
    }

    @Test
    public void export_swipe_rejectedInV1() {
        TaskMap map = baseMap();
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "SWIPE";
        step.swipe.put("start", java.util.Arrays.asList(1, 2));
        step.swipe.put("end", java.util.Arrays.asList(3, 4));
        map.segments.get(0).steps.add(step);
        try {
            PortableTaskRouteCodec.exportPortable(map, null);
            Assert.fail("expected swipe export rejection");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(String.valueOf(e.getMessage()).contains("unsupported_portable_op:SWIPE"));
        }
    }

    private static TaskMap baseMap() {
        TaskMap map = new TaskMap();
        map.taskKeyHash = "hash";
        map.packageName = "com.demo";
        map.packageLabel = "Demo";
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.subTaskId = "default";
        segment.subTaskIndex = 0;
        segment.subTaskDescription = "发帖";
        segment.packageName = "com.demo";
        segment.packageLabel = "Demo";
        map.segments.add(segment);
        return map;
    }

    private static TaskMap.Step tapStep(String stepId, String actionId) {
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = stepId;
        step.sourceActionId = actionId;
        step.op = "TAP";
        return step;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstStep(Map<String, Object> bundle) {
        List<Object> segments = (List<Object>) bundle.get("segments");
        Map<String, Object> segment = (Map<String, Object>) segments.get(0);
        List<Object> steps = (List<Object>) segment.get("steps");
        return (Map<String, Object>) steps.get(0);
    }
}
