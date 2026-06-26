package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class TaskMapAssemblerTest {

    @Test
    public void assemble_dropsDeletedAndNonReplayable() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "open something";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.args.add("100");
        tap.args.add("200");
        tap.locator.put("resource_id", "search_box");
        tap.locator.put("class", "TextView");
        record.actions.add(tap);

        TaskRouteRecord.Action input = new TaskRouteRecord.Action();
        input.actionId = "a0002";
        input.subTaskId = "default";
        input.op = "INPUT";
        input.args.add("secret");
        record.actions.add(input);

        TaskRouteRecord.Action wait = new TaskRouteRecord.Action();
        wait.actionId = "a0003";
        wait.subTaskId = "default";
        wait.op = "WAIT";
        wait.args.add("500");
        record.actions.add(wait);

        Set<String> deleteIds = new HashSet<String>();
        deleteIds.add("a0003");
        TaskMap map = TaskMapAssembler.assemble(record, deleteIds, "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(TaskMap.SCHEMA_V2, map.schema);
        Assert.assertEquals(1, map.segments.size());
        Assert.assertEquals(2, map.stepCount());
        Assert.assertEquals("TAP", map.segments.get(0).steps.get(0).op);
        Assert.assertEquals("search_box", map.segments.get(0).steps.get(0).xmlLocator.get("resource_id"));
        Assert.assertFalse(map.segments.get(0).steps.get(0).semanticLocator.isEmpty());
        Assert.assertEquals("INPUT", map.segments.get(0).steps.get(1).op);
        Assert.assertEquals("secret", map.segments.get(0).steps.get(1).args.get(0));
    }

    @Test
    public void assemble_buildsFallbackSemanticLocatorForPureCoordinateTap() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "open something";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.args.add("100");
        tap.args.add("200");
        tap.tapPoint.add(100);
        tap.tapPoint.add(200);
        record.actions.add(tap);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(1, map.stepCount());
        TaskMap.Step step = map.segments.get(0).steps.get(0);
        Assert.assertTrue(step.xmlLocator.isEmpty());
        Assert.assertFalse(step.semanticLocator.isEmpty());
        Assert.assertEquals("点击目标控件", step.semanticLocator.get("instruction"));
        Assert.assertEquals("", step.history.get("instruction"));
    }

    @Test
    public void assemble_buildsSemanticLocatorWithoutXmlLocatorForVisualFallback() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "open something";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.rawCommand = "TAP 100 200";
        tap.vision.put("action", "tap the publish entry");
        tap.vision.put("expected", "publish page opens");
        tap.vision.put("carry_context", "from home tab");
        record.actions.add(tap);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(TaskMap.SCHEMA_V2, map.schema);
        Assert.assertEquals(1, map.stepCount());
        TaskMap.Step step = map.segments.get(0).steps.get(0);
        Assert.assertEquals("TAP", step.op);
        Assert.assertTrue(step.xmlLocator.isEmpty());
        Assert.assertTrue(step.tapPoint.isEmpty());
        Assert.assertTrue(step.containerProbe.isEmpty());
        Assert.assertFalse(step.semanticLocator.isEmpty());
        Assert.assertEquals("tap the publish entry", step.semanticLocator.get("instruction"));
        Assert.assertEquals("publish page opens", step.semanticLocator.get("expected_after_tap"));
        Assert.assertEquals("tap the publish entry", step.history.get("instruction"));
        Assert.assertEquals("publish page opens", step.history.get("expected"));
    }

    @Test
    public void assemble_restoresInputArgsFromRawCommandWhenRecordedArgsAreRedacted() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "search keyword";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.args.add("100");
        tap.args.add("200");
        tap.locator.put("resource_id", "search_box");
        tap.locator.put("class", "EditText");
        record.actions.add(tap);

        TaskRouteRecord.Action input = new TaskRouteRecord.Action();
        input.actionId = "a0002";
        input.subTaskId = "default";
        input.op = "INPUT";
        input.rawCommand = "INPUT \"hello world\"";
        input.args.add("[redacted]");
        record.actions.add(input);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(2, map.stepCount());
        Assert.assertEquals("INPUT", map.segments.get(0).steps.get(1).op);
        Assert.assertEquals(Collections.singletonList("hello world"), map.segments.get(0).steps.get(1).args);
    }

    @Test
    public void assemble_buildsSemanticLocatorWhenXmlLocatorMissing() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "open feed";
        record.taskId = "tid";

        TaskRouteRecord.Action tap = new TaskRouteRecord.Action();
        tap.actionId = "a0001";
        tap.subTaskId = "default";
        tap.op = "TAP";
        tap.vision.put("action", "tap the news feed item");
        tap.vision.put("expected", "detail page opens");
        record.actions.add(tap);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "manual");

        Assert.assertNotNull(map);
        Assert.assertEquals(1, map.stepCount());
        TaskMap.Step step = map.segments.get(0).steps.get(0);
        Assert.assertTrue(step.xmlLocator.isEmpty());
        Assert.assertTrue(step.containerProbe.isEmpty());
        Assert.assertTrue(step.tapPoint.isEmpty());
        Assert.assertEquals("", step.fallbackPoint);
        Assert.assertEquals("tap the news feed item", step.history.get("instruction"));
        Assert.assertEquals("tap the news feed item", step.semanticLocator.get("instruction"));
    }

    @Test
    public void assemble_keepsSwipeReplayPayload() {
        TaskRouteRecord record = new TaskRouteRecord();
        record.taskKeyHash = "hash";
        record.packageName = "com.demo";
        record.rootTask = "scroll once";
        record.taskId = "tid";

        TaskRouteRecord.Action swipe = new TaskRouteRecord.Action();
        swipe.actionId = "a0001";
        swipe.subTaskId = "default";
        swipe.op = "SWIPE";
        swipe.swipe.put("start", java.util.Arrays.asList(500, 1600));
        swipe.swipe.put("end", java.util.Arrays.asList(500, 400));
        swipe.swipe.put("duration_ms", 1500);
        record.actions.add(swipe);

        TaskMap map = TaskMapAssembler.assemble(record, Collections.<String>emptySet(), "ai");

        Assert.assertNotNull(map);
        Assert.assertEquals(1, map.stepCount());
        Assert.assertEquals("SWIPE", map.segments.get(0).steps.get(0).op);
        Assert.assertEquals(1500, ((Number) map.segments.get(0).steps.get(0).swipe.get("duration_ms")).intValue());
        Assert.assertEquals(2, ((java.util.List<?>) map.segments.get(0).steps.get(0).swipe.get("start")).size());
    }
}
