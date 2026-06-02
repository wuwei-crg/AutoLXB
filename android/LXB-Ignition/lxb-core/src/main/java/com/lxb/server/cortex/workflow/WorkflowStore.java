package com.lxb.server.cortex.workflow;

import com.lxb.server.cortex.CortexTaskPersistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkflowStore {

    private static final String TEMPLATE_SCHEMA = "task_templates.v1";
    private static final String WORKFLOW_SCHEMA = "workflows.v1";

    private final CortexTaskPersistence persistence;
    private final String templatesPath;
    private final String workflowsPath;
    private final Object lock = new Object();
    private final LinkedHashMap<String, TaskTemplate> templates = new LinkedHashMap<String, TaskTemplate>();
    private final LinkedHashMap<String, WorkflowDef> workflows = new LinkedHashMap<String, WorkflowDef>();

    public WorkflowStore(CortexTaskPersistence persistence, String templatesPath, String workflowsPath) {
        this.persistence = persistence != null ? persistence : new CortexTaskPersistence();
        this.templatesPath = templatesPath;
        this.workflowsPath = workflowsPath;
        load();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        synchronized (lock) {
            templates.clear();
            List<Object> templateRows = persistence.loadRows(templatesPath, "templates");
            if (templateRows != null) {
                for (Object o : templateRows) {
                    if (!(o instanceof Map)) continue;
                    TaskTemplate t = TaskTemplate.fromMap((Map<String, Object>) o);
                    if (t != null && !t.templateId.isEmpty()) {
                        templates.put(t.templateId, t);
                    }
                }
            }

            workflows.clear();
            List<Object> workflowRows = persistence.loadRows(workflowsPath, "workflows");
            if (workflowRows != null) {
                for (Object o : workflowRows) {
                    if (!(o instanceof Map)) continue;
                    WorkflowDef w = WorkflowDef.fromMap((Map<String, Object>) o);
                    if (w != null && !w.workflowId.isEmpty()) {
                        workflows.put(w.workflowId, w);
                    }
                }
            }
        }
    }

    public List<TaskTemplate> listTemplates() {
        synchronized (lock) {
            List<TaskTemplate> out = new ArrayList<TaskTemplate>(templates.values());
            out.sort(new Comparator<TaskTemplate>() {
                @Override
                public int compare(TaskTemplate a, TaskTemplate b) {
                    return Long.compare(b.updatedAtMs, a.updatedAtMs);
                }
            });
            return out;
        }
    }

    public List<WorkflowDef> listWorkflows() {
        synchronized (lock) {
            List<WorkflowDef> out = new ArrayList<WorkflowDef>(workflows.values());
            out.sort(new Comparator<WorkflowDef>() {
                @Override
                public int compare(WorkflowDef a, WorkflowDef b) {
                    return Long.compare(b.updatedAtMs, a.updatedAtMs);
                }
            });
            return out;
        }
    }

    public TaskTemplate getTemplate(String id) {
        synchronized (lock) {
            return templates.get(TaskTemplate.stringOrEmpty(id));
        }
    }

    public WorkflowDef getWorkflow(String id) {
        synchronized (lock) {
            return workflows.get(TaskTemplate.stringOrEmpty(id));
        }
    }

    public TaskTemplate saveTemplate(TaskTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("template is required");
        }
        if (TaskTemplate.stringOrEmpty(template.description).isEmpty()) {
            throw new IllegalArgumentException("description is required");
        }
        synchronized (lock) {
            TaskTemplate existing = templates.get(TaskTemplate.stringOrEmpty(template.templateId));
            long created = existing != null ? existing.createdAtMs : template.createdAtMs;
            template.createdAtMs = created;
            template.normalizeForSave();
            templates.put(template.templateId, template);
            saveTemplatesLocked();
            return template;
        }
    }

    public WorkflowDef saveWorkflow(WorkflowDef workflow) {
        if (workflow == null) {
            throw new IllegalArgumentException("workflow is required");
        }
        if (TaskTemplate.stringOrEmpty(workflow.name).isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        if (workflow.steps.isEmpty()) {
            throw new IllegalArgumentException("workflow must have at least one step");
        }
        synchronized (lock) {
            for (WorkflowDef.Step step : workflow.steps) {
                if (step == null || TaskTemplate.stringOrEmpty(step.templateId).isEmpty()) {
                    throw new IllegalArgumentException("workflow step template_id is required");
                }
                if (!templates.containsKey(step.templateId)) {
                    throw new IllegalArgumentException("template not found: " + step.templateId);
                }
            }
            WorkflowDef existing = workflows.get(TaskTemplate.stringOrEmpty(workflow.workflowId));
            long created = existing != null ? existing.createdAtMs : workflow.createdAtMs;
            workflow.createdAtMs = created;
            workflow.normalizeForSave();
            workflows.put(workflow.workflowId, workflow);
            saveWorkflowsLocked();
            return workflow;
        }
    }

    public boolean deleteTemplate(String id) {
        String tid = TaskTemplate.stringOrEmpty(id);
        if (tid.isEmpty()) {
            throw new IllegalArgumentException("template_id is required");
        }
        synchronized (lock) {
            for (WorkflowDef workflow : workflows.values()) {
                for (WorkflowDef.Step step : workflow.steps) {
                    if (tid.equals(step.templateId)) {
                        throw new IllegalStateException("template is referenced by workflow: " + workflow.workflowId);
                    }
                }
            }
            boolean removed = templates.remove(tid) != null;
            if (removed) {
                saveTemplatesLocked();
            }
            return removed;
        }
    }

    public boolean deleteWorkflow(String id) {
        String wid = TaskTemplate.stringOrEmpty(id);
        if (wid.isEmpty()) {
            throw new IllegalArgumentException("workflow_id is required");
        }
        synchronized (lock) {
            boolean removed = workflows.remove(wid) != null;
            if (removed) {
                saveWorkflowsLocked();
            }
            return removed;
        }
    }

    public boolean hasLegacyTemplate(String kind, String id) {
        String k = TaskTemplate.stringOrEmpty(kind);
        String legacy = TaskTemplate.stringOrEmpty(id);
        if (k.isEmpty() || legacy.isEmpty()) return false;
        synchronized (lock) {
            for (TaskTemplate t : templates.values()) {
                if (k.equals(t.legacyKind) && legacy.equals(t.legacyId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean hasLegacyWorkflow(String kind, String id) {
        String k = TaskTemplate.stringOrEmpty(kind);
        String legacy = TaskTemplate.stringOrEmpty(id);
        if (k.isEmpty() || legacy.isEmpty()) return false;
        synchronized (lock) {
            for (WorkflowDef w : workflows.values()) {
                if (k.equals(w.legacyKind) && legacy.equals(w.legacyId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void saveAll() {
        synchronized (lock) {
            saveTemplatesLocked();
            saveWorkflowsLocked();
        }
    }

    private void saveTemplatesLocked() {
        List<Object> rows = new ArrayList<Object>();
        for (TaskTemplate t : templates.values()) {
            rows.add(t.toMap());
        }
        persistence.saveRows(templatesPath, TEMPLATE_SCHEMA, "templates", rows);
    }

    private void saveWorkflowsLocked() {
        List<Object> rows = new ArrayList<Object>();
        for (WorkflowDef w : workflows.values()) {
            rows.add(w.toMap());
        }
        persistence.saveRows(workflowsPath, WORKFLOW_SCHEMA, "workflows", rows);
    }
}
