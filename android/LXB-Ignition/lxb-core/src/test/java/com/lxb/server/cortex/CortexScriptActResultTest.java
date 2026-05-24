package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapStore;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class CortexScriptActResultTest {

    @Test
    public void scriptAct_withoutTaskMapSegmentEmitsSkippedAndFallsBackToVision() throws Exception {
        TraceLogger trace = new TraceLogger(128);
        CortexFsmEngine engine = newEngine(trace);
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task-skip");
        ctx.selectedPackage = "com.demo";

        CortexFsmEngine.State next = invokeScriptAct(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.VISION_ACT, next);
        Assert.assertEquals(Boolean.TRUE, ctx.routeResult.get("ok"));
        Assert.assertEquals("no_script", ctx.routeResult.get("mode"));
        Assert.assertTrue(traceContains(trace, "fsm_script_act_result", "\"result\":\"SKIPPED\""));
    }

    @Test
    public void scriptAct_successfulTaskMapReplayEmitsReplayedAndPreservesRouteResult() throws Exception {
        TraceLogger trace = new TraceLogger(128);
        CortexFsmEngine engine = newEngine(trace);
        CortexFsmEngine.Context ctx = newContextWithSegment("task-replayed", successfulWaitSegment());

        CortexFsmEngine.State next = invokeScriptAct(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.VISION_ACT, next);
        Assert.assertEquals(Boolean.TRUE, ctx.routeResult.get("ok"));
        Assert.assertEquals("task_map", ctx.routeResult.get("mode"));
        Assert.assertEquals("seg-1", ctx.routeResult.get("segment_id"));
        Assert.assertTrue(traceContains(trace, "fsm_script_act_result", "\"result\":\"REPLAYED\""));
    }

    @Test
    public void scriptAct_finishAfterReplayEmitsDoneAndSkipsVision() throws Exception {
        TraceLogger trace = new TraceLogger(128);
        CortexFsmEngine engine = newEngine(trace);
        TaskMap map = new TaskMap();
        map.finishAfterReplay = true;
        CortexFsmEngine.Context ctx = newContextWithSegment("task-done", successfulWaitSegment());
        ctx.taskMap = map;
        ctx.currentSubTaskIsLast = true;

        CortexFsmEngine.State next = invokeScriptAct(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.FINISH, next);
        Assert.assertEquals(Boolean.TRUE, ctx.routeResult.get("ok"));
        Assert.assertTrue(traceContains(trace, "fsm_script_act_result", "\"result\":\"DONE\""));
        Assert.assertFalse(traceContains(trace, "fsm_state_enter", "\"state\":\"VISION_ACT\""));
    }

    @Test
    public void scriptAct_failedTaskMapReplayEmitsFallbackVision() throws Exception {
        TraceLogger trace = new TraceLogger(128);
        CortexFsmEngine engine = newEngine(trace);
        TaskMap.Segment segment = segmentWithStep("seg-fallback", "UNKNOWN");
        CortexFsmEngine.Context ctx = newContextWithSegment("task-fallback", segment);

        CortexFsmEngine.State next = invokeScriptAct(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.VISION_ACT, next);
        Assert.assertEquals(Boolean.FALSE, ctx.routeResult.get("ok"));
        Assert.assertEquals("task_map", ctx.routeResult.get("mode"));
        Assert.assertTrue(String.valueOf(ctx.routeResult.get("reason")).contains("unsupported_task_map_op"));
        Assert.assertTrue(traceContains(trace, "fsm_script_act_result", "\"result\":\"FALLBACK_VISION\""));
    }

    private static CortexFsmEngine newEngine(TraceLogger trace) throws Exception {
        return new CortexFsmEngine(
                null,
                null,
                null,
                trace,
                new TaskMapStore(Files.createTempDirectory("script-act-test").toFile())
        );
    }

    private static CortexFsmEngine.Context newContextWithSegment(String taskId, TaskMap.Segment segment) {
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context(taskId);
        ctx.selectedPackage = "com.demo";
        ctx.currentTaskMapSegment = segment;
        ctx.taskRouteKeyHash = "route-" + taskId;
        return ctx;
    }

    private static TaskMap.Segment successfulWaitSegment() {
        return segmentWithStep("seg-1", "WAIT");
    }

    private static TaskMap.Segment segmentWithStep(String segmentId, String op) {
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "step-1";
        step.op = op;
        if ("WAIT".equals(op)) {
            step.args.add("0");
        }

        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = segmentId;
        segment.packageName = "com.demo";
        segment.subTaskDescription = "Do demo work";
        segment.steps.add(step);
        return segment;
    }

    private static CortexFsmEngine.State invokeScriptAct(CortexFsmEngine engine, CortexFsmEngine.Context ctx) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod("runScriptActState", CortexFsmEngine.Context.class);
        method.setAccessible(true);
        return (CortexFsmEngine.State) method.invoke(engine, ctx);
    }

    private static boolean traceContains(TraceLogger trace, String event, String fragment) {
        for (String line : traceLines(trace)) {
            if (line.contains("\"event\":\"" + event + "\"") && line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> traceLines(TraceLogger trace) {
        TraceLogger.PullPage page = trace.pullTail(128);
        List<String> out = new ArrayList<String>();
        for (TraceLogger.PullItem item : page.items) {
            out.add(item.line);
        }
        return out;
    }
}
