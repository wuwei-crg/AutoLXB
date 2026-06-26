package com.lxb.server.cortex.taskmap;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class SemanticTapDescriptor {
    private SemanticTapDescriptor() {}

    static boolean hasSemanticContext(TaskMap.Step step, TaskRouteRecord.Action action) {
        if (step != null) {
            if (!stringOrEmpty(step.semanticNote).isEmpty()
                    || !stringOrEmpty(step.expected).isEmpty()
                    || (step.history != null && !step.history.isEmpty())
                    || (step.semanticLocator != null && !step.semanticLocator.isEmpty())) {
                return true;
            }
        }
        if (action != null) {
            if (!stringOrEmpty(action.createdPageSemantics).isEmpty()
                    || (action.vision != null && !action.vision.isEmpty())) {
                return true;
            }
            return !stringOrEmpty(action.rawCommand).isEmpty()
                    && !normalizeOp(action.rawCommand).startsWith("TAP");
        }
        return false;
    }

    static Map<String, Object> build(TaskMap.Step step, TaskRouteRecord.Action action) {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("version", 1);
        String instruction = firstNonBlank(
                visionText(action, "action"),
                stringOrEmpty(step != null ? step.semanticNote : ""),
                stringOrEmpty(action != null ? action.createdPageSemantics : ""),
                humanizeRawCommand(action != null ? action.rawCommand : ""),
                "点击目标控件"
        );
        String expected = firstNonBlank(
                visionText(action, "expected"),
                stringOrEmpty(step != null ? step.expected : ""),
                ""
        );
        String pageContext = firstNonBlank(
                stringOrEmpty(step != null ? step.semanticNote : ""),
                stringOrEmpty(action != null ? action.createdPageSemantics : "")
        );
        String targetName = firstNonBlank(
                stringOrEmpty(action != null ? action.vision.get("target_name") : null),
                guessTargetName(instruction, expected)
        );
        String targetRole = firstNonBlank(
                stringOrEmpty(action != null ? action.vision.get("target_role") : null),
                guessTargetRole(instruction)
        );
        String visualHint = firstNonBlank(
                stringOrEmpty(action != null ? action.vision.get("visual_hint") : null),
                targetName
        );
        String sourceObservation = firstNonBlank(
                stringOrEmpty(action != null ? action.createdPageSemantics : ""),
                stringOrEmpty(step != null ? step.semanticNote : "")
        );
        descriptor.put("instruction", instruction);
        descriptor.put("target_name", targetName);
        descriptor.put("target_role", targetRole);
        descriptor.put("visual_hint", visualHint);
        descriptor.put("page_context", pageContext);
        descriptor.put("expected_after_tap", expected);
        descriptor.put("source_observation", sourceObservation);
        descriptor.put("source_command", stringOrEmpty(action != null ? action.rawCommand : ""));
        descriptor.put("descriptor_quality", hasStrongDescriptor(instruction, targetName, pageContext, sourceObservation) ? "strong" : "weak");
        return descriptor;
    }

    private static String visionText(TaskRouteRecord.Action action, String key) {
        if (action == null || action.vision == null || action.vision.isEmpty()) {
            return "";
        }
        Object value = action.vision.get(key);
        if (value == null && "action".equals(key)) {
            value = action.vision.get("Action");
        }
        if (value == null && "expected".equals(key)) {
            value = action.vision.get("expected");
        }
        return stringOrEmpty(value);
    }

    private static String humanizeRawCommand(String rawCommand) {
        String raw = stringOrEmpty(rawCommand);
        if (raw.isEmpty()) {
            return "";
        }
        if (raw.toUpperCase(Locale.ROOT).startsWith("TAP")) {
            return "";
        }
        return raw;
    }

    private static String guessTargetName(String instruction, String expected) {
        String merged = firstNonBlank(instruction, expected);
        if (merged.contains("发布")) return "发布";
        if (merged.contains("朋友圈")) return "朋友圈";
        if (merged.contains("发现")) return "发现";
        if (merged.contains("加号") || merged.contains("+")) return "加号按钮";
        return "";
    }

    private static String guessTargetRole(String instruction) {
        String lower = stringOrEmpty(instruction).toLowerCase(Locale.ROOT);
        if (lower.contains("tab")) return "tab";
        if (lower.contains("button") || lower.contains("按钮") || lower.contains("入口")) return "button";
        return "unknown";
    }

    private static boolean hasStrongDescriptor(String instruction, String targetName, String pageContext, String sourceObservation) {
        int nonBlankCount = 0;
        if (!stringOrEmpty(instruction).isEmpty()) nonBlankCount += 1;
        if (!stringOrEmpty(targetName).isEmpty()) nonBlankCount += 1;
        if (!stringOrEmpty(pageContext).isEmpty()) nonBlankCount += 1;
        if (!stringOrEmpty(sourceObservation).isEmpty()) nonBlankCount += 1;
        return nonBlankCount >= 2 && !"点击目标控件".equals(stringOrEmpty(instruction));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = stringOrEmpty(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static String normalizeOp(String op) {
        return stringOrEmpty(op).toUpperCase(Locale.ROOT);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
