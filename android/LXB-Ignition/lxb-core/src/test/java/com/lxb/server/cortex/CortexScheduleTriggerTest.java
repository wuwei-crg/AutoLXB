package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMapStore;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class CortexScheduleTriggerTest {

    @Test
    public void triggerScheduledTask_enqueuesScheduleSourceWithoutChangingNextRun() throws Exception {
        String oldMemoryPath = System.getProperty("lxb.task.memory.path");
        String oldSchedulesPath = System.getProperty("lxb.schedules.path");
        String oldRunsPath = System.getProperty("lxb.task.runs.path");
        File dir = Files.createTempDirectory("lxb-schedule-trigger").toFile();
        try {
            System.setProperty("lxb.task.memory.path", new File(dir, "task_memory.json").getAbsolutePath());
            System.setProperty("lxb.schedules.path", new File(dir, "schedules.json").getAbsolutePath());
            System.setProperty("lxb.task.runs.path", new File(dir, "task_runs.json").getAbsolutePath());

            CortexTaskManager manager = new CortexTaskManager(null, new TaskMapStore());
            long runAt = System.currentTimeMillis() + 60L * 60L * 1000L;
            Map<String, Object> schedule = manager.addScheduledTask(
                    "Daily demo",
                    "open demo",
                    "com.demo",
                    null,
                    null,
                    null,
                    null,
                    runAt,
                    "daily",
                    0,
                    "demo playbook",
                    true,
                    "manual"
            );
            String scheduleId = String.valueOf(schedule.get("schedule_id"));
            long nextRunBefore = ((Number) schedule.get("next_run_at")).longValue();

            Map<String, Object> triggered = manager.triggerScheduledTask(scheduleId);

            Assert.assertNotNull(triggered);
            Assert.assertEquals(scheduleId, triggered.get("schedule_id"));
            String taskId = String.valueOf(triggered.get("task_id"));
            Assert.assertFalse(taskId.isEmpty());

            Map<String, Object> taskStatus = manager.getTaskStatus(taskId);
            Assert.assertEquals(Boolean.TRUE, taskStatus.get("found"));
            Assert.assertEquals("schedule", taskStatus.get("source"));
            Assert.assertEquals(scheduleId, taskStatus.get("schedule_id"));
            Assert.assertEquals(scheduleId, taskStatus.get("source_id"));
            Assert.assertEquals("manual", taskStatus.get("task_map_mode"));
            Assert.assertEquals(Boolean.TRUE, taskStatus.get("record_enabled"));

            List<Map<String, Object>> schedules = manager.listScheduledTasks(10);
            Map<String, Object> updated = schedules.get(0);
            Assert.assertEquals(scheduleId, updated.get("schedule_id"));
            Assert.assertEquals(nextRunBefore, ((Number) updated.get("next_run_at")).longValue());
            Assert.assertEquals(1L, ((Number) updated.get("trigger_count")).longValue());
            Assert.assertTrue(((Number) updated.get("last_triggered_at")).longValue() > 0L);
            Assert.assertEquals(Boolean.TRUE, updated.get("enabled"));
        } finally {
            restoreProperty("lxb.task.memory.path", oldMemoryPath);
            restoreProperty("lxb.schedules.path", oldSchedulesPath);
            restoreProperty("lxb.task.runs.path", oldRunsPath);
        }
    }

    @Test
    public void triggerScheduledTask_allowsDisabledScheduleWithoutEnablingIt() throws Exception {
        String oldMemoryPath = System.getProperty("lxb.task.memory.path");
        String oldSchedulesPath = System.getProperty("lxb.schedules.path");
        String oldRunsPath = System.getProperty("lxb.task.runs.path");
        File dir = Files.createTempDirectory("lxb-schedule-trigger-disabled").toFile();
        try {
            System.setProperty("lxb.task.memory.path", new File(dir, "task_memory.json").getAbsolutePath());
            System.setProperty("lxb.schedules.path", new File(dir, "schedules.json").getAbsolutePath());
            System.setProperty("lxb.task.runs.path", new File(dir, "task_runs.json").getAbsolutePath());

            CortexTaskManager manager = new CortexTaskManager(null, new TaskMapStore());
            long runAt = System.currentTimeMillis() + 60L * 60L * 1000L;
            Map<String, Object> schedule = manager.addScheduledTask(
                    "Disabled demo",
                    "open disabled demo",
                    "com.demo.disabled",
                    null,
                    null,
                    null,
                    null,
                    runAt,
                    "daily",
                    0,
                    "",
                    false,
                    "off"
            );
            String scheduleId = String.valueOf(schedule.get("schedule_id"));
            Map<String, Object> disabled = manager.updateScheduledTask(
                    scheduleId,
                    "Disabled demo",
                    "open disabled demo",
                    "com.demo.disabled",
                    null,
                    null,
                    null,
                    null,
                    runAt,
                    "daily",
                    0,
                    "",
                    Boolean.FALSE,
                    false,
                    "off"
            );
            long nextRunBefore = ((Number) disabled.get("next_run_at")).longValue();

            Map<String, Object> triggered = manager.triggerScheduledTask(scheduleId);

            Assert.assertNotNull(triggered);
            Assert.assertEquals(scheduleId, triggered.get("schedule_id"));
            String taskId = String.valueOf(triggered.get("task_id"));
            Assert.assertFalse(taskId.isEmpty());

            Map<String, Object> taskStatus = manager.getTaskStatus(taskId);
            Assert.assertEquals(Boolean.TRUE, taskStatus.get("found"));
            Assert.assertEquals("schedule", taskStatus.get("source"));
            Assert.assertEquals(scheduleId, taskStatus.get("schedule_id"));

            Map<String, Object> updated = manager.listScheduledTasks(10).get(0);
            Assert.assertEquals(Boolean.FALSE, updated.get("enabled"));
            Assert.assertEquals(nextRunBefore, ((Number) updated.get("next_run_at")).longValue());
            Assert.assertEquals(1L, ((Number) updated.get("trigger_count")).longValue());
            Assert.assertTrue(((Number) updated.get("last_triggered_at")).longValue() > 0L);
        } finally {
            restoreProperty("lxb.task.memory.path", oldMemoryPath);
            restoreProperty("lxb.schedules.path", oldSchedulesPath);
            restoreProperty("lxb.task.runs.path", oldRunsPath);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
