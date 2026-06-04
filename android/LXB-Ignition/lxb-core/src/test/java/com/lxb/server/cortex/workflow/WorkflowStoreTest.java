package com.lxb.server.cortex.workflow;

import com.lxb.server.cortex.CortexTaskPersistence;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WorkflowStoreTest {

    @Test
    public void saveTemplate_defaultsRouteAndDecomposeOff() throws Exception {
        Path dir = Files.createTempDirectory("lxb-workflow-store");
        WorkflowStore store = new WorkflowStore(
                new CortexTaskPersistence(),
                dir.resolve("task_templates.v1.json").toString(),
                dir.resolve("workflows.v1.json").toString()
        );

        TaskTemplate template = TaskTemplate.createNew("Open chat", "Open chat app");
        TaskTemplate saved = store.saveTemplate(template);

        assertFalse(saved.templateId.isEmpty());
        assertEquals("template:" + saved.templateId, saved.routeId);
        assertFalse(saved.decomposeEnabled);
    }

    @Test
    public void deleteTemplate_rejectsReferencedTemplate() throws Exception {
        Path dir = Files.createTempDirectory("lxb-workflow-store");
        WorkflowStore store = new WorkflowStore(
                new CortexTaskPersistence(),
                dir.resolve("task_templates.v1.json").toString(),
                dir.resolve("workflows.v1.json").toString()
        );
        TaskTemplate template = store.saveTemplate(TaskTemplate.createNew("A", "Do A"));

        WorkflowDef workflow = WorkflowDef.createNew("Flow");
        WorkflowDef.Step step = new WorkflowDef.Step();
        step.templateId = template.templateId;
        workflow.steps.add(step);
        store.saveWorkflow(workflow);

        boolean rejected = false;
        try {
            store.deleteTemplate(template.templateId);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    public void saveWorkflow_preservesWorkflowPlaybook() throws Exception {
        Path dir = Files.createTempDirectory("lxb-workflow-store");
        WorkflowStore store = new WorkflowStore(
                new CortexTaskPersistence(),
                dir.resolve("task_templates.v1.json").toString(),
                dir.resolve("workflows.v1.json").toString()
        );
        TaskTemplate template = store.saveTemplate(TaskTemplate.createNew("A", "Do A"));

        WorkflowDef workflow = WorkflowDef.createNew("Flow");
        workflow.workflowPlaybook = "During battles, tap skip in the lower-right corner.";
        WorkflowDef.Step step = new WorkflowDef.Step();
        step.templateId = template.templateId;
        workflow.steps.add(step);
        WorkflowDef saved = store.saveWorkflow(workflow);

        WorkflowStore reloaded = new WorkflowStore(
                new CortexTaskPersistence(),
                dir.resolve("task_templates.v1.json").toString(),
                dir.resolve("workflows.v1.json").toString()
        );
        WorkflowDef loaded = reloaded.getWorkflow(saved.workflowId);

        assertEquals("During battles, tap skip in the lower-right corner.", loaded.workflowPlaybook);
    }
}
