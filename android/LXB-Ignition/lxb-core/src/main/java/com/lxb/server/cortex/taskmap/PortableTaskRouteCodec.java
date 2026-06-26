package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PortableTaskRouteCodec {

    public static final String PORTABLE_SCHEMA = "task_route_asset.v1";
    public static final String LEGACY_PORTABLE_SCHEMA = "task_route_portable.v1";
    @Deprecated
    public static final String PORTABLE_KIND_LOCAL_LOCATOR = "local_locator";
    @Deprecated
    public static final String PORTABLE_KIND_SEMANTIC_TAP = "semantic_tap";
    @Deprecated
    public static final String PORTABLE_KIND_MATERIALIZED = "materialized";
    @Deprecated
    public static final String ADAPTATION_STATUS_NONE = "none";
    @Deprecated
    public static final String ADAPTATION_STATUS_PENDING = "pending";
    @Deprecated
    public static final String ADAPTATION_STATUS_ADAPTED = "adapted";
    @Deprecated
    public static final String ADAPTATION_STATUS_FAILED = "failed";

    public static final class ExportResult {
        public Map<String, Object> bundle = new LinkedHashMap<String, Object>();
        public int xmlLocatorStepCount;
        public int semanticLocatorStepCount;
    }

    public static final class ImportResult {
        public TaskMap map;
        public Map<String, Object> taskInfo = new LinkedHashMap<String, Object>();
        public int xmlLocatorStepCount;
        public int semanticLocatorStepCount;
    }

    private PortableTaskRouteCodec() {}

    public static ExportResult exportPortable(TaskMap map, TaskRouteRecord record) {
        if (map == null) {
            throw new IllegalArgumentException("task_map_missing");
        }
        ExportResult out = new ExportResult();
        Map<String, TaskRouteRecord.Action> actions = indexActions(record);
        Map<String, Object> bundle = new LinkedHashMap<String, Object>();
        bundle.put("schema", PORTABLE_SCHEMA);

        Map<String, Object> taskInfo = buildTaskInfo(map, record);
        // The portable asset contract is task_info + route behavior + segments only.
        // It must not carry schedule metadata, run timestamps, or duplicated legacy
        // source_task data; imported routes become new local task assets.
        bundle.put("task_info", taskInfo);
        bundle.put("finish_after_replay", map.finishAfterReplay);

        List<Object> segmentRows = new ArrayList<Object>();
        for (TaskMap.Segment segment : map.segments) {
            Map<String, Object> segRow = new LinkedHashMap<String, Object>();
            segRow.put("segment_id", segment.segmentId);
            segRow.put("sub_task_id", segment.subTaskId);
            segRow.put("sub_task_index", segment.subTaskIndex);
            segRow.put("sub_task_description", segment.subTaskDescription);
            segRow.put("success_criteria", segment.successCriteria);
            segRow.put("package_name", segment.packageName);
            segRow.put("package_label", segment.packageLabel);
            segRow.put("inputs", new ArrayList<String>(segment.inputs));
            segRow.put("outputs", new ArrayList<String>(segment.outputs));

            List<Object> stepRows = new ArrayList<Object>();
            for (TaskMap.Step step : segment.steps) {
                stepRows.add(exportStep(step, actions.get(step.sourceActionId), out));
            }
            segRow.put("steps", stepRows);
            segmentRows.add(segRow);
        }
        bundle.put("segments", segmentRows);
        out.bundle = bundle;
        return out;
    }

    public static ImportResult importPortable(String targetTaskKeyHash, String targetPackageName, String bundleJson) {
        return importPortable(targetTaskKeyHash, targetPackageName, Json.parseObject(bundleJson));
    }

    @SuppressWarnings("unchecked")
    public static ImportResult importPortable(String targetTaskKeyHash, String targetPackageName, Map<String, Object> bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("portable_bundle_missing");
        }
        String schema = stringOrEmpty(bundle.get("schema"));
        if (!PORTABLE_SCHEMA.equals(schema) && !LEGACY_PORTABLE_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("invalid_portable_schema:" + schema);
        }
        Map<String, Object> sourceTask = bundle.get("task_info") instanceof Map
                ? (Map<String, Object>) bundle.get("task_info")
                : (bundle.get("source_task") instanceof Map
                        ? (Map<String, Object>) bundle.get("source_task")
                        : new LinkedHashMap<String, Object>());
        String sourcePackageName = stringOrEmpty(sourceTask.get("package_name"));
        String requestedPackage = stringOrEmpty(targetPackageName);
        if (!requestedPackage.isEmpty() && !sourcePackageName.isEmpty() && !requestedPackage.equals(sourcePackageName)) {
            throw new IllegalArgumentException("portable_package_mismatch:" + sourcePackageName + "!=" + requestedPackage);
        }
        TaskMap map = new TaskMap();
        map.taskKeyHash = stringOrEmpty(targetTaskKeyHash);
        map.source = "portable_import";
        map.sourceId = firstNonBlank(
                stringOrEmpty(sourceTask.get("route_id")),
                stringOrEmpty(sourceTask.get("task_id"))
        );
        map.packageName = !requestedPackage.isEmpty() ? requestedPackage : sourcePackageName;
        map.packageLabel = stringOrEmpty(sourceTask.get("package_label"));
        map.createdFromTaskId = stringOrEmpty(sourceTask.get("task_id"));
        map.createdAtMs = System.currentTimeMillis();
        map.mode = "manual";
        map.finishAfterReplay = toBoolean(bundle.get("finish_after_replay"), false);
        map.lastReplayStatus = "unused";

        ImportResult result = new ImportResult();
        result.map = map;
        result.taskInfo.putAll(sourceTask);

        Object segObj = bundle.get("segments");
        if (segObj instanceof List) {
            for (Object segItem : (List<Object>) segObj) {
                if (!(segItem instanceof Map)) {
                    continue;
                }
                Map<String, Object> segRow = (Map<String, Object>) segItem;
                TaskMap.Segment segment = new TaskMap.Segment();
                segment.segmentId = stringOrEmpty(segRow.get("segment_id"));
                segment.subTaskId = stringOrEmpty(segRow.get("sub_task_id"));
                segment.subTaskIndex = (int) toLong(segRow.get("sub_task_index"), 0L);
                segment.subTaskDescription = stringOrEmpty(segRow.get("sub_task_description"));
                segment.subTaskDescriptionHash = "";
                segment.successCriteria = stringOrEmpty(segRow.get("success_criteria"));
                segment.packageName = !map.packageName.isEmpty() ? map.packageName : stringOrEmpty(segRow.get("package_name"));
                segment.packageLabel = !map.packageLabel.isEmpty() ? map.packageLabel : stringOrEmpty(segRow.get("package_label"));
                copyStringList(segRow.get("inputs"), segment.inputs);
                copyStringList(segRow.get("outputs"), segment.outputs);

                Object stepsObj = segRow.get("steps");
                if (stepsObj instanceof List) {
                    for (Object stepItem : (List<Object>) stepsObj) {
                        if (!(stepItem instanceof Map)) {
                            continue;
                        }
                        TaskMap.Step step = importStep((Map<String, Object>) stepItem);
                        if ("TAP".equals(normalizeOp(step.op))) {
                            if (step.xmlLocator != null && !step.xmlLocator.isEmpty()) {
                                result.xmlLocatorStepCount += 1;
                            }
                            if (step.semanticLocator != null && !step.semanticLocator.isEmpty()) {
                                result.semanticLocatorStepCount += 1;
                            }
                        }
                        segment.steps.add(step);
                    }
                }
                if (!segment.packageName.isEmpty() && !segment.steps.isEmpty()) {
                    map.segments.add(segment);
                }
            }
        }
        return result;
    }

    private static Map<String, Object> exportStep(TaskMap.Step step, TaskRouteRecord.Action action, ExportResult counters) {
        String op = normalizeOp(step != null ? step.op : "");
        if ("SWIPE".equals(op)) {
            throw new IllegalArgumentException("unsupported_portable_op:SWIPE");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("step_id", step != null ? step.stepId : "");
        out.put("source_action_id", step != null ? step.sourceActionId : "");
        out.put("op", op);
        out.put("args", new ArrayList<String>(step != null ? step.args : new ArrayList<String>()));
        out.put("expected", step != null ? step.expected : "");

        if (!"TAP".equals(op)) {
            return out;
        }

        Map<String, Object> xmlLocator = step != null && step.xmlLocator != null
                ? step.xmlLocator
                : new LinkedHashMap<String, Object>();
        Map<String, Object> semanticLocator = step != null && step.semanticLocator != null
                ? step.semanticLocator
                : new LinkedHashMap<String, Object>();
        if (semanticLocator.isEmpty()) {
            semanticLocator = SemanticTapDescriptor.build(step, action);
        }
        if (!xmlLocator.isEmpty()) {
            out.put("xml_locator", new LinkedHashMap<String, Object>(xmlLocator));
            counters.xmlLocatorStepCount += 1;
        }
        out.put("semantic_locator", new LinkedHashMap<String, Object>(semanticLocator));
        counters.semanticLocatorStepCount += 1;
        return out;
    }

    private static TaskMap.Step importStep(Map<String, Object> row) {
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = stringOrEmpty(row.get("step_id"));
        step.sourceActionId = stringOrEmpty(row.get("source_action_id"));
        step.op = normalizeOp(stringOrEmpty(row.get("op")));
        copyStringList(row.get("args"), step.args);
        step.expected = stringOrEmpty(row.get("expected"));

        Object xmlLocatorObj = row.get("xml_locator");
        if (xmlLocatorObj == null) {
            xmlLocatorObj = row.get("locator");
        }
        if (xmlLocatorObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> xmlLocator = (Map<String, Object>) xmlLocatorObj;
            step.xmlLocator.putAll(xmlLocator);
        }

        Object semanticLocatorObj = row.get("semantic_locator");
        if (semanticLocatorObj == null) {
            semanticLocatorObj = row.get("semantic_descriptor");
        }
        if (semanticLocatorObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> semanticLocator = (Map<String, Object>) semanticLocatorObj;
            step.semanticLocator.putAll(semanticLocator);
            step.semanticNote = firstNonBlank(
                    stringOrEmpty(semanticLocator.get("page_context")),
                    stringOrEmpty(semanticLocator.get("source_observation"))
            );
            if (step.expected.isEmpty()) {
                step.expected = stringOrEmpty(semanticLocator.get("expected_after_tap"));
            }
        }

        step.portableKind = stringOrEmpty(row.get("portable_kind"));
        step.adaptationStatus = stringOrEmpty(row.get("adaptation_status"));
        step.adaptationError = stringOrEmpty(row.get("adaptation_error"));
        step.adaptationAttemptedAtMs = toLong(row.get("adaptation_attempted_at_ms"), 0L);
        step.materializedFromStepId = stringOrEmpty(row.get("materialized_from_step_id"));
        step.materializedAtMs = toLong(row.get("materialized_at_ms"), 0L);

        if ("TAP".equals(step.op) && step.semanticLocator.isEmpty()) {
            step.semanticLocator.putAll(buildFallbackSemanticLocator(step, row));
        }
        return step;
    }


    private static Map<String, Object> buildTaskInfo(TaskMap map, TaskRouteRecord record) {
        Map<String, Object> task = new LinkedHashMap<String, Object>();
        task.put("user_task", firstNonBlank(
                stringOrEmpty(record != null ? record.rootTask : ""),
                firstSegmentDescription(map)
        ));
        task.put("package_name", firstNonBlank(
                stringOrEmpty(record != null ? record.packageName : ""),
                stringOrEmpty(map != null ? map.packageName : "")
        ));
        task.put("package_label", firstNonBlank(
                stringOrEmpty(record != null ? record.packageLabel : ""),
                stringOrEmpty(map != null ? map.packageLabel : "")
        ));
        task.put("task_map_mode", firstNonBlank(
                stringOrEmpty(record != null ? record.taskMapMode : ""),
                stringOrEmpty(map != null ? map.mode : "")
        ));
        return task;
    }

    private static Map<String, TaskRouteRecord.Action> indexActions(TaskRouteRecord record) {
        Map<String, TaskRouteRecord.Action> out = new LinkedHashMap<String, TaskRouteRecord.Action>();
        if (record == null || record.actions == null) {
            return out;
        }
        for (TaskRouteRecord.Action action : record.actions) {
            if (action == null || stringOrEmpty(action.actionId).isEmpty()) {
                continue;
            }
            out.put(action.actionId, action);
        }
        return out;
    }

    private static String firstSegmentDescription(TaskMap map) {
        if (map == null || map.segments.isEmpty()) {
            return "";
        }
        return stringOrEmpty(map.segments.get(0).subTaskDescription);
    }

    private static void copyStringList(Object src, List<String> out) {
        if (!(src instanceof List) || out == null) {
            return;
        }
        for (Object item : (List<Object>) src) {
            String value = stringOrEmpty(item);
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
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

    private static Map<String, Object> buildFallbackSemanticLocator(TaskMap.Step step, Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String semanticNote = firstNonBlank(
                stringOrEmpty(row.get("semantic_note")),
                stringOrEmpty(step != null ? step.semanticNote : "")
        );
        String expected = firstNonBlank(
                stringOrEmpty(row.get("expected")),
                stringOrEmpty(step != null ? step.expected : "")
        );
        String instruction = firstNonBlank(semanticNote, expected, "点击目标控件");
        out.put("version", 1);
        out.put("instruction", instruction);
        out.put("target_name", "");
        out.put("target_role", "");
        out.put("visual_hint", "");
        out.put("page_context", semanticNote);
        out.put("expected_after_tap", expected);
        out.put("source_observation", semanticNote);
        out.put("source_command", stringOrEmpty(row.get("raw_command")));
        out.put("descriptor_quality", "weak");
        return out;
    }

    private static boolean toBoolean(Object v, boolean def) {
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) {
            return false;
        }
        return def;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static long toLong(Object o, long defVal) {
        if (o == null) {
            return defVal;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }
}
