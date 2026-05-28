package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.cortex.taskmap.TaskMap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Screenshot-only current-step target locator for task-map replay.
 *
 * This resolver is intentionally not a planner: it must return a point or a
 * non-point status for the current route step only.
 */
public final class SemanticVisionStepResolver implements TaskMapStepVisualResolver {
    public static final String RESOLVER_NAME = "semantic_visual";

    private final LlmClient llmClient;
    private final TraceLogger trace;

    public SemanticVisionStepResolver(LlmClient llmClient, TraceLogger trace) {
        this.llmClient = llmClient;
        this.trace = trace;
    }

    @Override
    public StepVisualResolveResult resolve(StepVisualResolveRequest request) {
        if (request == null) {
            return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR, "request_missing", RESOLVER_NAME);
        }
        if (request.screenshotPng == null || request.screenshotPng.length == 0) {
            return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR, "screenshot_missing", RESOLVER_NAME);
        }
        if (llmClient == null) {
            return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR, "llm_client_missing", RESOLVER_NAME);
        }
        String prompt = buildPrompt(request);
        traceEvent("task_map_visual_resolver_begin", request, prompt, null);
        try {
            String raw = llmClient.chatOnce(LlmConfig.loadDefault(), null, prompt, request.screenshotPng);
            traceEvent("task_map_visual_resolver_response", request, null, raw);
            StepVisualResolveResult result = parseResult(raw);
            traceEvent("task_map_visual_resolver_result", request, null,
                    result.status + ":" + result.reason);
            return result;
        } catch (Exception e) {
            String err = String.valueOf(e);
            traceEvent("task_map_visual_resolver_error", request, null, err);
            return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR, err, RESOLVER_NAME);
        }
    }

    public static StepVisualResolveResult parseResult(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR, "empty_response", RESOLVER_NAME);
        }
        try {
            Map<String, Object> obj = Json.parseObject(raw.trim());
            String status = stringOrEmpty(obj.get("result"));
            if (StepVisualResolveResult.STATUS_POINT.equals(StepVisualResolveResult.normalizeStatus(status))) {
                int x = toInt(obj.get("x"), Integer.MIN_VALUE);
                int y = toInt(obj.get("y"), Integer.MIN_VALUE);
                if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
                    return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR, "point_missing_coordinates", RESOLVER_NAME);
                }
                return StepVisualResolveResult.point(x, y, stringOrEmpty(obj.get("reason")), RESOLVER_NAME);
            }
            String normalized = StepVisualResolveResult.normalizeStatus(status);
            if (StepVisualResolveResult.STATUS_ERROR.equals(normalized)
                    && !StepVisualResolveResult.STATUS_ERROR.equals(status)) {
                return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR,
                        "unsupported_status:" + status, RESOLVER_NAME);
            }
            return StepVisualResolveResult.status(normalized, stringOrEmpty(obj.get("reason")), RESOLVER_NAME);
        } catch (Exception e) {
            return StepVisualResolveResult.status(StepVisualResolveResult.STATUS_ERROR,
                    "invalid_json:" + e.getMessage(), RESOLVER_NAME);
        }
    }

    public static String buildPrompt(StepVisualResolveRequest request) {
        TaskMap.Step step = request != null ? request.step : null;
        StringBuilder sb = new StringBuilder();
        sb.append("You are a constrained visual locator for Android task-route replay.\n");
        sb.append("Your only job: locate the tap target for the CURRENT route step in the screenshot.\n");
        sb.append("Do not plan the user's task. Do not choose the next business action. Do not output TAP/BACK/WAIT commands.\n");
        sb.append("Coordinates must be screenshot/device pixel coordinates, not normalized 0-1000 action coordinates.\n");
        sb.append("Return JSON only with one of these shapes:\n");
        sb.append("{\"result\":\"point\",\"x\":123,\"y\":456,\"reason\":\"...\"}\n");
        sb.append("{\"result\":\"no_match\",\"reason\":\"target is not visible\"}\n");
        sb.append("{\"result\":\"ambiguous\",\"reason\":\"multiple possible targets\"}\n");
        sb.append("{\"result\":\"blocked\",\"reason\":\"popup or overlay blocks the target\"}\n\n");
        sb.append("Context:\n");
        sb.append("- package: ").append(stringOrEmpty(request != null ? request.packageName : "")).append("\n");
        sb.append("- route_id: ").append(stringOrEmpty(request != null ? request.routeId : "")).append("\n");
        sb.append("- segment_id: ").append(stringOrEmpty(request != null ? request.segmentId : "")).append("\n");
        sb.append("- step_index: ").append(request != null ? request.stepIndex : -1).append("\n");
        sb.append("- step_id: ").append(stringOrEmpty(step != null ? step.stepId : "")).append("\n");
        sb.append("- source_action_id: ").append(stringOrEmpty(step != null ? step.sourceActionId : "")).append("\n");
        sb.append("- op: ").append(stringOrEmpty(step != null ? step.op : "")).append("\n");
        sb.append("- expected: ").append(stringOrEmpty(step != null ? step.expected : "")).append("\n");
        sb.append("- semantic_note: ").append(stringOrEmpty(step != null ? step.semanticNote : "")).append("\n");
        sb.append("- locator_failure: ").append(stringOrEmpty(request != null ? request.locatorFailureReason : "")).append("\n");
        Map<String, Object> history = step != null && step.history != null
                ? step.history
                : new LinkedHashMap<String, Object>();
        if (!history.isEmpty()) {
            sb.append("Step history seed:\n");
            sb.append("- instruction: ").append(stringOrEmpty(history.get(CortexExecutionHistory.KEY_INSTRUCTION))).append("\n");
            sb.append("- expected: ").append(stringOrEmpty(history.get(CortexExecutionHistory.KEY_EXPECTED))).append("\n");
            sb.append("- carry_context: ").append(stringOrEmpty(history.get(CortexExecutionHistory.KEY_CARRY_CONTEXT))).append("\n");
        }
        if (step != null && step.semanticDescriptor != null && !step.semanticDescriptor.isEmpty()) {
            sb.append("Semantic descriptor: ").append(Json.stringify(step.semanticDescriptor)).append("\n");
        }
        String historyText = request != null ? stringOrEmpty(request.historyText) : "";
        if (!historyText.isEmpty()) {
            sb.append("\nExecution history before this step:\n");
            sb.append(historyText).append("\n");
        }
        sb.append("\nIf the current step target is not visible, return no_match. If blocked by a large popup/overlay, return blocked.\n");
        return sb.toString();
    }

    private void traceEvent(String name, StepVisualResolveRequest request, String prompt, String response) {
        if (trace == null || request == null) {
            return;
        }
        Map<String, Object> ev = new LinkedHashMap<String, Object>();
        ev.put("task_id", request.taskId);
        ev.put("route_id", request.routeId);
        ev.put("segment_id", request.segmentId);
        ev.put("index", request.stepIndex);
        ev.put("step_id", request.step != null ? request.step.stepId : "");
        ev.put("resolver", RESOLVER_NAME);
        if (prompt != null) {
            ev.put("prompt", prompt.length() > 3000 ? prompt.substring(0, 3000) + "..." : prompt);
        }
        if (response != null) {
            ev.put("response", response.length() > 2000 ? response.substring(0, 2000) + "..." : response);
        }
        trace.event(name, ev);
    }

    private static int toInt(Object o, int defVal) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
