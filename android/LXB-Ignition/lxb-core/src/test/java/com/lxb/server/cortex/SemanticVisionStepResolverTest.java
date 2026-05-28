package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;

import org.junit.Assert;
import org.junit.Test;

public class SemanticVisionStepResolverTest {

    @Test
    public void parse_pointJson_acceptsCoordinates() {
        StepVisualResolveResult result = SemanticVisionStepResolver.parseResult(
                "{\"result\":\"point\",\"x\":100,\"y\":200,\"reason\":\"target\"}"
        );

        Assert.assertEquals(StepVisualResolveResult.STATUS_POINT, result.status);
        Assert.assertEquals(100, result.x);
        Assert.assertEquals(200, result.y);
    }

    @Test
    public void parse_noMatchAmbiguousBlocked_acceptsStatuses() {
        Assert.assertEquals(StepVisualResolveResult.STATUS_NO_MATCH,
                SemanticVisionStepResolver.parseResult("{\"result\":\"no_match\",\"reason\":\"not visible\"}").status);
        Assert.assertEquals(StepVisualResolveResult.STATUS_AMBIGUOUS,
                SemanticVisionStepResolver.parseResult("{\"result\":\"ambiguous\",\"reason\":\"two targets\"}").status);
        Assert.assertEquals(StepVisualResolveResult.STATUS_BLOCKED,
                SemanticVisionStepResolver.parseResult("{\"result\":\"blocked\",\"reason\":\"modal\"}").status);
    }

    @Test
    public void parse_dslTap_rejectsBusinessCommand() {
        StepVisualResolveResult result = SemanticVisionStepResolver.parseResult("TAP 100 200");

        Assert.assertEquals(StepVisualResolveResult.STATUS_ERROR, result.status);
        Assert.assertTrue(result.reason.startsWith("invalid_json"));
    }

    @Test
    public void prompt_containsCurrentStepOnlyBoundary() {
        String prompt = SemanticVisionStepResolver.buildPrompt(new StepVisualResolveRequest(
                "task",
                "route",
                "com.demo",
                "seg0001",
                0,
                new TaskMap.Step(),
                new byte[]{1, 2, 3},
                "Recent turns: none",
                "locator missing"
        ));

        Assert.assertTrue(prompt.contains("CURRENT route step"));
        Assert.assertTrue(prompt.contains("Do not plan the user's task"));
        Assert.assertTrue(prompt.contains("Do not output TAP/BACK/WAIT commands"));
        Assert.assertTrue(prompt.contains("pixel coordinates"));
    }

    @Test
    public void prompt_includesHistoryAndStepSemantics() {
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.expected = "open detail";
        step.semanticNote = "news feed item";
        step.history.put(CortexExecutionHistory.KEY_INSTRUCTION, "tap the first news card");
        step.history.put(CortexExecutionHistory.KEY_EXPECTED, "detail opens");
        step.semanticDescriptor.put("instruction", "tap semantic target");

        String prompt = SemanticVisionStepResolver.buildPrompt(new StepVisualResolveRequest(
                "task",
                "route",
                "com.demo",
                "seg0001",
                1,
                step,
                new byte[]{1, 2, 3},
                "1) action: previous",
                "locator failed"
        ));

        Assert.assertTrue(prompt.contains("tap the first news card"));
        Assert.assertTrue(prompt.contains("open detail"));
        Assert.assertTrue(prompt.contains("news feed item"));
        Assert.assertTrue(prompt.contains("Execution history before this step"));
        Assert.assertTrue(prompt.contains("previous"));
    }
}
