package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;
import com.lxb.server.cortex.taskmap.TaskMapStore;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import java.nio.file.Files;

public class CortexExecutionHistoryTest {

    @Test
    public void render_emptyHistoryMatchesCurrentPromptContract() {
        CortexExecutionHistory history = new CortexExecutionHistory();

        Assert.assertEquals("Recent turns: none\n", history.renderPromptBlock());
    }

    @Test
    public void closePending_appendsSameFormatRow() {
        CortexExecutionHistory history = new CortexExecutionHistory();
        history.beginPending("tap search", "search opens", "from home");

        Map<String, Object> row = history.closePendingWithObservation(
                "search opened",
                "ok",
                "progress"
        );

        Assert.assertNotNull(row);
        Assert.assertEquals("tap search", row.get(CortexExecutionHistory.KEY_INSTRUCTION));
        Assert.assertEquals("search opens", row.get(CortexExecutionHistory.KEY_EXPECTED));
        Assert.assertEquals("search opened", row.get(CortexExecutionHistory.KEY_ACTUAL));
        Assert.assertEquals("ok", row.get(CortexExecutionHistory.KEY_JUDGEMENT_PREV));
        Assert.assertEquals("progress", row.get(CortexExecutionHistory.KEY_JUDGEMENT_GLOBAL));
        Assert.assertEquals("from home", row.get(CortexExecutionHistory.KEY_CARRY_CONTEXT));
    }

    @Test
    public void historyCap_keepsLastTenRows() {
        CortexExecutionHistory history = new CortexExecutionHistory();
        for (int i = 0; i < 12; i++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put(CortexExecutionHistory.KEY_INSTRUCTION, "step " + i);
            row.put(CortexExecutionHistory.KEY_EXPECTED, "expected " + i);
            row.put(CortexExecutionHistory.KEY_ACTUAL, "actual " + i);
            history.addRow(row);
        }

        List<Map<String, Object>> rows = history.rows();
        Assert.assertEquals(10, rows.size());
        Assert.assertEquals("step 2", rows.get(0).get(CortexExecutionHistory.KEY_INSTRUCTION));
        Assert.assertEquals("step 11", rows.get(9).get(CortexExecutionHistory.KEY_INSTRUCTION));
    }

    @Test
    public void snapshotRestore_rollsBackRetryPollution() {
        CortexExecutionHistory history = new CortexExecutionHistory();
        Map<String, Object> stable = new LinkedHashMap<String, Object>();
        stable.put(CortexExecutionHistory.KEY_INSTRUCTION, "stable");
        stable.put(CortexExecutionHistory.KEY_EXPECTED, "stable expected");
        stable.put(CortexExecutionHistory.KEY_ACTUAL, "stable actual");
        history.addRow(stable);
        history.beginPending("pending", "pending expected", "carry");

        CortexExecutionHistory.Snapshot snapshot = history.snapshot();

        Map<String, Object> pollution = new LinkedHashMap<String, Object>();
        pollution.put(CortexExecutionHistory.KEY_INSTRUCTION, "pollution");
        pollution.put(CortexExecutionHistory.KEY_EXPECTED, "pollution expected");
        pollution.put(CortexExecutionHistory.KEY_ACTUAL, "pollution actual");
        history.addRow(pollution);
        history.beginPending("other", "other expected", "other carry");

        history.restore(snapshot);

        Assert.assertEquals(1, history.rows().size());
        Assert.assertTrue(history.renderPromptBlock().contains("action: pending"));
        Assert.assertFalse(history.renderPromptBlock().contains("pollution"));
    }

    @Test
    public void synthesizeLegacyStep_usesSafeDefaults() {
        TaskMap.Step step = new TaskMap.Step();
        step.stepId = "s0001";
        step.op = "TAP";
        step.expected = "target opens";

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("result", "resolve_fail");
        summary.put("reason", "missing locator");

        Map<String, Object> row = CortexExecutionHistory.rowFromTaskMapStep(step, summary);

        Assert.assertEquals("Replay route step s0001", row.get(CortexExecutionHistory.KEY_INSTRUCTION));
        Assert.assertEquals("target opens", row.get(CortexExecutionHistory.KEY_EXPECTED));
        Assert.assertTrue(String.valueOf(row.get(CortexExecutionHistory.KEY_ACTUAL)).contains("resolve_fail"));
        Assert.assertEquals("script_replay_failed", row.get(CortexExecutionHistory.KEY_JUDGEMENT_PREV));
        Assert.assertEquals("route_replay_fallback", row.get(CortexExecutionHistory.KEY_JUDGEMENT_GLOBAL));
        Assert.assertEquals("none", row.get(CortexExecutionHistory.KEY_CARRY_CONTEXT));
    }

    @Test
    public void visionPrompt_rendersExecutionHistoryModule() throws Exception {
        CortexFsmEngine engine = new CortexFsmEngine(
                null,
                null,
                null,
                new TraceLogger(16),
                new TaskMapStore(Files.createTempDirectory("execution-history-prompt").toFile()),
                request -> StepVisualResolveResult.status(StepVisualResolveResult.STATUS_NO_MATCH, "", "fake")
        );
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task1");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put(CortexExecutionHistory.KEY_INSTRUCTION, "SCRIPT_ACT tapped publish");
        row.put(CortexExecutionHistory.KEY_EXPECTED, "publish page opens");
        row.put(CortexExecutionHistory.KEY_ACTUAL, "SCRIPT_ACT replay op=TAP result=ok");
        ctx.executionHistory.addRow(row);

        Method method = CortexFsmEngine.class.getDeclaredMethod("buildVisionPrompt", CortexFsmEngine.Context.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(engine, ctx);

        Assert.assertTrue(prompt.contains("[RECENT_HISTORY_BLOCK]"));
        Assert.assertTrue(prompt.contains("SCRIPT_ACT tapped publish"));
        Assert.assertTrue(prompt.contains("SCRIPT_ACT replay op=TAP result=ok"));
    }
}
