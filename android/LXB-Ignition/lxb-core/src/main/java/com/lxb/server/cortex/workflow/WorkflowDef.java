package com.lxb.server.cortex.workflow;

import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class WorkflowDef {

    public static final String TRIGGER_NONE = "none";
    public static final String TRIGGER_SCHEDULE = "schedule";
    public static final String TRIGGER_NOTIFICATION = "notification";
    public static final String FAILURE_STOP = "stop_on_failure";
    public static final String FAILURE_CONTINUE = "continue_on_failure";

    public String workflowId = "";
    public String name = "";
    public String description = "";
    public String triggerType = TRIGGER_NONE;
    public boolean triggerEnabled = false;
    public Map<String, Object> triggerConfig = new LinkedHashMap<String, Object>();
    public String failurePolicy = FAILURE_STOP;
    public String legacyKind = "";
    public String legacyId = "";
    public long createdAtMs = 0L;
    public long updatedAtMs = 0L;
    public final List<Step> steps = new ArrayList<Step>();

    public static final class Step {
        public String stepId = "";
        public String templateId = "";
        public String name = "";
        public int order = 0;

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("step_id", stepId);
            out.put("template_id", templateId);
            out.put("name", name);
            out.put("order", order);
            return out;
        }

        public static Step fromMap(Map<String, Object> row, int fallbackOrder) {
            if (row == null) return null;
            Step out = new Step();
            out.stepId = TaskTemplate.stringOrEmpty(row.get("step_id"));
            out.templateId = TaskTemplate.stringOrEmpty(row.get("template_id"));
            out.name = TaskTemplate.stringOrEmpty(row.get("name"));
            out.order = TaskTemplate.toInt(row.get("order"), fallbackOrder);
            if (out.stepId.isEmpty()) {
                out.stepId = "step_" + UUID.randomUUID().toString();
            }
            if (out.templateId.isEmpty()) {
                return null;
            }
            return out;
        }
    }

    public static WorkflowDef createNew(String name) {
        long now = System.currentTimeMillis();
        WorkflowDef out = new WorkflowDef();
        out.workflowId = "wf_" + UUID.randomUUID().toString();
        out.name = TaskTemplate.stringOrEmpty(name);
        out.createdAtMs = now;
        out.updatedAtMs = now;
        return out;
    }

    public void normalizeForSave() {
        workflowId = TaskTemplate.stringOrEmpty(workflowId);
        if (workflowId.isEmpty()) {
            workflowId = "wf_" + UUID.randomUUID().toString();
        }
        name = TaskTemplate.stringOrEmpty(name);
        description = TaskTemplate.stringOrEmpty(description);
        triggerType = normalizeTriggerType(triggerType);
        if (TRIGGER_NONE.equals(triggerType)) {
            triggerEnabled = false;
            triggerConfig = new LinkedHashMap<String, Object>();
        } else if (triggerConfig == null) {
            triggerConfig = new LinkedHashMap<String, Object>();
        }
        failurePolicy = normalizeFailurePolicy(failurePolicy);
        legacyKind = TaskTemplate.stringOrEmpty(legacyKind);
        legacyId = TaskTemplate.stringOrEmpty(legacyId);
        long now = System.currentTimeMillis();
        if (createdAtMs <= 0L) {
            createdAtMs = now;
        }
        updatedAtMs = now;
        int i = 0;
        for (Step step : steps) {
            if (step == null) continue;
            if (step.stepId == null || step.stepId.trim().isEmpty()) {
                step.stepId = "step_" + UUID.randomUUID().toString();
            }
            step.templateId = TaskTemplate.stringOrEmpty(step.templateId);
            step.name = TaskTemplate.stringOrEmpty(step.name);
            step.order = i++;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("workflow_id", workflowId);
        out.put("name", name);
        out.put("description", description);
        out.put("trigger_type", triggerType);
        out.put("trigger_enabled", triggerEnabled);
        out.put("trigger_config", new LinkedHashMap<String, Object>(triggerConfig));
        out.put("trigger_summary", buildTriggerSummary());
        out.put("failure_policy", failurePolicy);
        out.put("legacy_kind", legacyKind);
        out.put("legacy_id", legacyId);
        out.put("created_at_ms", createdAtMs);
        out.put("updated_at_ms", updatedAtMs);
        List<Object> rows = new ArrayList<Object>();
        for (Step step : steps) {
            if (step != null) {
                rows.add(step.toMap());
            }
        }
        out.put("steps", rows);
        return out;
    }

    public String buildTriggerSummary() {
        if (TRIGGER_NONE.equals(triggerType)) {
            return "No trigger";
        }
        if (TRIGGER_NOTIFICATION.equals(triggerType)) {
            String pkg = TaskTemplate.stringOrEmpty(triggerConfig.get("package_list"));
            return pkg.isEmpty() ? "Notification" : "Notification " + pkg;
        }
        if (TRIGGER_SCHEDULE.equals(triggerType)) {
            String repeat = TaskTemplate.stringOrEmpty(triggerConfig.get("repeat_mode"));
            long next = TaskTemplate.toLong(triggerConfig.get("next_run_at"), 0L);
            long runAt = TaskTemplate.toLong(triggerConfig.get("run_at"), 0L);
            long ts = next > 0L ? next : runAt;
            if (ts > 0L) {
                String time = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(ts));
                if ("daily".equals(repeat)) return "Daily " + time.substring(6);
                if ("weekly".equals(repeat)) return "Weekly " + time.substring(6);
                return "Once " + time;
            }
            return "Scheduled";
        }
        return triggerType;
    }

    @SuppressWarnings("unchecked")
    public static WorkflowDef fromMap(Map<String, Object> row) {
        if (row == null) return null;
        WorkflowDef out = new WorkflowDef();
        out.workflowId = TaskTemplate.stringOrEmpty(row.get("workflow_id"));
        out.name = TaskTemplate.stringOrEmpty(row.get("name"));
        out.description = TaskTemplate.stringOrEmpty(row.get("description"));
        out.triggerType = normalizeTriggerType(TaskTemplate.stringOrEmpty(row.get("trigger_type")));
        out.triggerEnabled = TaskTemplate.toBool(row.get("trigger_enabled"), false);
        Object configObj = row.get("trigger_config");
        if (configObj instanceof Map) {
            out.triggerConfig.putAll((Map<String, Object>) configObj);
        }
        out.failurePolicy = normalizeFailurePolicy(TaskTemplate.stringOrEmpty(row.get("failure_policy")));
        out.legacyKind = TaskTemplate.stringOrEmpty(row.get("legacy_kind"));
        out.legacyId = TaskTemplate.stringOrEmpty(row.get("legacy_id"));
        out.createdAtMs = TaskTemplate.toLong(row.get("created_at_ms"), TaskTemplate.toLong(row.get("created_at"), 0L));
        out.updatedAtMs = TaskTemplate.toLong(row.get("updated_at_ms"), out.createdAtMs);
        Object stepsObj = row.get("steps");
        if (stepsObj instanceof List) {
            int idx = 0;
            for (Object o : (List<Object>) stepsObj) {
                if (!(o instanceof Map)) {
                    idx++;
                    continue;
                }
                Step step = Step.fromMap((Map<String, Object>) o, idx++);
                if (step != null) {
                    out.steps.add(step);
                }
            }
        }
        return out;
    }

    public static String normalizeTriggerType(String raw) {
        String v = TaskTemplate.stringOrEmpty(raw).toLowerCase(Locale.ROOT);
        if (TRIGGER_SCHEDULE.equals(v) || TRIGGER_NOTIFICATION.equals(v)) {
            return v;
        }
        return TRIGGER_NONE;
    }

    public static String normalizeFailurePolicy(String raw) {
        String v = TaskTemplate.stringOrEmpty(raw).toLowerCase(Locale.ROOT);
        if (FAILURE_CONTINUE.equals(v)) {
            return FAILURE_CONTINUE;
        }
        return FAILURE_STOP;
    }
}
