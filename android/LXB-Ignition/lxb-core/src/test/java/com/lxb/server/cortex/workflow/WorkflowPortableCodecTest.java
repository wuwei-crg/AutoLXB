package com.lxb.server.cortex.workflow;

import com.lxb.server.cortex.CortexTaskPersistence;
import com.lxb.server.cortex.taskmap.PortableTaskRouteCodec;
import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapStore;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class WorkflowPortableCodecTest {

    @Test
    public void exportWorkflow_omitsTriggerAndEmbedsReferencedTemplate() throws Exception {
        Fixture fx = fixture();
        TaskTemplate template = fx.store.saveTemplate(TaskTemplate.createNew("Post", "Post update"));
        template.packageName = "com.demo";
        fx.store.saveTemplate(template);

        WorkflowDef workflow = WorkflowDef.createNew("Morning flow");
        workflow.workflowPlaybook = "Tap skip during every battle.";
        workflow.triggerType = WorkflowDef.TRIGGER_SCHEDULE;
        workflow.triggerEnabled = true;
        workflow.triggerConfig.put("run_at", 123L);
        WorkflowDef.Step step = new WorkflowDef.Step();
        step.templateId = template.templateId;
        workflow.steps.add(step);
        fx.store.saveWorkflow(workflow);

        Map<String, Object> exported = WorkflowPortableCodec.exportWorkflow(workflow, fx.store, fx.mapStore);
        @SuppressWarnings("unchecked")
        Map<String, Object> workflowRow = (Map<String, Object>) exported.get("workflow");

        assertEquals(WorkflowPortableCodec.SCHEMA_WORKFLOW_BUNDLE, exported.get("schema"));
        assertEquals(WorkflowPortableCodec.VERSION, exported.get("version"));
        assertFalse(workflowRow.containsKey("trigger_type"));
        assertFalse(workflowRow.containsKey("trigger_enabled"));
        assertFalse(workflowRow.containsKey("trigger_config"));
        assertEquals("Tap skip during every battle.", workflowRow.get("workflow_playbook"));
        assertEquals(1, ((java.util.List<?>) exported.get("templates")).size());
    }

    @Test
    public void importWorkflow_generatesNewIdsAndDisablesTrigger() throws Exception {
        Fixture source = fixture();
        TaskTemplate template = source.store.saveTemplate(TaskTemplate.createNew("Post", "Post update"));
        WorkflowDef workflow = WorkflowDef.createNew("Flow");
        workflow.workflowPlaybook = "Use skip when battle animation starts.";
        workflow.triggerType = WorkflowDef.TRIGGER_NOTIFICATION;
        workflow.triggerEnabled = true;
        WorkflowDef.Step step = new WorkflowDef.Step();
        step.templateId = template.templateId;
        workflow.steps.add(step);
        source.store.saveWorkflow(workflow);
        Map<String, Object> bundle = WorkflowPortableCodec.exportWorkflow(workflow, source.store, source.mapStore);

        Fixture target = fixture();
        WorkflowPortableCodec.ImportResult imported = WorkflowPortableCodec.importPortable(bundle, target.store, target.mapStore);
        WorkflowDef importedWorkflow = target.store.getWorkflow(imported.workflowId);

        assertNotEquals(workflow.workflowId, imported.workflowId);
        assertNotEquals(template.templateId, imported.templateIds.get(0));
        assertEquals(WorkflowDef.TRIGGER_NONE, importedWorkflow.triggerType);
        assertFalse(importedWorkflow.triggerEnabled);
        assertEquals("Use skip when battle animation starts.", importedWorkflow.workflowPlaybook);
        assertEquals(imported.templateIds.get(0), importedWorkflow.steps.get(0).templateId);
    }

    @Test
    public void importWorkflow_rejectsMissingEmbeddedTemplate() throws Exception {
        Map<String, Object> workflow = new LinkedHashMap<String, Object>();
        workflow.put("workflow_id", "wf-old");
        workflow.put("name", "Bad");
        java.util.List<Object> steps = new java.util.ArrayList<Object>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("template_id", "tpl-missing");
        steps.add(step);
        workflow.put("steps", steps);
        Map<String, Object> bundle = new LinkedHashMap<String, Object>();
        bundle.put("schema", WorkflowPortableCodec.SCHEMA_WORKFLOW_BUNDLE);
        bundle.put("version", WorkflowPortableCodec.VERSION);
        bundle.put("workflow", workflow);
        bundle.put("templates", new java.util.ArrayList<Object>());

        Fixture target = fixture();
        try {
            WorkflowPortableCodec.importPortable(bundle, target.store, target.mapStore);
            org.junit.Assert.fail("expected import rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("exactly match"));
        }
        assertTrue(target.store.listTemplates().isEmpty());
        assertTrue(target.store.listWorkflows().isEmpty());
    }

    @Test
    public void importPureRoute_createsTemplateOnlyWithRoute() throws Exception {
        Map<String, Object> route = PortableTaskRouteCodec.exportPortable(routeMap("legacy-route"), null).bundle;
        Fixture target = fixture();

        WorkflowPortableCodec.ImportResult imported = WorkflowPortableCodec.importPortable(route, target.store, target.mapStore);
        TaskTemplate template = target.store.getTemplate(imported.templateIds.get(0));

        assertEquals("template", imported.importedType);
        assertEquals("", imported.workflowId);
        assertEquals("template:" + template.templateId, template.routeId);
        assertTrue(target.mapStore.hasMap(template.routeId));
    }

    private static Fixture fixture() throws Exception {
        Path dir = Files.createTempDirectory("lxb-portable-workflow");
        Fixture fx = new Fixture();
        fx.store = new WorkflowStore(
                new CortexTaskPersistence(),
                dir.resolve("task_templates.v1.json").toString(),
                dir.resolve("workflows.v1.json").toString()
        );
        fx.mapStore = new TaskMapStore(dir.resolve("task_maps").toFile());
        return fx;
    }

    private static TaskMap routeMap(String routeId) {
        TaskMap map = new TaskMap();
        map.taskKeyHash = routeId;
        map.packageName = "com.demo";
        TaskMap.Segment segment = new TaskMap.Segment();
        segment.segmentId = "seg1";
        segment.subTaskId = "default";
        segment.subTaskIndex = 0;
        segment.subTaskDescription = "Post update";
        segment.packageName = "com.demo";
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "step1";
        step.sourceActionId = "action1";
        step.op = "TAP";
        step.locator.put("resource_id", "publish");
        segment.steps.add(step);
        map.segments.add(segment);
        return map;
    }

    private static final class Fixture {
        WorkflowStore store;
        TaskMapStore mapStore;
    }
}
