package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapStore;
import com.lxb.server.perception.PerceptionEngine;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class CortexTaskMapReplayTest {

    @Test
    public void executeTaskMapRoutingStep_failedSemanticTapStopsBeforeTapResolution() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", "")
        );
        FakeExecutionEngine execution = new FakeExecutionEngine();
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload),
                execution,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-semantic").toFile())
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.portableKind = "semantic_tap";
        step.adaptationStatus = "failed";
        step.adaptationError = "no_match";

        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";
        segment.steps.add(step);

        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task1");
        ctx.taskRouteKeyHash = "hash";
        ctx.currentTaskMapSegment = segment;

        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload), new TraceLogger(64));
        Object exec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", step, resolver, 0);
        Assert.assertFalse((Boolean) readField(exec, "ok"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> summary = (java.util.Map<String, Object>) readField(exec, "step");
        Assert.assertEquals("semantic_adaptation_failed", summary.get("result"));
        Assert.assertEquals("no_match", summary.get("reason"));
        Assert.assertEquals(0, execution.tapCount);
    }

    @Test
    public void tapWithValidLocator_doesNotCallVisualResolver() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode(100, 200, 300, 360, "android.widget.Button", "发布", "publish_button", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(10, 20, "unused", "fake_visual")
        );
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-locator").toFile()),
                visualResolver
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.locator.put("resource_id", "publish_button");
        step.locator.put("class", "Button");

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        Object point = invokeResolveRegularTaskMapTapPoint(engine, ctx, "com.demo", step, resolver, 0);

        Assert.assertEquals(200, ((Number) readField(point, "x")).intValue());
        Assert.assertEquals(280, ((Number) readField(point, "y")).intValue());
        Assert.assertEquals(0, visualResolver.calls);
    }

    @Test
    public void tapWithMissingLocator_callsVisualResolverAndReturnsPoint() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(321, 654, "semantic match", "semantic_visual")
        );
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-visual").toFile()),
                visualResolver
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.history.put("instruction", "tap the publish entry");

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        Object point = invokeResolveRegularTaskMapTapPoint(engine, ctx, "com.demo", step, resolver, 0);

        Assert.assertEquals(321, ((Number) readField(point, "x")).intValue());
        Assert.assertEquals(654, ((Number) readField(point, "y")).intValue());
        Assert.assertEquals("semantic_visual", readField(point, "pickedStage"));
        Assert.assertEquals(1, visualResolver.calls);
        Assert.assertTrue(visualResolver.lastRequest.historyText.contains("Recent turns"));
    }

    @Test
    public void tapWithContainerProbeOnly_doesNotUseContainerProbeAsFallback() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode(80, 340, 1000, 500, "android.widget.LinearLayout", "新闻", "feed_item", "条目")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.status(StepVisualResolveResult.STATUS_NO_MATCH, "not visible", "semantic_visual")
        );
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-container").toFile()),
                visualResolver
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.tapPoint.add(200);
        step.tapPoint.add(420);
        step.containerProbe.put("resource_id", "feed_item");
        step.containerProbe.put("class", "LinearLayout");

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        try {
            invokeResolveRegularTaskMapTapPoint(engine, ctx, "com.demo", step, resolver, 0);
            Assert.fail("expected semantic visual no_match");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(String.valueOf(e.getCause()).contains("task_map_visual_no_match"));
        }
        Assert.assertEquals(1, visualResolver.calls);
    }

    @Test
    public void tapWithFallbackPointOnly_doesNotUseFallbackPoint() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.status(StepVisualResolveResult.STATUS_AMBIGUOUS, "two targets", "semantic_visual")
        );
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-fallback").toFile()),
                visualResolver
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.fallbackPoint = "[200, 420]";

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        try {
            invokeResolveRegularTaskMapTapPoint(engine, ctx, "com.demo", step, resolver, 0);
            Assert.fail("expected semantic visual ambiguous");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(String.valueOf(e.getCause()).contains("task_map_visual_ambiguous"));
        }
        Assert.assertEquals(1, visualResolver.calls);
    }

    private static Object invokeExecuteTaskMapRoutingStep(
            CortexFsmEngine engine,
            CortexFsmEngine.Context ctx,
            String pkg,
            TaskMap.Step step,
            LocatorResolver resolver,
            int index
    ) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod(
                "executeTaskMapRoutingStep",
                CortexFsmEngine.Context.class,
                String.class,
                TaskMap.Step.class,
                LocatorResolver.class,
                int.class
        );
        method.setAccessible(true);
        return method.invoke(engine, ctx, pkg, step, resolver, index);
    }

    private static Object invokeResolveRegularTaskMapTapPoint(
            CortexFsmEngine engine,
            CortexFsmEngine.Context ctx,
            String pkg,
            TaskMap.Step step,
            LocatorResolver resolver,
            int index
    ) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod(
                "resolveRegularTaskMapTapPoint",
                CortexFsmEngine.Context.class,
                String.class,
                TaskMap.Step.class,
                LocatorResolver.class,
                int.class
        );
        method.setAccessible(true);
        return method.invoke(engine, ctx, pkg, step, resolver, index);
    }

    private static CortexFsmEngine.Context contextWithSegment(TaskMap.Step step) {
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";
        segment.steps.add(step);
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task1");
        ctx.taskRouteKeyHash = "hash";
        ctx.currentTaskMapSegment = segment;
        return ctx;
    }

    private static byte[] screenshotPayload() {
        return new byte[]{0x01, (byte) 0x89, 0x50, 0x4e, 0x47};
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static DumpActionsParser.ActionNode actionNode(
            int left,
            int top,
            int right,
            int bottom,
            String className,
            String text,
            String resourceId,
            String contentDesc
    ) {
        return new DumpActionsParser.ActionNode(
                (byte) 1,
                new Bounds(left, top, right, bottom),
                className,
                text,
                resourceId,
                contentDesc
        );
    }

    private static byte[] buildDumpActionsPayload(DumpActionsParser.ActionNode... nodes) throws Exception {
        List<String> shortPool = new ArrayList<String>();
        List<String> longPool = new ArrayList<String>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01);
        out.write((nodes.length >> 8) & 0xFF);
        out.write(nodes.length & 0xFF);

        for (DumpActionsParser.ActionNode node : nodes) {
            ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
            buf.put(node.type);
            buf.putShort((short) node.bounds.left);
            buf.putShort((short) node.bounds.top);
            buf.putShort((short) node.bounds.right);
            buf.putShort((short) node.bounds.bottom);
            buf.put((byte) shortPoolIndex(shortPool, node.className));
            buf.putShort((short) longPoolIndex(longPool, node.text));
            buf.put((byte) shortPoolIndex(shortPool, node.resourceId));
            buf.put((byte) shortPoolIndex(shortPool, node.contentDesc));
            buf.put(new byte[6]);
            out.write(buf.array());
        }

        out.write(shortPool.size());
        for (String value : shortPool) {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            out.write(data.length);
            out.write(data);
        }

        out.write((longPool.size() >> 8) & 0xFF);
        out.write(longPool.size() & 0xFF);
        for (String value : longPool) {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            out.write((data.length >> 8) & 0xFF);
            out.write(data.length & 0xFF);
            out.write(data);
        }
        return out.toByteArray();
    }

    private static int shortPoolIndex(List<String> pool, String value) {
        if (value == null || value.isEmpty()) {
            return 0xFF;
        }
        int idx = pool.indexOf(value);
        if (idx >= 0) {
            return idx;
        }
        pool.add(value);
        return pool.size() - 1;
    }

    private static int longPoolIndex(List<String> pool, String value) {
        if (value == null || value.isEmpty()) {
            return 0xFFFF;
        }
        int idx = pool.indexOf(value);
        if (idx >= 0) {
            return idx;
        }
        pool.add(value);
        return pool.size() - 1;
    }

    private static final class FakePerceptionEngine extends PerceptionEngine {
        private final byte[] payload;
        private final byte[] screenshot;

        private FakePerceptionEngine(byte[] payload) {
            this(payload, null);
        }

        private FakePerceptionEngine(byte[] payload, byte[] screenshot) {
            this.payload = payload;
            this.screenshot = screenshot;
        }

        @Override
        public byte[] handleDumpActions(byte[] payload) {
            return this.payload;
        }

        @Override
        public byte[] handleScreenshot() {
            return screenshot != null ? screenshot : super.handleScreenshot();
        }
    }

    private static final class CountingVisualResolver implements TaskMapStepVisualResolver {
        private final StepVisualResolveResult result;
        private int calls = 0;
        private StepVisualResolveRequest lastRequest;

        private CountingVisualResolver(StepVisualResolveResult result) {
            this.result = result;
        }

        @Override
        public StepVisualResolveResult resolve(StepVisualResolveRequest request) {
            calls += 1;
            lastRequest = request;
            return result;
        }
    }

    private static final class FakeExecutionEngine extends com.lxb.server.execution.ExecutionEngine {
        private int tapCount = 0;

        @Override
        public byte[] handleTap(byte[] payload) {
            tapCount += 1;
            return new byte[]{0x01};
        }
    }
}
