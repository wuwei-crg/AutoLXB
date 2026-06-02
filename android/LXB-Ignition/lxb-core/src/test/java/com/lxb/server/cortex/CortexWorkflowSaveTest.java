package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMapStore;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CortexWorkflowSaveTest {

    @Test
    public void saveWorkflow_requiresName() throws Exception {
        withTempState("lxb-workflow-name", new Body() {
            @Override
            public void run(CortexTaskManager manager) {
                Map<String, Object> template = manager.saveTaskTemplate(template("Template", "Do it"));
                Map<String, Object> workflow = workflow("", String.valueOf(template.get("template_id")));
                try {
                    manager.saveWorkflow(workflow);
                    Assert.fail("Expected name validation failure");
                } catch (IllegalArgumentException expected) {
                    Assert.assertTrue(expected.getMessage().contains("name"));
                }
            }
        });
    }

    @Test
    public void saveWorkflow_partialTriggerTogglePreservesConfigAndSteps() throws Exception {
        withTempState("lxb-workflow-merge", new Body() {
            @Override
            public void run(CortexTaskManager manager) {
                Map<String, Object> template = manager.saveTaskTemplate(template("Template", "Do it"));
                Map<String, Object> workflow = workflow("Morning flow", String.valueOf(template.get("template_id")));
                workflow.put("trigger_type", "schedule");
                workflow.put("trigger_enabled", false);
                Map<String, Object> triggerConfig = new LinkedHashMap<String, Object>();
                triggerConfig.put("run_at", System.currentTimeMillis() + 60_000L);
                triggerConfig.put("repeat_mode", "once");
                workflow.put("trigger_config", triggerConfig);
                Map<String, Object> saved = manager.saveWorkflow(workflow);

                Map<String, Object> partial = new LinkedHashMap<String, Object>();
                partial.put("workflow_id", saved.get("workflow_id"));
                partial.put("trigger_enabled", true);
                Map<String, Object> toggled = manager.saveWorkflow(partial);

                Assert.assertEquals(Boolean.TRUE, toggled.get("trigger_enabled"));
                Assert.assertEquals("schedule", toggled.get("trigger_type"));
                Assert.assertTrue(((List<?>) toggled.get("steps")).size() == 1);
                Map<?, ?> toggledConfig = (Map<?, ?>) toggled.get("trigger_config");
                Assert.assertEquals("once", toggledConfig.get("repeat_mode"));
                Assert.assertTrue(((Number) toggledConfig.get("next_run_at")).longValue() > 0L);
            }
        });
    }

    private static Map<String, Object> template(String name, String description) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("description", description);
        return row;
    }

    private static Map<String, Object> workflow(String name, String templateId) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        List<Object> steps = new ArrayList<Object>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("template_id", templateId);
        steps.add(step);
        row.put("steps", steps);
        return row;
    }

    private interface Body {
        void run(CortexTaskManager manager) throws Exception;
    }

    private static void withTempState(String prefix, Body body) throws Exception {
        String oldMemoryPath = System.getProperty("lxb.task.memory.path");
        String oldSchedulesPath = System.getProperty("lxb.schedules.path");
        String oldRunsPath = System.getProperty("lxb.task.runs.path");
        String oldTemplatesPath = System.getProperty("lxb.task.templates.path");
        String oldWorkflowsPath = System.getProperty("lxb.workflows.path");
        File dir = Files.createTempDirectory(prefix).toFile();
        try {
            System.setProperty("lxb.task.memory.path", new File(dir, "task_memory.json").getAbsolutePath());
            System.setProperty("lxb.schedules.path", new File(dir, "schedules.json").getAbsolutePath());
            System.setProperty("lxb.task.runs.path", new File(dir, "task_runs.json").getAbsolutePath());
            System.setProperty("lxb.task.templates.path", new File(dir, "task_templates.json").getAbsolutePath());
            System.setProperty("lxb.workflows.path", new File(dir, "workflows.json").getAbsolutePath());
            body.run(new CortexTaskManager(null, new TaskMapStore()));
        } finally {
            restoreProperty("lxb.task.memory.path", oldMemoryPath);
            restoreProperty("lxb.schedules.path", oldSchedulesPath);
            restoreProperty("lxb.task.runs.path", oldRunsPath);
            restoreProperty("lxb.task.templates.path", oldTemplatesPath);
            restoreProperty("lxb.workflows.path", oldWorkflowsPath);
        }
    }

    private static void restoreProperty(String key, String oldValue) {
        if (oldValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, oldValue);
        }
    }
}
