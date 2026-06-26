package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains the external semantic history shared by SCRIPT_ACT and VISION_ACT.
 *
 * The row contract intentionally matches the existing VISION_ACT prompt history:
 * instruction, expected, actual, judgement_prev, judgement_global, carry_context.
 */
public final class CortexExecutionHistory {

    public static final String KEY_INSTRUCTION = "instruction";
    public static final String KEY_EXPECTED = "expected";
    public static final String KEY_ACTUAL = "actual";
    public static final String KEY_JUDGEMENT_PREV = "judgement_prev";
    public static final String KEY_JUDGEMENT_GLOBAL = "judgement_global";
    public static final String KEY_LEGACY_JUDGEMENT = "judgement";
    public static final String KEY_CARRY_CONTEXT = "carry_context";

    private static final int MAX_ROWS = 10;

    private final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    private String pendingInstruction = "";
    private String pendingExpected = "";
    private String pendingCarryContext = "";

    public static final class Snapshot {
        private final List<Map<String, Object>> rows;
        private final String pendingInstruction;
        private final String pendingExpected;
        private final String pendingCarryContext;

        private Snapshot(
                List<Map<String, Object>> rows,
                String pendingInstruction,
                String pendingExpected,
                String pendingCarryContext
        ) {
            this.rows = rows;
            this.pendingInstruction = pendingInstruction;
            this.pendingExpected = pendingExpected;
            this.pendingCarryContext = pendingCarryContext;
        }
    }

    public void clear() {
        rows.clear();
        pendingInstruction = "";
        pendingExpected = "";
        pendingCarryContext = "";
    }

    public void addRow(Map<String, Object> row) {
        Map<String, Object> normalized = normalizeRow(row);
        if (isEmptyRow(normalized)) {
            return;
        }
        rows.add(normalized);
        while (rows.size() > MAX_ROWS) {
            rows.remove(0);
        }
    }

    public List<Map<String, Object>> rows() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            out.add(new LinkedHashMap<String, Object>(row));
        }
        return out;
    }

    public void beginPending(String instruction, String expected, String carryContext) {
        pendingInstruction = stringOrEmpty(instruction);
        pendingExpected = stringOrEmpty(expected);
        pendingCarryContext = stringOrEmpty(carryContext);
    }

    public Map<String, Object> closePendingWithObservation(String actual, String judgementPrev, String judgementGlobal) {
        if (!hasPending()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put(KEY_INSTRUCTION, pendingInstruction);
        row.put(KEY_EXPECTED, pendingExpected);
        row.put(KEY_ACTUAL, stringOrEmpty(actual));
        String jp = firstNonEmpty(judgementPrev, "unknown");
        String jg = firstNonEmpty(judgementGlobal, jp);
        row.put(KEY_JUDGEMENT_PREV, jp);
        row.put(KEY_JUDGEMENT_GLOBAL, jg);
        row.put(KEY_LEGACY_JUDGEMENT, jp);
        row.put(KEY_CARRY_CONTEXT, pendingCarryContext);
        addRow(row);
        beginPending("", "", "");
        return normalizeRow(row);
    }

    public boolean hasPending() {
        return !pendingInstruction.isEmpty()
                || !pendingExpected.isEmpty()
                || !pendingCarryContext.isEmpty();
    }

    public int size() {
        return rows.size();
    }

    public Snapshot snapshot() {
        return new Snapshot(rows(), pendingInstruction, pendingExpected, pendingCarryContext);
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        rows.clear();
        for (Map<String, Object> row : snapshot.rows) {
            rows.add(new LinkedHashMap<String, Object>(row));
        }
        pendingInstruction = snapshot.pendingInstruction != null ? snapshot.pendingInstruction : "";
        pendingExpected = snapshot.pendingExpected != null ? snapshot.pendingExpected : "";
        pendingCarryContext = snapshot.pendingCarryContext != null ? snapshot.pendingCarryContext : "";
    }

    public void appendPromptBlock(StringBuilder sb) {
        if (sb == null) {
            return;
        }
        boolean hasPending = hasPending();
        if (rows.isEmpty() && !hasPending) {
            sb.append("Recent turns: none\n");
            return;
        }

        sb.append("Recent turns (oldest -> newest):\n");
        int rowIndex = 1;
        for (Map<String, Object> row : rows) {
            sb.append(rowIndex++).append(") action: ").append(stringOrEmpty(row.get(KEY_INSTRUCTION))).append("\n");
            sb.append("   expected: ").append(stringOrEmpty(row.get(KEY_EXPECTED))).append("\n");
            sb.append("   actual: ").append(stringOrEmpty(row.get(KEY_ACTUAL))).append("\n");
            sb.append("   judge_prev: ").append(stringOrEmpty(row.get(KEY_JUDGEMENT_PREV))).append("\n");
            sb.append("   judge_global: ").append(stringOrEmpty(row.get(KEY_JUDGEMENT_GLOBAL))).append("\n");
            sb.append("   carry_context: ").append(stringOrEmpty(row.get(KEY_CARRY_CONTEXT))).append("\n");
        }
        if (hasPending) {
            sb.append(rowIndex).append(") action: ").append(pendingInstruction).append("\n");
            sb.append("   expected: ").append(pendingExpected).append("\n");
            sb.append("   actual: pending - observe actual result in this turn\n");
            sb.append("   judge_prev: pending - evaluate previous action outcome in this turn\n");
            sb.append("   judge_global: pending - evaluate global progress in this turn\n");
            sb.append("   carry_context: ").append(!pendingCarryContext.isEmpty() ? pendingCarryContext : "none").append("\n");
        }
    }

    public String renderPromptBlock() {
        StringBuilder sb = new StringBuilder();
        appendPromptBlock(sb);
        return sb.toString();
    }

    public static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> safe = row != null ? row : new LinkedHashMap<String, Object>();
        String jp = firstNonEmpty(stringOrEmpty(safe.get(KEY_JUDGEMENT_PREV)), stringOrEmpty(safe.get(KEY_LEGACY_JUDGEMENT)));
        String jg = stringOrEmpty(safe.get(KEY_JUDGEMENT_GLOBAL));
        if (jg.isEmpty()) {
            jg = jp;
        }
        out.put(KEY_INSTRUCTION, stringOrEmpty(safe.get(KEY_INSTRUCTION)));
        out.put(KEY_EXPECTED, stringOrEmpty(safe.get(KEY_EXPECTED)));
        out.put(KEY_ACTUAL, stringOrEmpty(safe.get(KEY_ACTUAL)));
        out.put(KEY_JUDGEMENT_PREV, firstNonEmpty(jp, "unknown"));
        out.put(KEY_JUDGEMENT_GLOBAL, firstNonEmpty(jg, firstNonEmpty(jp, "unknown")));
        out.put(KEY_LEGACY_JUDGEMENT, firstNonEmpty(jp, "unknown"));
        out.put(KEY_CARRY_CONTEXT, firstNonEmpty(stringOrEmpty(safe.get(KEY_CARRY_CONTEXT)), "none"));
        return out;
    }

    public static Map<String, Object> rowFromVision(Map<String, Object> vision, String semanticNote, String rawCommand) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        Map<String, Object> safe = vision != null ? vision : new LinkedHashMap<String, Object>();
        row.put(KEY_INSTRUCTION, firstNonEmpty(
                stringOrEmpty(safe.get("action")),
                firstNonEmpty(stringOrEmpty(safe.get("instruction")), stringOrEmpty(rawCommand))
        ));
        row.put(KEY_EXPECTED, stringOrEmpty(safe.get("expected")));
        row.put(KEY_ACTUAL, firstNonEmpty(
                stringOrEmpty(safe.get("Ovserve_result")),
                firstNonEmpty(stringOrEmpty(safe.get("Observe_result")), stringOrEmpty(semanticNote))
        ));
        String jp = firstNonEmpty(
                stringOrEmpty(safe.get("Judge_prev_result")),
                stringOrEmpty(safe.get("Judge_result"))
        );
        String jg = firstNonEmpty(stringOrEmpty(safe.get("Judge_global_result")), jp);
        row.put(KEY_JUDGEMENT_PREV, firstNonEmpty(jp, "unknown"));
        row.put(KEY_JUDGEMENT_GLOBAL, firstNonEmpty(jg, firstNonEmpty(jp, "unknown")));
        row.put(KEY_CARRY_CONTEXT, firstNonEmpty(stringOrEmpty(safe.get("carry_context")), "none"));
        return normalizeRow(row);
    }

    public static Map<String, Object> rowFromTaskMapStep(TaskMap.Step step, Map<String, Object> execSummary) {
        Map<String, Object> seed = step != null && step.history != null
                ? new LinkedHashMap<String, Object>(step.history)
                : new LinkedHashMap<String, Object>();
        Map<String, Object> summary = execSummary != null ? execSummary : new LinkedHashMap<String, Object>();

        String result = stringOrEmpty(summary.get("result"));
        String reason = stringOrEmpty(summary.get("reason"));
        String picked = stringOrEmpty(summary.get("picked_stage"));
        String op = stringOrEmpty(step != null ? step.op : "");
        String stepId = stringOrEmpty(step != null ? step.stepId : "");
        String instruction = firstNonEmpty(
                stringOrEmpty(seed.get(KEY_INSTRUCTION)),
                firstNonEmpty(
                        stringOrEmpty(step != null && step.semanticLocator != null ? step.semanticLocator.get("instruction") : ""),
                        firstNonEmpty(
                                stringOrEmpty(step != null ? step.semanticNote : ""),
                                "Replay route step " + stepId
                        )
                )
        );
        String expected = firstNonEmpty(
                stringOrEmpty(seed.get(KEY_EXPECTED)),
                stringOrEmpty(step != null ? step.expected : "")
        );
        StringBuilder actual = new StringBuilder();
        actual.append("SCRIPT_ACT replay");
        if (!op.isEmpty()) actual.append(" op=").append(op);
        if (!stepId.isEmpty()) actual.append(" step=").append(stepId);
        if (!result.isEmpty()) actual.append(" result=").append(result);
        if (!picked.isEmpty()) actual.append(" picked_stage=").append(picked);
        if (!reason.isEmpty()) actual.append(" reason=").append(reason);

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put(KEY_INSTRUCTION, instruction);
        row.put(KEY_EXPECTED, expected);
        row.put(KEY_ACTUAL, firstNonEmpty(actual.toString(), stringOrEmpty(seed.get(KEY_ACTUAL))));
        boolean ok = "ok".equalsIgnoreCase(result);
        row.put(KEY_JUDGEMENT_PREV, ok ? "script_replay_ok" : "script_replay_failed");
        row.put(KEY_JUDGEMENT_GLOBAL, ok ? "route_replay_progress" : "route_replay_fallback");
        row.put(KEY_CARRY_CONTEXT, firstNonEmpty(stringOrEmpty(seed.get(KEY_CARRY_CONTEXT)), "none"));
        return normalizeRow(row);
    }

    public static Map<String, Object> recoveryRow(TaskMap.Step step, boolean recovered, String reason) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        String stepId = stringOrEmpty(step != null ? step.stepId : "");
        row.put(KEY_INSTRUCTION, "Recover route replay blocker for step " + stepId);
        row.put(KEY_EXPECTED, "Dismiss only a blocking popup/overlay, then retry the same route step.");
        row.put(KEY_ACTUAL, "SCRIPT_ACT recovery " + (recovered ? "succeeded" : "did not recover") + ": " + stringOrEmpty(reason));
        row.put(KEY_JUDGEMENT_PREV, recovered ? "script_recovery_ok" : "script_recovery_failed");
        row.put(KEY_JUDGEMENT_GLOBAL, recovered ? "route_replay_progress" : "route_replay_fallback");
        row.put(KEY_CARRY_CONTEXT, "none");
        return normalizeRow(row);
    }

    private static boolean isEmptyRow(Map<String, Object> row) {
        return stringOrEmpty(row.get(KEY_INSTRUCTION)).isEmpty()
                && stringOrEmpty(row.get(KEY_EXPECTED)).isEmpty()
                && stringOrEmpty(row.get(KEY_ACTUAL)).isEmpty();
    }

    public static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String firstNonEmpty(String a, String b) {
        String av = stringOrEmpty(a);
        return !av.isEmpty() ? av : stringOrEmpty(b);
    }
}
