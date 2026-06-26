package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;

import org.junit.Assert;
import org.junit.Test;

public class TaskMapTapReplayVerifierTest {

    @Test
    public void buildPrompt_includesReasoningGuidanceAndLocatorContext() {
        TaskMap.Step current = new TaskMap.Step();
        current.stepId = "current-step";
        current.expected = "detail page opens";
        current.semanticNote = "current card";
        current.semanticLocator.put("instruction", "tap the current entry");

        TaskMap.Step previous = new TaskMap.Step();
        previous.stepId = "previous-step";
        previous.expected = "game page opens";
        previous.semanticLocator.put("instruction", "tap the game entry");
        previous.xmlLocator.put("resource_id", "game_button");
        previous.xmlLocator.put("class", "Button");

        TaskMapTapVerificationRequest request = new TaskMapTapVerificationRequest(
                "task-1",
                "route-1",
                "com.demo",
                "segment-1",
                1,
                current,
                previous,
                0,
                "semantic_locator",
                "xml_locator",
                321,
                654,
                1,
                "previous target still visible",
                new byte[]{1},
                "Recent turns: none"
        );

        String prompt = TaskMapTapReplayVerifier.buildPrompt(request);

        Assert.assertTrue(prompt.contains("Use one response only"));
        Assert.assertTrue(prompt.contains("Write the reasoning directly into the JSON fields"));
        Assert.assertTrue(prompt.contains("\"observing\""));
        Assert.assertTrue(prompt.contains("\"judging_prev\""));
        Assert.assertTrue(prompt.contains("\"judge_prev_result\""));
        Assert.assertTrue(prompt.contains("\"thinking\""));
        Assert.assertTrue(prompt.contains("\"decision\":\"previous\""));
        Assert.assertTrue(prompt.contains("\"decision\":\"current\""));
        Assert.assertTrue(prompt.contains("\"decision\":\"defer\""));
        Assert.assertTrue(prompt.contains("current_step_semantic_locator"));
        Assert.assertTrue(prompt.contains("last_tap_semantic_locator"));
        Assert.assertTrue(prompt.contains("current_step_expected: detail page opens"));
        Assert.assertTrue(prompt.contains("last_tap_expected: game page opens"));
        Assert.assertTrue(prompt.contains("current_step_locator_mode: semantic_locator"));
        Assert.assertTrue(prompt.contains("last_tap_locator_mode: xml_locator"));
        Assert.assertTrue(prompt.contains("1000x1000 logical plane"));
        Assert.assertTrue(prompt.contains("last_tap_executed_point_pixels"));
        Assert.assertTrue(prompt.contains("game_button"));
    }

    @Test
    public void parseResult_acceptsJsonAndWrappedJson() {
        TaskMapTapVerificationResult previous = TaskMapTapReplayVerifier.parseResult(
                "{\"observing\":\"old page\",\"judging_prev\":\"still there\",\"judge_prev_result\":\"mismatch\",\"thinking\":\"retry previous\",\"decision\":\"previous\",\"command\":\"TAP 123 456\",\"reason\":\"retry\"}"
        );
        Assert.assertTrue(previous.isPrevious());
        Assert.assertEquals(123, previous.tapX);
        Assert.assertEquals(456, previous.tapY);
        Assert.assertEquals("retry", previous.reason);
        Assert.assertEquals("old page", previous.observing);
        Assert.assertEquals("still there", previous.judgingPrev);
        Assert.assertEquals("mismatch", previous.judgePrevResult);
        Assert.assertEquals("retry previous", previous.thinking);

        TaskMapTapVerificationResult current = TaskMapTapReplayVerifier.parseResult(
                "note before json\n{\"observing\":\"new page\",\"judging_prev\":\"gone\",\"judge_prev_result\":\"match\",\"thinking\":\"execute current\",\"decision\":\"current\",\"command\":\"TAP 7 8\",\"reason\":\"advance\"}\nnote after json"
        );
        Assert.assertTrue(current.isCurrent());
        Assert.assertEquals(7, current.tapX);
        Assert.assertEquals(8, current.tapY);
        Assert.assertEquals("new page", current.observing);
        Assert.assertEquals("execute current", current.thinking);

        TaskMapTapVerificationResult defer = TaskMapTapReplayVerifier.parseResult(
                "{\"observing\":\"loading\",\"judging_prev\":\"unclear\",\"judge_prev_result\":\"unclear\",\"thinking\":\"defer to fallback\",\"decision\":\"defer\",\"command\":\"\",\"reason\":\"loading\"}"
        );
        Assert.assertTrue(defer.isDefer());
        Assert.assertEquals("", defer.command);
        Assert.assertEquals("loading", defer.reason);
        Assert.assertEquals("loading", defer.observing);
        Assert.assertEquals("unclear", defer.judgingPrev);
        Assert.assertEquals("unclear", defer.judgePrevResult);
        Assert.assertEquals("defer to fallback", defer.thinking);
    }

    @Test
    public void parseResult_rejectsInvalidDecisionAndCommand() {
        TaskMapTapVerificationResult invalidDecision = TaskMapTapReplayVerifier.parseResult(
                "{\"decision\":\"future\",\"command\":\"TAP 1 2\",\"reason\":\"bad\"}"
        );
        Assert.assertTrue(invalidDecision.isError());
        Assert.assertTrue(invalidDecision.reason.contains("unsupported_decision"));
        Assert.assertEquals("", invalidDecision.thinking);

        TaskMapTapVerificationResult invalidCommand = TaskMapTapReplayVerifier.parseResult(
                "{\"decision\":\"current\",\"command\":\"SWIPE 1 2 3 4 5\",\"reason\":\"bad\"}"
        );
        Assert.assertTrue(invalidCommand.isError());
        Assert.assertTrue(invalidCommand.reason.contains("invalid_command"));

        TaskMapTapVerificationResult outOfRangeCommand = TaskMapTapReplayVerifier.parseResult(
                "{\"decision\":\"current\",\"command\":\"TAP 1 1001\",\"reason\":\"bad\"}"
        );
        Assert.assertTrue(outOfRangeCommand.isError());
        Assert.assertTrue(outOfRangeCommand.reason.contains("command_coordinates_out_of_range"));

        TaskMapTapVerificationResult invalidDeferCommand = TaskMapTapReplayVerifier.parseResult(
                "{\"decision\":\"defer\",\"command\":\"TAP 1 2\",\"reason\":\"bad\"}"
        );
        Assert.assertTrue(invalidDeferCommand.isError());
        Assert.assertTrue(invalidDeferCommand.reason.contains("invalid_command"));
    }
}
