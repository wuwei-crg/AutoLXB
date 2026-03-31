package com.lxb.server.cortex.notify;

import com.lxb.server.cortex.LlmClient;
import com.lxb.server.cortex.LlmConfig;

import java.util.Locale;

public class NotificationTaskRewriter {
    private static final int NOTIFY_LLM_TIMEOUT_MS = 60_000;

    public static class ConditionResult {
        public final boolean match;
        public final String raw;
        public final String error;

        public ConditionResult(boolean match, String raw, String error) {
            this.match = match;
            this.raw = raw != null ? raw : "";
            this.error = error != null ? error : "";
        }
    }

    public static class RewriteResult {
        public final boolean ok;
        public final String finalTask;
        public final String raw;
        public final String error;

        public RewriteResult(boolean ok, String finalTask, String raw, String error) {
            this.ok = ok;
            this.finalTask = finalTask != null ? finalTask : "";
            this.raw = raw != null ? raw : "";
            this.error = error != null ? error : "";
        }
    }

    private final LlmClient llmClient;

    public NotificationTaskRewriter(LlmClient llmClient) {
        this.llmClient = llmClient != null ? llmClient : new LlmClient();
    }

    public ConditionResult evaluateCondition(NotificationTriggerRule rule, NotificationEvent event) {
        if (rule == null || event == null) {
            return new ConditionResult(false, "", "invalid_input");
        }
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            String prompt = buildConditionPrompt(rule.llmCondition, event);
            String raw = llmClient.chatOnce(cfg, null, prompt, null, NOTIFY_LLM_TIMEOUT_MS, NOTIFY_LLM_TIMEOUT_MS);
            String token = normalizeToken(raw);
            if (token.equals(rule.llmYesToken)) {
                return new ConditionResult(true, raw, "");
            }
            if (token.equals(rule.llmNoToken)) {
                return new ConditionResult(false, raw, "");
            }
            return new ConditionResult(false, raw, "invalid_yes_no_token");
        } catch (Exception e) {
            return new ConditionResult(false, "", String.valueOf(e));
        }
    }

    public RewriteResult rewriteTask(String userRequirement, NotificationEvent event, String preferredPackage) {
        String requirement = userRequirement != null ? userRequirement.trim() : "";
        if (requirement.isEmpty()) {
            return new RewriteResult(false, "", "", "empty_requirement");
        }
        if (event == null) {
            return new RewriteResult(false, "", "", "empty_event");
        }
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            String targetPackage = preferredPackage != null ? preferredPackage.trim() : "";
            if (targetPackage.isEmpty()) {
                targetPackage = event.packageName != null ? event.packageName.trim() : "";
            }
            String prompt = buildRewritePrompt(requirement, event, targetPackage);
            String raw = llmClient.chatOnce(cfg, null, prompt, null, NOTIFY_LLM_TIMEOUT_MS, NOTIFY_LLM_TIMEOUT_MS);
            String task = normalizeTaskLine(raw);
            if (task.isEmpty()) {
                return new RewriteResult(false, "", raw, "empty_rewrite_output");
            }
            if (task.length() > 200) {
                return new RewriteResult(false, "", raw, "rewrite_output_too_long");
            }
            return new RewriteResult(true, task, raw, "");
        } catch (Exception e) {
            return new RewriteResult(false, "", "", String.valueOf(e));
        }
    }

    private static String buildConditionPrompt(String cond, NotificationEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a strict binary classifier.\n");
        sb.append("Task: Decide whether a notification matches the user condition.\n");
        sb.append("Return only one token: yes or no.\n\n");
        sb.append("User condition:\n");
        sb.append(cond != null ? cond : "").append("\n\n");
        sb.append("Notification:\n");
        sb.append("- package: ").append(e.packageName).append("\n");
        sb.append("- title: ").append(e.title).append("\n");
        sb.append("- text: ").append(e.text).append("\n");
        sb.append("- ticker: ").append(e.ticker).append("\n");
        sb.append("- post_time_ms: ").append(e.postTime).append("\n");
        return sb.toString();
    }

    private static String buildRewritePrompt(String requirement, NotificationEvent e, String targetPackage) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a task rewriter for Android automation.\n");
        sb.append("Given user requirement and one notification, output exactly one Chinese sentence as the final task.\n");
        sb.append("Do not output JSON. Do not output explanation.\n");
        sb.append("The sentence must be specific and executable.\n");
        sb.append("Hard constraints:\n");
        sb.append("1) Must explicitly include the software/app to operate (app name or package id).\n");
        sb.append("2) Must describe concrete operation path, e.g. \"打开微信，进入XX群聊，回复...\".\n");
        sb.append("3) Do not use vague words such as 某软件/某应用/某群聊/某人/某消息/某内容.\n");
        sb.append("4) If notification title/text contains concrete target (group/person), reuse it directly.\n\n");
        sb.append("User requirement:\n");
        sb.append(requirement).append("\n\n");
        sb.append("Preferred target app package:\n");
        sb.append(targetPackage != null ? targetPackage : "").append("\n\n");
        sb.append("Notification:\n");
        sb.append("- package: ").append(e.packageName).append("\n");
        sb.append("- title: ").append(e.title).append("\n");
        sb.append("- text: ").append(e.text).append("\n");
        sb.append("- ticker: ").append(e.ticker).append("\n");
        sb.append("- post_time_ms: ").append(e.postTime).append("\n");
        return sb.toString();
    }

    private static String normalizeToken(String raw) {
        if (raw == null) return "";
        String line = raw.trim();
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).trim();
        }
        line = line.toLowerCase(Locale.ROOT);
        if ("yes".equals(line)) return "yes";
        if ("no".equals(line)) return "no";
        if (line.startsWith("yes")) return "yes";
        if (line.startsWith("no")) return "no";
        return line;
    }

    private static String normalizeTaskLine(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl).trim();
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
