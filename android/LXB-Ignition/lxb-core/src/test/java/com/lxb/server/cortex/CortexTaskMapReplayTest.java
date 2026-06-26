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
import java.util.Map;

public class CortexTaskMapReplayTest {

    @Test
    public void executeTaskMapRoutingStep_withoutXmlLocator_usesSemanticLocatorPath() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(321, 654, "semantic match", "semantic_visual")
        );
        FakeExecutionEngine execution = new FakeExecutionEngine();
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                execution,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-semantic").toFile()),
                visualResolver
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.semanticLocator.put("instruction", "tap the publish entry");
        step.semanticLocator.put("expected_after_tap", "publish page opens");

        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";
        segment.steps.add(step);

        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task1");
        ctx.taskRouteKeyHash = "hash";
        ctx.currentTaskMapSegment = segment;

        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload), new TraceLogger(64));
        Object exec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", step, resolver, 0);
        Assert.assertTrue((Boolean) readField(exec, "ok"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> summary = (java.util.Map<String, Object>) readField(exec, "step");
        Assert.assertEquals("semantic_locator", summary.get("locator_mode"));
        Assert.assertEquals("ok", summary.get("result"));
        Assert.assertEquals("", summary.get("reason"));
        Assert.assertEquals("semantic_visual", summary.get("picked_stage"));
        Assert.assertEquals(1, execution.tapCount);
        Assert.assertEquals(1, visualResolver.calls);
    }

    @Test
    public void semanticLocator_normalizedPointIsMappedBeforeTapExecution() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(500, 500, "semantic center", "semantic_visual")
        );
        FakeExecutionEngine execution = new FakeExecutionEngine();
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                execution,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-normalized").toFile()),
                visualResolver
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.semanticLocator.put("instruction", "tap the normalized center");

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        ctx.deviceInfo.put("width", 1080);
        ctx.deviceInfo.put("height", 2200);

        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload), new TraceLogger(64));
        Object exec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", step, resolver, 0);

        Assert.assertTrue((Boolean) readField(exec, "ok"));
        Assert.assertEquals(1, execution.tapCount);
        Assert.assertEquals(540, execution.lastTapX);
        Assert.assertEquals(1100, execution.lastTapY);
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
        SequencedTapVerificationResolver verifier = new SequencedTapVerificationResolver(
                TaskMapTapVerificationResult.current(10, 20, "unused", TaskMapTapReplayVerifier.VERIFIER_NAME)
        );
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                null,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-locator").toFile()),
                visualResolver,
                verifier
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.semanticLocator.put("instruction", "tap the publish entry");
        step.xmlLocator.put("resource_id", "publish_button");
        step.xmlLocator.put("class", "Button");

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        Object point = invokeResolveRegularTaskMapTapPoint(engine, ctx, "com.demo", step, resolver, 0);

        Assert.assertEquals(200, ((Number) readField(point, "x")).intValue());
        Assert.assertEquals(280, ((Number) readField(point, "y")).intValue());
        Assert.assertEquals(0, visualResolver.calls);
        Assert.assertEquals(0, verifier.calls);
    }

    @Test
    public void tapRetryVerification_previousKeepsRouteOnSameIndexAndRetriesOnce() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode(100, 200, 300, 360, "android.widget.Button", "进入游戏", "enter_game_button", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(111, 222, "unused", "semantic_visual")
        );
        SequencedTapVerificationResolver verifier = new SequencedTapVerificationResolver(
                TaskMapTapVerificationResult.previous(
                        111,
                        222,
                        "old page still visible",
                        "previous tap target remains and expected page did not appear",
                        "mismatch",
                        "retry the previous tap from verifier output",
                        "previous target still visible",
                        TaskMapTapReplayVerifier.VERIFIER_NAME
                ),
                TaskMapTapVerificationResult.current(
                        333,
                        444,
                        "new page visible",
                        "previous tap landed",
                        "match",
                        "execute the current tap from verifier output",
                        "current target visible",
                        TaskMapTapReplayVerifier.VERIFIER_NAME
                )
        );
        FakeExecutionEngine execution = new FakeExecutionEngine();
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                execution,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-previous").toFile()),
                visualResolver,
                verifier
        );

        TaskMap.Step seedStep = new TaskMap.Step();
        seedStep.stepId = "seed-step";
        seedStep.op = "TAP";
        seedStep.expected = "seed page opens";
        seedStep.semanticLocator.put("instruction", "tap the enter game entry");
        seedStep.xmlLocator.put("resource_id", "enter_game_button");
        seedStep.xmlLocator.put("class", "Button");

        TaskMap.Step currentStep = new TaskMap.Step();
        currentStep.stepId = "current-step";
        currentStep.op = "TAP";
        currentStep.expected = "detail page opens";
        currentStep.semanticLocator.put("instruction", "tap the detail entry");
        currentStep.semanticLocator.put("expected_after_tap", "detail page opens");

        CortexFsmEngine.Context ctx = contextWithSegment(seedStep, currentStep);
        ctx.taskRouteKeyHash = "hash";
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        Object seedExec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", seedStep, resolver, 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> seedSummary = (Map<String, Object>) readField(seedExec, "step");
        Assert.assertEquals("ok", seedSummary.get("result"));
        Assert.assertEquals("", seedSummary.get("verification_decision"));
        Assert.assertEquals(0, verifier.calls);
        Assert.assertEquals("seed-step", readField(readField(ctx.taskMapLastTapCheckpoint, "step"), "stepId"));

        Object retryExec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", currentStep, resolver, 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> retrySummary = (Map<String, Object>) readField(retryExec, "step");
        Assert.assertEquals("ok", retrySummary.get("result"));
        Assert.assertEquals(Boolean.FALSE, readField(retryExec, "advanceIndex"));
        Assert.assertEquals("previous", retrySummary.get("verification_decision"));
        Assert.assertEquals("previous target still visible", retrySummary.get("verification_reason"));
        Assert.assertEquals("old page still visible", retrySummary.get("verification_observing"));
        Assert.assertEquals("previous tap target remains and expected page did not appear", retrySummary.get("verification_judging_prev"));
        Assert.assertEquals("mismatch", retrySummary.get("verification_judge_prev_result"));
        Assert.assertEquals("retry the previous tap from verifier output", retrySummary.get("verification_thinking"));
        Assert.assertEquals("seed-step", retrySummary.get("execution_step_id"));
        Assert.assertEquals(0, ((Number) retrySummary.get("execution_step_index")).intValue());
        Assert.assertEquals("retry_previous_tap", retrySummary.get("reason"));
        Assert.assertEquals(1, verifier.calls);
        Assert.assertEquals(1, verifier.requests.size());
        Assert.assertEquals("seed-step", verifier.requests.get(0).lastTapStep.stepId);
        Assert.assertEquals("current-step", verifier.requests.get(0).currentStep.stepId);
        Assert.assertEquals("xml_locator", verifier.requests.get(0).lastTapLocatorMode);
        Assert.assertEquals("semantic_locator", verifier.requests.get(0).currentStepLocatorMode);
        Assert.assertEquals("seed page opens", verifier.requests.get(0).lastTapStep.expected);
        Assert.assertEquals(1, ((Number) readField(ctx.taskMapLastTapCheckpoint, "retryCount")).intValue());

        Object advanceExec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", currentStep, resolver, 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> advanceSummary = (Map<String, Object>) readField(advanceExec, "step");
        Assert.assertEquals("ok", advanceSummary.get("result"));
        Assert.assertEquals(Boolean.TRUE, readField(advanceExec, "advanceIndex"));
        Assert.assertEquals("current", advanceSummary.get("verification_decision"));
        Assert.assertEquals("current target visible", advanceSummary.get("verification_reason"));
        Assert.assertEquals("new page visible", advanceSummary.get("verification_observing"));
        Assert.assertEquals("previous tap landed", advanceSummary.get("verification_judging_prev"));
        Assert.assertEquals("match", advanceSummary.get("verification_judge_prev_result"));
        Assert.assertEquals("execute the current tap from verifier output", advanceSummary.get("verification_thinking"));
        Assert.assertEquals("current-step", advanceSummary.get("execution_step_id"));
        Assert.assertEquals(1, ((Number) advanceSummary.get("execution_step_index")).intValue());
        Assert.assertEquals("", advanceSummary.get("reason"));
        Assert.assertEquals(2, verifier.calls);
        Assert.assertEquals("current-step", readField(readField(ctx.taskMapLastTapCheckpoint, "step"), "stepId"));
        Assert.assertEquals(0, ((Number) readField(ctx.taskMapLastTapCheckpoint, "retryCount")).intValue());
        Assert.assertEquals(3, execution.tapCount);
        Assert.assertEquals(0, visualResolver.calls);
    }

    @Test
    public void tapRetryVerification_currentNormalizedCommandIsMappedBeforeTapExecution() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode(100, 200, 300, 360, "android.widget.Button", "进入游戏", "enter_game_button", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(111, 222, "unused", "semantic_visual")
        );
        SequencedTapVerificationResolver verifier = new SequencedTapVerificationResolver(
                TaskMapTapVerificationResult.current(500, 500, "current target visible", TaskMapTapReplayVerifier.VERIFIER_NAME)
        );
        FakeExecutionEngine execution = new FakeExecutionEngine();
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                execution,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-verifier-normalized").toFile()),
                visualResolver,
                verifier
        );

        TaskMap.Step seedStep = new TaskMap.Step();
        seedStep.stepId = "seed-step";
        seedStep.op = "TAP";
        seedStep.expected = "seed page opens";
        seedStep.semanticLocator.put("instruction", "tap the enter game entry");
        seedStep.xmlLocator.put("resource_id", "enter_game_button");
        seedStep.xmlLocator.put("class", "Button");

        TaskMap.Step currentStep = new TaskMap.Step();
        currentStep.stepId = "current-step";
        currentStep.op = "TAP";
        currentStep.expected = "detail page opens";
        currentStep.semanticLocator.put("instruction", "tap the detail entry");

        CortexFsmEngine.Context ctx = contextWithSegment(seedStep, currentStep);
        ctx.taskRouteKeyHash = "hash";
        ctx.deviceInfo.put("width", 1080);
        ctx.deviceInfo.put("height", 2200);
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", seedStep, resolver, 0);
        Object exec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", currentStep, resolver, 1);

        Assert.assertTrue((Boolean) readField(exec, "ok"));
        Assert.assertEquals(Boolean.TRUE, readField(exec, "advanceIndex"));
        Assert.assertEquals(2, execution.tapCount);
        Assert.assertEquals(540, execution.lastTapX);
        Assert.assertEquals(1100, execution.lastTapY);
        Assert.assertEquals(0, visualResolver.calls);
    }

    @Test
    public void tapRetryVerification_deferFallsBackToVisualResolver() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", ""),
                actionNode(100, 200, 300, 360, "android.widget.Button", "进入游戏", "enter_game_button", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(777, 888, "fallback target", "semantic_visual")
        );
        SequencedTapVerificationResolver verifier = new SequencedTapVerificationResolver(
                TaskMapTapVerificationResult.defer("", "page still loading", TaskMapTapReplayVerifier.VERIFIER_NAME)
        );
        FakeExecutionEngine execution = new FakeExecutionEngine();
        CortexFsmEngine engine = new CortexFsmEngine(
                new FakePerceptionEngine(payload, screenshotPayload()),
                execution,
                null,
                new TraceLogger(64),
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-defer").toFile()),
                visualResolver,
                verifier
        );

        TaskMap.Step seedStep = new TaskMap.Step();
        seedStep.stepId = "seed-step";
        seedStep.op = "TAP";
        seedStep.expected = "seed page opens";
        seedStep.semanticLocator.put("instruction", "tap the enter game entry");
        seedStep.xmlLocator.put("resource_id", "enter_game_button");
        seedStep.xmlLocator.put("class", "Button");

        TaskMap.Step currentStep = new TaskMap.Step();
        currentStep.stepId = "current-step";
        currentStep.op = "TAP";
        currentStep.expected = "detail page opens";
        currentStep.semanticLocator.put("instruction", "tap the detail entry");
        currentStep.semanticLocator.put("expected_after_tap", "detail page opens");

        CortexFsmEngine.Context ctx = contextWithSegment(seedStep, currentStep);
        ctx.taskRouteKeyHash = "hash";
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        Object seedExec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", seedStep, resolver, 0);
        Assert.assertEquals(Boolean.TRUE, readField(seedExec, "advanceIndex"));

        Object deferExec = invokeExecuteTaskMapRoutingStep(engine, ctx, "com.demo", currentStep, resolver, 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> deferSummary = (Map<String, Object>) readField(deferExec, "step");
        Assert.assertEquals("ok", deferSummary.get("result"));
        Assert.assertEquals(Boolean.TRUE, readField(deferExec, "advanceIndex"));
        Assert.assertEquals("defer", deferSummary.get("verification_decision"));
        Assert.assertEquals("page still loading", deferSummary.get("verification_reason"));
        Assert.assertEquals("semantic_visual", deferSummary.get("picked_stage"));
        Assert.assertEquals("current-step", deferSummary.get("execution_step_id"));
        Assert.assertEquals(1, ((Number) deferSummary.get("execution_step_index")).intValue());
        Assert.assertEquals("", deferSummary.get("reason"));
        Assert.assertEquals(1, verifier.calls);
        Assert.assertEquals(1, visualResolver.calls);
        Assert.assertEquals(2, execution.tapCount);
        Assert.assertEquals("current-step", readField(readField(ctx.taskMapLastTapCheckpoint, "step"), "stepId"));
        Assert.assertEquals(0, ((Number) readField(ctx.taskMapLastTapCheckpoint, "retryCount")).intValue());
        Assert.assertEquals("seed-step", verifier.requests.get(0).lastTapStep.stepId);
        Assert.assertEquals("current-step", verifier.requests.get(0).currentStep.stepId);
        Assert.assertEquals("xml_locator", verifier.requests.get(0).lastTapLocatorMode);
        Assert.assertEquals("semantic_locator", verifier.requests.get(0).currentStepLocatorMode);
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
        step.semanticLocator.put("instruction", "tap the publish entry");
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
    public void tapWithMissingLocator_waitsBeforeSemanticScreenshot() throws Exception {
        byte[] payload = buildDumpActionsPayload(
                actionNode(0, 0, 1080, 2200, "android.widget.FrameLayout", "", "root", "")
        );
        CountingVisualResolver visualResolver = new CountingVisualResolver(
                StepVisualResolveResult.point(321, 654, "semantic match", "semantic_visual")
        );
        TimingPerceptionEngine perception = new TimingPerceptionEngine(payload, screenshotPayload());
        TraceLogger trace = new TraceLogger(64);
        CortexFsmEngine engine = new CortexFsmEngine(
                perception,
                null,
                null,
                trace,
                new TaskMapStore(Files.createTempDirectory("taskmap-replay-settle").toFile()),
                visualResolver
        );

        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.semanticLocator.put("instruction", "tap the publish entry");

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        LocatorResolver resolver = new LocatorResolver(perception, new TraceLogger(64));

        long startedAt = System.currentTimeMillis();
        invokeResolveRegularTaskMapTapPoint(engine, ctx, "com.demo", step, resolver, 0);

        Assert.assertTrue(perception.firstScreenshotAtMs > 0L);
        Assert.assertTrue(
                "expected semantic screenshot settle delay before screenshot",
                perception.firstScreenshotAtMs - startedAt >= 1800L
        );

        Map<String, Object> lastTrace = lastTraceObject(trace);
        Assert.assertEquals("task_map_semantic_screenshot_settle", lastTrace.get("event"));
        Assert.assertEquals("semantic_visual_resolve", lastTrace.get("reason"));
        Assert.assertEquals(2000L, ((Number) lastTrace.get("wait_ms")).longValue());
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
        step.semanticLocator.put("instruction", "tap the feed item");
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
        Assert.assertEquals(3, visualResolver.calls);
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
        step.semanticLocator.put("instruction", "tap fallback target");
        step.fallbackPoint = "[200, 420]";

        CortexFsmEngine.Context ctx = contextWithSegment(step);
        LocatorResolver resolver = new LocatorResolver(new FakePerceptionEngine(payload, screenshotPayload()), new TraceLogger(64));

        try {
            invokeResolveRegularTaskMapTapPoint(engine, ctx, "com.demo", step, resolver, 0);
            Assert.fail("expected semantic visual ambiguous");
        } catch (InvocationTargetException e) {
            Assert.assertTrue(String.valueOf(e.getCause()).contains("task_map_visual_ambiguous"));
        }
        Assert.assertEquals(3, visualResolver.calls);
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

    private static CortexFsmEngine.Context contextWithSegment(TaskMap.Step... steps) {
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg0001";
        segment.packageName = "com.demo";
        if (steps != null) {
            for (TaskMap.Step step : steps) {
                if (step != null) {
                    segment.steps.add(step);
                }
            }
        }
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

    private static final class TimingPerceptionEngine extends PerceptionEngine {
        private final byte[] payload;
        private final byte[] screenshot;
        private long firstScreenshotAtMs = 0L;

        private TimingPerceptionEngine(byte[] payload, byte[] screenshot) {
            this.payload = payload;
            this.screenshot = screenshot;
        }

        @Override
        public byte[] handleDumpActions(byte[] payload) {
            return this.payload;
        }

        @Override
        public byte[] handleScreenshot() {
            if (firstScreenshotAtMs == 0L) {
                firstScreenshotAtMs = System.currentTimeMillis();
            }
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

    private static final class SequencedTapVerificationResolver implements TaskMapTapVerificationResolver {
        private final List<TaskMapTapVerificationResult> results = new ArrayList<TaskMapTapVerificationResult>();
        private final List<TaskMapTapVerificationRequest> requests = new ArrayList<TaskMapTapVerificationRequest>();
        private int calls = 0;

        private SequencedTapVerificationResolver(TaskMapTapVerificationResult... results) {
            if (results != null) {
                for (TaskMapTapVerificationResult result : results) {
                    this.results.add(result);
                }
            }
        }

        @Override
        public TaskMapTapVerificationResult verify(TaskMapTapVerificationRequest request) {
            requests.add(request);
            if (results.isEmpty()) {
                calls += 1;
                return TaskMapTapVerificationResult.error("no_result", TaskMapTapReplayVerifier.VERIFIER_NAME);
            }
            int index = Math.min(calls, results.size() - 1);
            calls += 1;
            return results.get(index);
        }
    }

    private static final class FakeExecutionEngine extends com.lxb.server.execution.ExecutionEngine {
        private int tapCount = 0;
        private int lastTapX = -1;
        private int lastTapY = -1;

        @Override
        public byte[] handleTap(byte[] payload) {
            tapCount += 1;
            if (payload != null && payload.length >= 4) {
                ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                lastTapX = buf.getShort() & 0xFFFF;
                lastTapY = buf.getShort() & 0xFFFF;
            }
            return new byte[]{0x01};
        }
    }

    private static Map<String, Object> lastTraceObject(TraceLogger trace) {
        TraceLogger.PullPage page = trace.pullTail(1);
        Assert.assertEquals(1, page.items.size());
        return com.lxb.server.cortex.json.Json.parseObject(page.items.get(0).line);
    }
}
