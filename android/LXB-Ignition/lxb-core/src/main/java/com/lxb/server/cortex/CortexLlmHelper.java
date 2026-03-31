package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prompt/output helper methods extracted from CortexFsmEngine.
 */
public final class CortexLlmHelper {

    private CortexLlmHelper() {
    }

    public static String buildTaskDecomposePrompt(CortexFsmEngine.Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> c : ctx.appCandidates) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            String pkg = stringOrEmpty(c.get("package"));
            String label = stringOrEmpty(c.get("label"));
            if (label.isEmpty()) {
                label = stringOrEmpty(c.get("name"));
            }
            if (pkg.isEmpty()) {
                continue;
            }
            row.put("package", pkg);
            row.put("label", label.isEmpty() ? null : label);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that decomposes a high-level Android user task into a small number of high-level sub_tasks.\n");
        sb.append("Important execution capability:\n");
        sb.append("- The downstream VISION_ACT stage already has built-in LLM abilities: understanding, extraction, summarization, reasoning, and rewrite.\n");
        sb.append("- Do NOT create a sub_task that opens any AI/chat app for summarization/analysis unless the user explicitly requires that specific app.\n");
        sb.append("- For tasks like \"collect info then summarize\", keep summarization inside the same sub_task flow, not as a separate AI-app sub_task.\n\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"label\"}):\n");
        sb.append("(label may be null; when null, infer app intent from package id)\n");
        sb.append(Json.stringify(payload)).append("\n\n");
        sb.append("Output JSON only, no extra text:\n");
        sb.append("{\"sub_tasks\":[{...}],\"task_type\":\"single|loop|mixed\"}\n");
        sb.append("Definition of sub_task (very important):\n");
        sb.append("- A sub_task is a self-contained sub-goal that the agent can execute as a mini-workflow.\n");
        sb.append("- It is NOT a single UI click or a tiny step inside one screen.\n");
        sb.append("- It usually corresponds to one user-intent like \"view my followers\" or \"send a message with a link\".\n");
        sb.append("- All low-level UI steps (open app, tap tabs, tap buttons) to achieve that intent belong INSIDE one sub_task, not as separate sub_tasks.\n");
        sb.append("\n");
        sb.append("When to create multiple sub_tasks:\n");
        sb.append("- If the user describes multiple distinct goals or phases, even in the same app, use multiple sub_tasks.\n");
        sb.append("  Example: \"open Xiaohongshu, check my followers, then check my following\" ->\n");
        sb.append("    sub_task_1: open Xiaohongshu and view my followers.\n");
        sb.append("    sub_task_2: open Xiaohongshu and view my following.\n");
        sb.append("- If the user task involves multiple apps (e.g., copy link in app A, send link in app B), use multiple sub_tasks (one per high-level goal).\n");
        sb.append("- If the task is a single simple goal in one app, use exactly one sub_task.\n");
        sb.append("\n");
        sb.append("What NOT to do (wrong decomposition):\n");
        sb.append("- Do NOT break a single high-level goal into micro-steps per click.\n");
        sb.append("  Wrong: \"open app\", \"tap Me\", \"tap Followers\" as three sub_tasks.\n");
        sb.append("  Correct: one sub_task \"open the app and view my followers\".\n");
        sb.append("- Do NOT create one sub_task per UI element on a single page.\n");
        sb.append("\n");
        sb.append("Meaning of modes:\n");
        sb.append("- mode=\"single\": the sub_task is executed once and has a single completion condition.\n");
        sb.append("  Examples: post one dynamic, send one message, view my followers once.\n");
        sb.append("- mode=\"loop\": the sub_task needs to repeat an operation over a set of items whose size is not known from the text.\n");
        sb.append("  Examples: sign in to all forums, like all unread posts, clear all unread notifications.\n");
        sb.append("- For loop sub_tasks, do NOT create one sub_task per item; instead create a single loop sub_task that covers all items.\n");
        sb.append("\n");
        sb.append("Each sub_task MUST have fields: id, description, mode, inputs, outputs, success_criteria.\n");
        sb.append("Optional fields are allowed (for example app_hint), but they are not required.\n");
        sb.append("Additional rules:\n");
        sb.append("1) mode is either \"single\" or \"loop\".\n");
        sb.append("2) For loop sub_tasks, add loop_metadata with loop_unit, loop_target_condition, loop_termination_criteria, max_iterations.\n");
        sb.append("3) If the task is simple and fits in one app, return a single sub_task.\n");
        sb.append("4) For multi-app tasks, break into multiple sub_tasks and wire outputs/inputs.\n");
        sb.append("5) sub_tasks count should usually be between 1 and 5, never dozens.\n");
        sb.append("6) Do NOT output markdown, code fences, or comments.\n");
        return sb.toString();
    }

    public static String buildTaskDecomposeSystemPrompt() {
        return "You are an assistant that decomposes a high-level Android user task into a small number of high-level sub_tasks for an automation agent.\n"
                + "sub_tasks are independent sub-goals (like \"view my followers\" or \"send a link\"), NOT individual button clicks.\n"
                + "VISION_ACT already has built-in LLM capability (understanding/summarization/reasoning/rewrite), so do not add AI/chat-app sub_tasks unless the user explicitly names that app.\n"
                + "You MUST output strict JSON with fields: sub_tasks (array) and task_type.\n"
                + "Do not output markdown, code fences, or any commentary outside the JSON.";
    }

    public static String buildAppResolvePrompt(CortexFsmEngine.Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> c : ctx.appCandidates) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            String pkg = stringOrEmpty(c.get("package"));
            String label = stringOrEmpty(c.get("label"));
            if (label.isEmpty()) {
                label = stringOrEmpty(c.get("name"));
            }
            if (pkg.isEmpty()) {
                continue;
            }
            row.put("package", pkg);
            row.put("label", label.isEmpty() ? null : label);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that selects the best Android app to handle a task.\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"label\"}):\n");
        sb.append("(label may be null; when null, infer app intent from package id)\n");
        sb.append(Json.stringify(payload)).append("\n\n");
        sb.append("Output JSON only, no extra text:\n");
        sb.append("{\"package_name\":\"one_package_from_apps\"}\n");
        sb.append("Rules:\n");
        sb.append("1) package_name MUST be exactly one of the \"package\" values above.\n");
        sb.append("2) package_name MUST be a package id string (e.g., com.tencent.mm), NOT app label/name.\n");
        sb.append("3) If the task clearly refers to a specific brand (e.g., Bilibili, Taobao), map it to that app.\n");
        sb.append("4) If ambiguous, choose the app that typical users would most likely use.\n");
        sb.append("5) Do NOT explain, do NOT add markdown, do NOT add comments.\n");
        return sb.toString();
    }

    public static String buildAppResolveSystemPrompt() {
        return "You are an assistant that selects the best Android app to handle a task.\n"
                + "You MUST output strict JSON only with a single field: package_name.\n"
                + "package_name must be an installed package id (contains dots), never a human-readable app label.\n"
                + "Do not output markdown or any extra commentary.";
    }

    public static String extractPackageFromResponse(String raw) {
        Map<String, Object> obj = extractJsonObjectFromText(raw);
        if (obj.isEmpty()) {
            return "";
        }
        String pkg = stringOrEmpty(obj.get("package_name"));
        if (pkg.isEmpty()) {
            pkg = stringOrEmpty(obj.get("package"));
        }
        return pkg;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> extractJsonObjectFromText(String text) {
        String s = text != null ? text.trim() : "";
        if (s.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Map<String, Object> obj = Json.parseObject(s);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }

        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            return new LinkedHashMap<String, Object>();
        }
        String slice = s.substring(start, end + 1);
        try {
            Map<String, Object> obj = Json.parseObject(slice);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<String, Object>();
    }

    public static String normalizeModelOutput(String raw, CortexFsmEngine.State state, CortexFsmEngine.Context ctx) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty() || !text.startsWith("{")) {
            return text;
        }
        Map<String, Object> obj;
        try {
            obj = Json.parseObject(text);
        } catch (Exception e) {
            return text;
        }
        if (state == CortexFsmEngine.State.APP_RESOLVE) {
            String pkg = stringOrEmpty(obj.get("package_name"));
            if (pkg.isEmpty()) {
                pkg = stringOrEmpty(obj.get("package"));
            }
            if (!pkg.isEmpty()) {
                return "SET_APP " + pkg;
            }
        }
        if (state == CortexFsmEngine.State.ROUTE_PLAN) {
            String pkg = stringOrEmpty(obj.get("package_name"));
            if (pkg.isEmpty()) {
                pkg = ctx.selectedPackage != null ? ctx.selectedPackage : "";
            }
            String target = stringOrEmpty(obj.get("target_page"));
            if (!pkg.isEmpty() && !target.isEmpty()) {
                return "ROUTE " + pkg + " " + target;
            }
        }
        return text;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
