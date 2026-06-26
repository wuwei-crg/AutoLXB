package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.cortex.taskmap.TaskMap;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TaskMapTapReplayVerifier implements TaskMapTapVerificationResolver {
    public static final String VERIFIER_NAME = "semantic_tap_verifier";

    private final LlmClient llmClient;
    private final TraceLogger trace;

    public TaskMapTapReplayVerifier(LlmClient llmClient, TraceLogger trace) {
        this.llmClient = llmClient;
        this.trace = trace;
    }

    public TaskMapTapVerificationResult verify(TaskMapTapVerificationRequest request) {
        if (request == null) {
            return TaskMapTapVerificationResult.error("request_missing", VERIFIER_NAME);
        }
        if (request.screenshotPng == null || request.screenshotPng.length == 0) {
            return TaskMapTapVerificationResult.error("screenshot_missing", VERIFIER_NAME);
        }
        if (llmClient == null) {
            return TaskMapTapVerificationResult.error("llm_client_missing", VERIFIER_NAME);
        }
        String prompt = buildPrompt(request);
        traceEvent("task_map_post_tap_verifier_begin", request, prompt, null, null);
        try {
            String raw = llmClient.chatOnce(
                    LlmConfig.loadDefaultForRoute(LlmConfig.ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR),
                    null,
                    prompt,
                    request.screenshotPng
            );
            traceEvent("task_map_post_tap_verifier_response", request, null, raw, null);
            TaskMapTapVerificationResult result = parseResult(raw);
            traceEvent("task_map_post_tap_verifier_result", request, null, null, result);
            return result;
        } catch (Exception e) {
            String err = String.valueOf(e);
            traceEvent("task_map_post_tap_verifier_error", request, null, err, null);
            return TaskMapTapVerificationResult.error(err, VERIFIER_NAME);
        }
    }

    public static String buildPrompt(TaskMapTapVerificationRequest request) {
        TaskMap.Step current = request != null ? request.currentStep : null;
        TaskMap.Step lastTap = request != null ? request.lastTapStep : null;
        StringBuilder sb = new StringBuilder();
        sb.append("You are a post-click verifier for Android task-route replay.\n");
        sb.append("You will see the current page screenshot after a TAP action.\n");
        sb.append("Use one response only. Do not split analysis and command into separate turns.\n");
        sb.append("The last TAP locator is the stored last TAP context, not necessarily the immediately previous route step.\n");
        sb.append("If the last TAP has already been retried once, do not choose previous again.\n");
        sb.append("Inspect the last TAP result against the current screenshot, then inspect the current step locator, then choose one decision.\n");
        sb.append("Write the reasoning directly into the JSON fields before the final decision.\n\n");
        sb.append("Return JSON only with this shape:\n");
        sb.append("{\"observing\":\"...\",\"judging_prev\":\"...\",\"judge_prev_result\":\"...\",\"thinking\":\"...\",\"decision\":\"previous\",\"command\":\"TAP 123 456\",\"reason\":\"...\"}\n");
        sb.append("For current, keep the same shape but set \"decision\":\"current\".\n");
        sb.append("For defer, keep the same shape but set \"decision\":\"defer\" and \"command\":\"\".\n");
        sb.append("Command coordinates must be normalized integers in a 1000x1000 logical plane.\n");
        sb.append("Top-left is (0,0), bottom-right is (1000,1000). Do not output screenshot/device pixel coordinates.\n");
        sb.append("The engine will map normalized command coordinates to device pixels after parsing.\n\n");
        sb.append("Context:\n");
        sb.append("- task_id: ").append(stringOrEmpty(request != null ? request.taskId : "")).append("\n");
        sb.append("- route_id: ").append(stringOrEmpty(request != null ? request.routeId : "")).append("\n");
        sb.append("- package: ").append(stringOrEmpty(request != null ? request.packageName : "")).append("\n");
        sb.append("- segment_id: ").append(stringOrEmpty(request != null ? request.segmentId : "")).append("\n");
        sb.append("- step_index: ").append(request != null ? request.stepIndex : -1).append("\n");
        sb.append("- current_step_locator_mode: ").append(stringOrEmpty(request != null ? request.currentStepLocatorMode : "")).append("\n");
        sb.append("- current_step_id: ").append(stringOrEmpty(current != null ? current.stepId : "")).append("\n");
        sb.append("- current_step_expected: ").append(stringOrEmpty(current != null ? current.expected : "")).append("\n");
        sb.append("- current_step_semantic_note: ").append(stringOrEmpty(current != null ? current.semanticNote : "")).append("\n");
        sb.append("- current_step_semantic_locator: ").append(Json.stringify(current != null ? current.semanticLocator : new LinkedHashMap<String, Object>())).append("\n");
        sb.append("- current_step_xml_locator: ").append(Json.stringify(current != null ? current.xmlLocator : new LinkedHashMap<String, Object>())).append("\n");
        sb.append("- last_tap_locator_mode: ").append(stringOrEmpty(request != null ? request.lastTapLocatorMode : "")).append("\n");
        sb.append("- last_tap_step_id: ").append(stringOrEmpty(lastTap != null ? lastTap.stepId : "")).append("\n");
        sb.append("- last_tap_index: ").append(request != null ? request.lastTapIndex : -1).append("\n");
        sb.append("- last_tap_expected: ").append(stringOrEmpty(lastTap != null ? lastTap.expected : "")).append("\n");
        sb.append("- last_tap_retry_count: ").append(request != null ? request.lastTapRetryCount : 0).append("\n");
        sb.append("- last_tap_result_reason: ").append(stringOrEmpty(request != null ? request.lastTapResultReason : "")).append("\n");
        sb.append("- last_tap_executed_point_pixels: [").append(request != null ? request.lastTapX : -1).append(", ").append(request != null ? request.lastTapY : -1).append("]\n");
        sb.append("- last_tap_semantic_locator: ").append(Json.stringify(lastTap != null ? lastTap.semanticLocator : new LinkedHashMap<String, Object>())).append("\n");
        sb.append("- last_tap_xml_locator: ").append(Json.stringify(lastTap != null ? lastTap.xmlLocator : new LinkedHashMap<String, Object>())).append("\n");
        if (request != null && request.historyText != null && !request.historyText.trim().isEmpty()) {
            sb.append("\nExecution history before this step:\n");
            sb.append(request.historyText.trim()).append("\n");
        }
        return sb.toString();
    }

    public static TaskMapTapVerificationResult parseResult(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return TaskMapTapVerificationResult.error("empty_response", VERIFIER_NAME);
        }
        try {
            Map<String, Object> obj = parseResultObject(raw.trim());
            String decision = stringOrEmpty(obj.get("decision"));
            if (decision.isEmpty()) {
                decision = stringOrEmpty(obj.get("result"));
            }
            String command = stringOrEmpty(obj.get("command"));
            String observing = stringOrEmpty(firstPresent(obj, "observing", "Observing"));
            String judgingPrev = stringOrEmpty(firstPresent(obj, "judging_prev", "Judging_prev"));
            String judgePrevResult = stringOrEmpty(firstPresent(obj, "judge_prev_result", "Judge_prev_result"));
            String thinking = stringOrEmpty(firstPresent(obj, "thinking", "Thinking"));
            String reason = stringOrEmpty(obj.get("reason"));
            return TaskMapTapVerificationResult.fromJsonDecision(
                    decision,
                    command,
                    observing,
                    judgingPrev,
                    judgePrevResult,
                    thinking,
                    reason,
                    VERIFIER_NAME
            );
        } catch (Exception e) {
            return TaskMapTapVerificationResult.error("invalid_json:" + e.getMessage(), VERIFIER_NAME);
        }
    }

    private static Map<String, Object> parseResultObject(String raw) {
        try {
            return Json.parseObject(raw);
        } catch (RuntimeException directError) {
            Map<String, Object> extracted = CortexLlmHelper.extractJsonObjectFromText(raw);
            if (!extracted.isEmpty()) {
                return extracted;
            }
            throw directError;
        }
    }

    private void traceEvent(
            String name,
            TaskMapTapVerificationRequest request,
            String prompt,
            String response,
            TaskMapTapVerificationResult result
    ) {
        if (trace == null || request == null) {
            return;
        }
        Map<String, Object> ev = new LinkedHashMap<String, Object>();
        ev.put("task_id", request.taskId);
        ev.put("route_id", request.routeId);
        ev.put("segment_id", request.segmentId);
        ev.put("index", request.stepIndex);
        ev.put("step_id", request.currentStep != null ? request.currentStep.stepId : "");
        ev.put("last_tap_step_id", request.lastTapStep != null ? request.lastTapStep.stepId : "");
        ev.put("decision", result != null ? result.decision : "");
        ev.put("command", result != null ? result.command : "");
        ev.put("resolver", VERIFIER_NAME);
        if (prompt != null) {
            ev.put("prompt", prompt.length() > 3000 ? prompt.substring(0, 3000) + "..." : prompt);
        }
        if (response != null) {
            ev.put("response", response.length() > 2000 ? response.substring(0, 2000) + "..." : response);
        }
        if (result != null) {
            ev.put("reason", result.reason);
            ev.put("tap_x", result.tapX);
            ev.put("tap_y", result.tapY);
            ev.put("observing", result.observing);
            ev.put("judging_prev", result.judgingPrev);
            ev.put("judge_prev_result", result.judgePrevResult);
            ev.put("thinking", result.thinking);
        }
        trace.event(name, ev);
    }

    private static Object firstPresent(Map<String, Object> obj, String keyA, String keyB) {
        if (obj == null) {
            return "";
        }
        Object a = obj.get(keyA);
        if (a != null && !stringOrEmpty(a).isEmpty()) {
            return a;
        }
        Object b = obj.get(keyB);
        return b != null ? b : "";
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
