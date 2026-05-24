package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapStore;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;

public class CortexFsmPlanBuilderTest {

    @Test
    public void initPlan_taskRouteHitRestoresSegmentsAndSkipsDecompose() throws Exception {
        TaskMapStore store = new TaskMapStore(Files.createTempDirectory("fsm-plan-hit").toFile());
        TaskMap map = usableTaskMap("route-hit");
        Assert.assertTrue(store.saveMap(map));

        CortexFsmEngine engine = new CortexFsmEngine(null, null, null, new TraceLogger(64), store);
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task-hit");
        ctx.taskRouteKeyHash = "route-hit";
        ctx.taskMapMode = "auto";

        CortexFsmEngine.State next = invokeBuildExecutionPlan(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.DEVICE_PREPARE, next);
        Assert.assertTrue(ctx.hasTaskRoute);
        Assert.assertTrue(ctx.taskMapRootHit);
        Assert.assertEquals("hit", ctx.taskRouteStatus);
        Assert.assertEquals(1, ctx.subTasks.size());
        Assert.assertEquals("com.demo", ctx.taskMap.segments.get(0).packageName);
    }

    @Test
    public void initPlan_routeOffFallsBackToTaskDecompose() throws Exception {
        CortexFsmEngine engine = new CortexFsmEngine(
                null,
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("fsm-plan-off").toFile())
        );
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task-off");
        ctx.taskRouteKeyHash = "route-off";
        ctx.taskMapMode = "off";

        CortexFsmEngine.State next = invokeBuildExecutionPlan(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.TASK_DECOMPOSE, next);
        Assert.assertFalse(ctx.hasTaskRoute);
        Assert.assertFalse(ctx.taskMapRootHit);
        Assert.assertEquals("off", ctx.taskRouteStatus);
    }

    @Test
    public void devicePrepareDoesNotRequireSelectedPackage() throws Exception {
        TraceLogger trace = new TraceLogger(64);
        CortexFsmEngine engine = new CortexFsmEngine(
                null,
                null,
                null,
                trace,
                new TaskMapStore(Files.createTempDirectory("fsm-device-prepare").toFile())
        );
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task-device");
        ctx.selectedPackage = "";
        ctx.hasTaskRoute = true;
        ctx.taskRouteStatus = "hit";

        CortexFsmEngine.State next = invokeDevicePrepare(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.DEVICE_PREPARE, next);
        Assert.assertTrue(traceContains(trace, "fsm_device_prepare_done"));
        Assert.assertFalse(traceContains(trace, "fsm_app_enter_failed"));
    }

    @Test
    public void appEnterOwnsMissingPackageFailure() throws Exception {
        TraceLogger trace = new TraceLogger(64);
        CortexFsmEngine engine = new CortexFsmEngine(
                null,
                null,
                null,
                trace,
                new TaskMapStore(Files.createTempDirectory("fsm-app-enter").toFile())
        );
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task-app-enter");
        ctx.selectedPackage = "";

        CortexFsmEngine.State next = invokeAppEnter(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.FAIL, next);
        Assert.assertEquals("app_enter_no_package", ctx.error);
        Assert.assertTrue(traceContains(trace, "fsm_app_enter_failed"));
    }

    private static TaskMap usableTaskMap(String key) {
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "step-1";
        step.op = "WAIT";
        step.args.add("0");

        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg-1";
        segment.subTaskId = "sub-1";
        segment.subTaskDescription = "Open demo";
        segment.packageName = "com.demo";
        segment.steps.add(step);

        TaskMap map = new TaskMap();
        map.taskKeyHash = key;
        map.packageName = "com.demo";
        map.segments.add(segment);
        return map;
    }

    private static CortexFsmEngine.State invokeBuildExecutionPlan(CortexFsmEngine engine, CortexFsmEngine.Context ctx) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod("buildExecutionPlanInInit", CortexFsmEngine.Context.class);
        method.setAccessible(true);
        return (CortexFsmEngine.State) method.invoke(engine, ctx);
    }

    private static CortexFsmEngine.State invokeDevicePrepare(CortexFsmEngine engine, CortexFsmEngine.Context ctx) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod("runDevicePrepareState", CortexFsmEngine.Context.class);
        method.setAccessible(true);
        return (CortexFsmEngine.State) method.invoke(engine, ctx);
    }

    private static CortexFsmEngine.State invokeAppEnter(CortexFsmEngine engine, CortexFsmEngine.Context ctx) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod("runAppEnterState", CortexFsmEngine.Context.class);
        method.setAccessible(true);
        return (CortexFsmEngine.State) method.invoke(engine, ctx);
    }

    private static boolean traceContains(TraceLogger trace, String event) {
        TraceLogger.PullPage page = trace.pullTail(64);
        for (TraceLogger.PullItem item : page.items) {
            if (item.line.contains("\"event\":\"" + event + "\"")) {
                return true;
            }
        }
        return false;
    }
}
