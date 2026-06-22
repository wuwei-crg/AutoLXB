package com.lxb.server.cortex.workflow;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.cortex.taskmap.PortableTaskRouteCodec;
import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapStore;
import com.lxb.server.cortex.taskmap.TaskRouteRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class WorkflowPortableCodec {

    public static final String SCHEMA_WORKFLOW_BUNDLE = "workflow_bundle";
    public static final String SCHEMA_TEMPLATE_BUNDLE = "task_template_bundle";
    public static final int VERSION = 1;

    public static final class ImportResult {
        public String importedType = "";
        public String workflowId = "";
        public final List<String> templateIds = new ArrayList<String>();
        public int routeCount;
        public int pendingAdaptationCount;
        public int materializedCount;

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("imported_type", importedType);
            out.put("workflow_id", workflowId);
            out.put("template_id", templateIds.isEmpty() ? "" : templateIds.get(0));
            out.put("template_ids", new ArrayList<String>(templateIds));
            out.put("route_count", routeCount);
            out.put("pending_adaptation_count", pendingAdaptationCount);
            out.put("materialized_count", materializedCount);
            return out;
        }
    }

    private WorkflowPortableCodec() {}

    public static Map<String, Object> exportTemplate(TaskTemplate template, TaskMapStore taskMapStore) {
        if (template == null) {
            throw new IllegalArgumentException("template is required");
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema", SCHEMA_TEMPLATE_BUNDLE);
        root.put("version", VERSION);
        root.put("template", exportTemplateRow(template, taskMapStore));
        return root;
    }

    public static Map<String, Object> exportWorkflow(WorkflowDef workflow, WorkflowStore workflowStore, TaskMapStore taskMapStore) {
        if (workflow == null) {
            throw new IllegalArgumentException("workflow is required");
        }
        if (workflowStore == null) {
            throw new IllegalArgumentException("workflow store is required");
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema", SCHEMA_WORKFLOW_BUNDLE);
        root.put("version", VERSION);
        root.put("workflow", exportWorkflowRow(workflow));

        List<Object> templates = new ArrayList<Object>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (WorkflowDef.Step step : workflow.steps) {
            String templateId = TaskTemplate.stringOrEmpty(step != null ? step.templateId : "");
            if (templateId.isEmpty() || seen.contains(templateId)) {
                continue;
            }
            TaskTemplate template = workflowStore.getTemplate(templateId);
            if (template == null) {
                throw new IllegalArgumentException("template not found: " + templateId);
            }
            templates.add(exportTemplateRow(template, taskMapStore));
            seen.add(templateId);
        }
        root.put("templates", templates);
        return root;
    }

    @SuppressWarnings("unchecked")
    public static ImportResult importPortable(Map<String, Object> bundle, WorkflowStore workflowStore, TaskMapStore taskMapStore) {
        if (bundle == null) {
            throw new IllegalArgumentException("portable bundle is required");
        }
        if (workflowStore == null || taskMapStore == null) {
            throw new IllegalArgumentException("portable stores are required");
        }
        String schema = str(bundle.get("schema"));
        int version = toInt(bundle.get("version"), 0);
        if (SCHEMA_WORKFLOW_BUNDLE.equals(schema)) {
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported workflow_bundle version: " + version);
            }
            Object workflowObj = bundle.get("workflow");
            Object templatesObj = bundle.get("templates");
            if (!(workflowObj instanceof Map)) {
                throw new IllegalArgumentException("workflow is required");
            }
            if (!(templatesObj instanceof List)) {
                throw new IllegalArgumentException("templates are required");
            }
            return importWorkflowBundle((Map<String, Object>) workflowObj, (List<Object>) templatesObj, workflowStore, taskMapStore);
        }
        if (SCHEMA_TEMPLATE_BUNDLE.equals(schema)) {
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported task_template_bundle version: " + version);
            }
            Object templateObj = bundle.get("template");
            if (!(templateObj instanceof Map)) {
                throw new IllegalArgumentException("template is required");
            }
            return importTemplateOnly((Map<String, Object>) templateObj, workflowStore, taskMapStore, "template");
        }
        if (PortableTaskRouteCodec.PORTABLE_SCHEMA.equals(schema) || PortableTaskRouteCodec.LEGACY_PORTABLE_SCHEMA.equals(schema)) {
            String taskType = normalizeLegacyTaskType(str(bundle.get("task_type")));
            if ("schedule".equals(taskType) || "notify_trigger".equals(taskType)) {
                return importLegacyTaskBundle(bundle, taskType, workflowStore, taskMapStore);
            }
            return importPureRouteBundle(bundle, workflowStore, taskMapStore);
        }
        throw new IllegalArgumentException("unsupported portable schema: " + schema);
    }

    @SuppressWarnings("unchecked")
    private static ImportResult importWorkflowBundle(
            Map<String, Object> workflowRow,
            List<Object> templateRows,
            WorkflowStore workflowStore,
            TaskMapStore taskMapStore
    ) {
        LinkedHashSet<String> referenced = new LinkedHashSet<String>();
        Object stepsObj = workflowRow.get("steps");
        if (!(stepsObj instanceof List) || ((List<Object>) stepsObj).isEmpty()) {
            throw new IllegalArgumentException("workflow steps are required");
        }
        for (Object stepObj : (List<Object>) stepsObj) {
            if (!(stepObj instanceof Map)) {
                continue;
            }
            String templateId = str(((Map<String, Object>) stepObj).get("template_id"));
            if (templateId.isEmpty()) {
                throw new IllegalArgumentException("workflow step template_id is required");
            }
            referenced.add(templateId);
        }

        LinkedHashMap<String, Map<String, Object>> templatesById = new LinkedHashMap<String, Map<String, Object>>();
        for (Object templateObj : templateRows) {
            if (!(templateObj instanceof Map)) {
                continue;
            }
            Map<String, Object> templateRow = (Map<String, Object>) templateObj;
            String templateId = str(templateRow.get("template_id"));
            if (templateId.isEmpty()) {
                throw new IllegalArgumentException("embedded template_id is required");
            }
            if (templatesById.containsKey(templateId)) {
                throw new IllegalArgumentException("duplicate embedded template_id: " + templateId);
            }
            templatesById.put(templateId, templateRow);
        }
        if (!templatesById.keySet().equals(referenced)) {
            throw new IllegalArgumentException("workflow embedded templates must exactly match step references");
        }

        ImportPlan plan = new ImportPlan();
        LinkedHashMap<String, TaskTemplate> importedByOldId = new LinkedHashMap<String, TaskTemplate>();
        for (String oldId : templatesById.keySet()) {
            PlannedTemplate planned = buildPlannedTemplate(templatesById.get(oldId));
            plan.templates.add(planned);
            importedByOldId.put(oldId, planned.template);
        }

        WorkflowDef workflow = WorkflowDef.createNew(firstNonEmpty(str(workflowRow.get("name")), "导入的工作流"));
        workflow.description = str(workflowRow.get("description"));
        workflow.workflowPlaybook = str(workflowRow.get("workflow_playbook"));
        workflow.failurePolicy = WorkflowDef.normalizeFailurePolicy(str(workflowRow.get("failure_policy")));
        workflow.triggerType = WorkflowDef.TRIGGER_NONE;
        workflow.triggerEnabled = false;
        workflow.triggerConfig.clear();
        int order = 0;
        for (Object stepObj : (List<Object>) stepsObj) {
            Map<String, Object> row = (Map<String, Object>) stepObj;
            String oldTemplateId = str(row.get("template_id"));
            TaskTemplate template = importedByOldId.get(oldTemplateId);
            WorkflowDef.Step step = new WorkflowDef.Step();
            step.templateId = template.templateId;
            step.name = firstNonEmpty(str(row.get("name")), template.name);
            step.order = order++;
            workflow.steps.add(step);
        }
        plan.workflow = workflow;
        return commitPlan(plan, workflowStore, taskMapStore, "workflow");
    }

    private static ImportResult importTemplateOnly(
            Map<String, Object> templateRow,
            WorkflowStore workflowStore,
            TaskMapStore taskMapStore,
            String importedType
    ) {
        ImportPlan plan = new ImportPlan();
        plan.templates.add(buildPlannedTemplate(templateRow));
        return commitPlan(plan, workflowStore, taskMapStore, importedType);
    }

    @SuppressWarnings("unchecked")
    private static ImportResult importLegacyTaskBundle(
            Map<String, Object> bundle,
            String taskType,
            WorkflowStore workflowStore,
            TaskMapStore taskMapStore
    ) {
        Map<String, Object> taskInfo = bundle.get("task_info") instanceof Map
                ? (Map<String, Object>) bundle.get("task_info")
                : new LinkedHashMap<String, Object>();
        Map<String, Object> taskConfig = bundle.get("task_config") instanceof Map
                ? (Map<String, Object>) bundle.get("task_config")
                : new LinkedHashMap<String, Object>();
        Map<String, Object> templateRow = new LinkedHashMap<String, Object>();
        templateRow.put("name", firstNonEmpty(str(taskConfig.get("name")), str(taskInfo.get("name")), str(taskConfig.get("user_task")), str(taskInfo.get("user_task")), "导入的模板"));
        templateRow.put("description", firstNonEmpty(str(taskConfig.get("user_task")), str(taskInfo.get("user_task")), str(templateRow.get("name"))));
        templateRow.put("package_name", firstNonEmpty(str(taskConfig.get("package_name")), str(taskInfo.get("package_name"))));
        templateRow.put("user_playbook", firstNonEmpty(str(taskConfig.get("user_playbook")), str(taskInfo.get("user_playbook"))));
        templateRow.put("task_map_mode", firstNonEmpty(str(taskConfig.get("task_map_mode")), str(taskInfo.get("task_map_mode")), "manual"));
        templateRow.put("record_enabled", false);
        templateRow.put("decompose_enabled", false);
        templateRow.put("route", new LinkedHashMap<String, Object>(bundle));

        ImportPlan plan = new ImportPlan();
        PlannedTemplate planned = buildPlannedTemplate(templateRow);
        plan.templates.add(planned);
        WorkflowDef workflow = WorkflowDef.createNew(firstNonEmpty(str(templateRow.get("name")), "导入的工作流"));
        workflow.triggerType = WorkflowDef.TRIGGER_NONE;
        workflow.triggerEnabled = false;
        workflow.triggerConfig.clear();
        workflow.failurePolicy = WorkflowDef.FAILURE_STOP;
        WorkflowDef.Step step = new WorkflowDef.Step();
        step.templateId = planned.template.templateId;
        step.name = planned.template.name;
        workflow.steps.add(step);
        plan.workflow = workflow;
        return commitPlan(plan, workflowStore, taskMapStore, "workflow");
    }

    @SuppressWarnings("unchecked")
    private static ImportResult importPureRouteBundle(
            Map<String, Object> bundle,
            WorkflowStore workflowStore,
            TaskMapStore taskMapStore
    ) {
        Map<String, Object> taskInfo = bundle.get("task_info") instanceof Map
                ? (Map<String, Object>) bundle.get("task_info")
                : new LinkedHashMap<String, Object>();
        Map<String, Object> templateRow = new LinkedHashMap<String, Object>();
        templateRow.put("name", firstNonEmpty(str(taskInfo.get("name")), str(taskInfo.get("user_task")), "导入的模板"));
        templateRow.put("description", firstNonEmpty(str(taskInfo.get("user_task")), str(taskInfo.get("name")), "导入的模板"));
        templateRow.put("package_name", str(taskInfo.get("package_name")));
        templateRow.put("user_playbook", str(taskInfo.get("user_playbook")));
        templateRow.put("task_map_mode", firstNonEmpty(str(taskInfo.get("task_map_mode")), "manual"));
        templateRow.put("route", new LinkedHashMap<String, Object>(bundle));
        return importTemplateOnly(templateRow, workflowStore, taskMapStore, "template");
    }

    @SuppressWarnings("unchecked")
    private static PlannedTemplate buildPlannedTemplate(Map<String, Object> row) {
        TaskTemplate template = TaskTemplate.createNew(
                firstNonEmpty(str(row.get("name")), str(row.get("description")), "导入的模板"),
                firstNonEmpty(str(row.get("description")), str(row.get("name")), "导入的模板")
        );
        template.packageName = firstNonEmpty(str(row.get("package_name")), str(row.get("package")));
        template.startPage = str(row.get("start_page"));
        template.mapPath = "";
        template.userPlaybook = str(row.get("user_playbook"));
        template.recordEnabled = toBool(row.get("record_enabled"), false);
        template.taskMapMode = TaskTemplate.normalizeTaskMapMode(firstNonEmpty(str(row.get("task_map_mode")), "manual"));
        template.decomposeEnabled = toBool(row.get("decompose_enabled"), false);
        template.routeId = template.templateId;

        PlannedTemplate planned = new PlannedTemplate();
        planned.template = template;
        Object routeObj = row.get("route");
        if (routeObj instanceof Map && !((Map<String, Object>) routeObj).isEmpty()) {
            PortableTaskRouteCodec.ImportResult imported = PortableTaskRouteCodec.importPortable(
                    template.routeId,
                    template.packageName,
                    Json.stringify(routeObj)
            );
            if (imported.map == null || !imported.map.isUsable()) {
                throw new IllegalArgumentException("portable route is not usable");
            }
            imported.map.taskKeyHash = template.routeId;
            imported.map.source = "template";
            imported.map.sourceId = template.routeId;
            imported.map.mode = template.taskMapMode;
            if (!template.packageName.isEmpty()) {
                imported.map.packageName = template.packageName;
            }
            planned.routeMap = imported.map;
            planned.pendingAdaptationCount = imported.pendingAdaptationCount;
            planned.materializedCount = imported.executableImportCount;
        }
        return planned;
    }

    private static ImportResult commitPlan(ImportPlan plan, WorkflowStore workflowStore, TaskMapStore taskMapStore, String importedType) {
        List<String> savedRoutes = new ArrayList<String>();
        List<String> savedTemplates = new ArrayList<String>();
        String savedWorkflow = "";
        ImportResult result = new ImportResult();
        result.importedType = importedType;
        try {
            for (PlannedTemplate planned : plan.templates) {
                if (planned.routeMap != null) {
                    if (!taskMapStore.saveMap(planned.routeMap)) {
                        throw new IllegalStateException("route save failed: " + planned.template.routeId);
                    }
                    savedRoutes.add(planned.template.routeId);
                    result.routeCount += 1;
                    result.pendingAdaptationCount += planned.pendingAdaptationCount;
                    result.materializedCount += planned.materializedCount;
                }
            }
            for (PlannedTemplate planned : plan.templates) {
                TaskTemplate saved = workflowStore.saveTemplate(planned.template);
                savedTemplates.add(saved.templateId);
                result.templateIds.add(saved.templateId);
            }
            if (plan.workflow != null) {
                WorkflowDef saved = workflowStore.saveWorkflow(plan.workflow);
                savedWorkflow = saved.workflowId;
                result.workflowId = saved.workflowId;
                result.importedType = "workflow";
            }
            return result;
        } catch (RuntimeException e) {
            rollback(savedWorkflow, savedTemplates, savedRoutes, workflowStore, taskMapStore);
            throw e;
        } catch (Exception e) {
            rollback(savedWorkflow, savedTemplates, savedRoutes, workflowStore, taskMapStore);
            throw new RuntimeException(e);
        }
    }

    private static void rollback(
            String workflowId,
            List<String> templateIds,
            List<String> routeIds,
            WorkflowStore workflowStore,
            TaskMapStore taskMapStore
    ) {
        try {
            if (!str(workflowId).isEmpty()) {
                workflowStore.deleteWorkflow(workflowId);
            }
        } catch (Exception ignored) {}
        for (String templateId : templateIds) {
            try {
                workflowStore.deleteTemplate(templateId);
            } catch (Exception ignored) {}
        }
        for (String routeId : routeIds) {
            try {
                taskMapStore.deleteMap(routeId);
            } catch (Exception ignored) {}
        }
    }

    private static Map<String, Object> exportWorkflowRow(WorkflowDef workflow) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("workflow_id", workflow.workflowId);
        out.put("name", workflow.name);
        out.put("description", workflow.description);
        out.put("workflow_playbook", workflow.workflowPlaybook);
        out.put("failure_policy", workflow.failurePolicy);
        List<Object> steps = new ArrayList<Object>();
        for (WorkflowDef.Step step : workflow.steps) {
            if (step == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("step_id", step.stepId);
            row.put("template_id", step.templateId);
            row.put("name", step.name);
            row.put("order", step.order);
            steps.add(row);
        }
        out.put("steps", steps);
        return out;
    }

    private static Map<String, Object> exportTemplateRow(TaskTemplate template, TaskMapStore taskMapStore) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("template_id", template.templateId);
        out.put("name", template.name);
        out.put("description", template.description);
        out.put("package_name", template.packageName);
        out.put("start_page", template.startPage);
        out.put("user_playbook", template.userPlaybook);
        out.put("record_enabled", template.recordEnabled);
        out.put("task_map_mode", template.taskMapMode);
        out.put("decompose_enabled", template.decomposeEnabled);
        String routeId = str(template.templateId);
        if (taskMapStore != null && !routeId.isEmpty()) {
            TaskMap map = taskMapStore.loadMap(routeId);
            if (map != null) {
                TaskRouteRecord record = taskMapStore.loadLatestAttemptRecord(routeId);
                if (record == null || record.actions.isEmpty()) {
                    record = taskMapStore.loadLatestSuccessRecord(routeId);
                }
                PortableTaskRouteCodec.ExportResult route = PortableTaskRouteCodec.exportPortable(map, record);
                out.put("route", route.bundle);
            }
        }
        return out;
    }

    private static String normalizeLegacyTaskType(String raw) {
        String v = str(raw).toLowerCase();
        if ("schedule".equals(v) || "scheduled_task".equals(v)) {
            return "schedule";
        }
        if ("notify_trigger".equals(v) || "notification_trigger".equals(v) || "notification".equals(v)) {
            return "notify_trigger";
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = str(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static boolean toBool(Object value, boolean def) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return def;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return def;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static int toInt(Object value, int def) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static final class PlannedTemplate {
        TaskTemplate template;
        TaskMap routeMap;
        int pendingAdaptationCount;
        int materializedCount;
    }

    private static final class ImportPlan {
        final List<PlannedTemplate> templates = new ArrayList<PlannedTemplate>();
        WorkflowDef workflow;
    }
}
