package com.lxb.server.popup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GKD 规则解析器
 *
 * 解析 GKD JSON5 格式的规则文件。
 * 由于 Android 环境没有 JSON5 库，使用简化的正则解析。
 *
 * 规则文件位置: /sdcard/lxb/gkd_rules.json5
 */
public class GkdRuleParser {

    private static final String TAG = "[LXB][GkdParser]";

    // 默认规则文件路径
    private static final String DEFAULT_RULE_PATH = "/sdcard/lxb/gkd_rules.json5";

    // 内置的简化规则 (用于无规则文件时的后备)
    private static final String[][] BUILTIN_RULES = {
        // 开屏广告
        {"text", "跳过"},
        {"text", "跳过广告"},
        {"text", "skip"},
        {"text", "Skip"},
        {"text", "SKIP"},
        {"text~", "(?i)跳[\\s]*过.*"},
        {"text~", "(?i)\\d+[sS秒].*跳过"},
        {"desc", "跳过"},
        {"desc", "跳过广告"},
        {"id~", ".*skip.*"},
        {"id~", ".*splash.*skip.*"},

        // 更新提示
        {"text", "以后再说"},
        {"text", "暂不更新"},
        {"text", "稍后再说"},
        {"text", "下次再说"},
        {"text", "取消"},
        {"text", "暂不升级"},
        {"text", "忽略此版本"},
        {"text", "残忍拒绝"},
        {"text", "我知道了"},

        // 青少年模式
        {"text", "我知道了"},
        {"text", "知道了"},
        {"text", "关闭"},
        {"text~", "(?i).*青少年.*知道.*"},

        // 通知权限
        {"text", "暂不开启"},
        {"text", "以后再说"},
        {"text", "不允许"},
        {"text", "拒绝"},

        // 评价提示
        {"text", "不了，谢谢"},
        {"text", "以后再说"},
        {"text", "残忍拒绝"},
    };

    /**
     * 从文件加载规则
     */
    public GkdRule loadFromFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.out.println(TAG + " Rule file not found: " + path);
            return null;
        }

        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            return parseJson5(content.toString());

        } catch (Exception e) {
            System.err.println(TAG + " Failed to load rules: " + e.getMessage());
            return null;
        }
    }

    /**
     * 加载默认规则文件
     */
    public GkdRule loadDefault() {
        GkdRule rule = loadFromFile(DEFAULT_RULE_PATH);
        if (rule != null) {
            System.out.println(TAG + " Loaded rules from " + DEFAULT_RULE_PATH);
            return rule;
        }

        // 使用内置规则
        System.out.println(TAG + " Using builtin rules");
        return createBuiltinRules();
    }

    /**
     * 创建内置规则
     */
    private GkdRule createBuiltinRules() {
        GkdRule rule = new GkdRule();

        // 开屏广告规则组
        GkdRule.RuleGroup splashGroup = new GkdRule.RuleGroup();
        splashGroup.key = 0;
        splashGroup.name = "开屏广告";
        splashGroup.matchTime = 9000;
        splashGroup.actionMaximum = 2;

        // 更新提示规则组
        GkdRule.RuleGroup updateGroup = new GkdRule.RuleGroup();
        updateGroup.key = 1;
        updateGroup.name = "更新提示";
        updateGroup.matchTime = 10000;
        updateGroup.actionMaximum = 2;

        // 青少年模式规则组
        GkdRule.RuleGroup teenGroup = new GkdRule.RuleGroup();
        teenGroup.key = 2;
        teenGroup.name = "青少年模式";
        teenGroup.matchTime = 10000;
        teenGroup.actionMaximum = 1;

        // 将内置规则分配到各组
        for (String[] builtinRule : BUILTIN_RULES) {
            String type = builtinRule[0];
            String value = builtinRule[1];

            GkdRule.Rule r = new GkdRule.Rule();
            r.matches = buildSelector(type, value);

            // 根据关键词分配到不同组
            if (value.contains("跳过") || value.contains("skip") || value.contains("Skip") ||
                value.contains("SKIP") || value.contains("splash")) {
                splashGroup.rules.add(r);
            } else if (value.contains("更新") || value.contains("升级") || value.contains("版本") ||
                       value.contains("暂不") || value.contains("稍后") || value.contains("下次")) {
                updateGroup.rules.add(r);
            } else if (value.contains("青少年") || value.contains("知道了")) {
                teenGroup.rules.add(r);
            } else {
                // 默认加入更新提示组
                updateGroup.rules.add(r);
            }
        }

        rule.globalGroups.add(splashGroup);
        rule.globalGroups.add(updateGroup);
        rule.globalGroups.add(teenGroup);

        System.out.println(TAG + " Created builtin rules: " +
            "splash=" + splashGroup.rules.size() +
            ", update=" + updateGroup.rules.size() +
            ", teen=" + teenGroup.rules.size());

        return rule;
    }

    /**
     * 构建选择器字符串
     */
    private String buildSelector(String type, String value) {
        switch (type) {
            case "text":
                return "[text=\"" + value + "\"]";
            case "text~":
                return "[text~=\"" + value + "\"]";
            case "desc":
                return "[desc=\"" + value + "\"]";
            case "desc~":
                return "[desc~=\"" + value + "\"]";
            case "id":
                return "[id=\"" + value + "\"]";
            case "id~":
                return "[id~=\"" + value + "\"]";
            default:
                return "[text=\"" + value + "\"]";
        }
    }

    /**
     * 解析 JSON5 格式的规则文件
     *
     * 简化解析，只提取关键字段:
     * - globalGroups[].rules[].matches
     * - globalGroups[].rules[].anyMatches
     * - apps[].id
     * - apps[].groups[].rules[].matches
     */
    private GkdRule parseJson5(String content) {
        GkdRule rule = new GkdRule();

        try {
            // 解析 globalGroups
            rule.globalGroups = parseGlobalGroups(content);
            System.out.println(TAG + " Parsed " + rule.globalGroups.size() + " global groups");

            // 解析 apps (简化版，只解析包名和启用状态)
            rule.apps = parseApps(content);
            System.out.println(TAG + " Parsed " + rule.apps.size() + " app rules");

        } catch (Exception e) {
            System.err.println(TAG + " Parse error: " + e.getMessage());
            e.printStackTrace();
        }

        return rule;
    }

    /**
     * 解析全局规则组
     */
    private List<GkdRule.RuleGroup> parseGlobalGroups(String content) {
        List<GkdRule.RuleGroup> groups = new ArrayList<>();

        // 找到 globalGroups 数组
        int start = content.indexOf("globalGroups:[");
        if (start < 0) {
            System.out.println(TAG + " globalGroups not found");
            return groups;
        }

        // 提取 globalGroups 数组内容
        int depth = 0;
        int arrayStart = start + "globalGroups:[".length();
        int arrayEnd = arrayStart;

        for (int i = arrayStart; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                if (depth == 0) {
                    arrayEnd = i;
                    break;
                }
                depth--;
            }
        }

        String groupsContent = content.substring(arrayStart, arrayEnd);

        // 解析每个规则组
        Pattern groupPattern = Pattern.compile("\\{key:(\\d+),name:'([^']*)'");
        Matcher groupMatcher = groupPattern.matcher(groupsContent);

        int lastEnd = 0;
        while (groupMatcher.find()) {
            GkdRule.RuleGroup group = new GkdRule.RuleGroup();
            group.key = Integer.parseInt(groupMatcher.group(1));
            group.name = groupMatcher.group(2);

            // 找到这个组的结束位置
            int groupStart = groupMatcher.start();
            int groupEnd = findMatchingBrace(groupsContent, groupStart);

            if (groupEnd > groupStart) {
                String groupContent = groupsContent.substring(groupStart, groupEnd + 1);

                // 解析 matchTime
                Pattern matchTimePattern = Pattern.compile("matchTime:(\\d+)");
                Matcher mtMatcher = matchTimePattern.matcher(groupContent);
                if (mtMatcher.find()) {
                    group.matchTime = Integer.parseInt(mtMatcher.group(1));
                }

                // 解析 actionMaximum
                Pattern actionMaxPattern = Pattern.compile("actionMaximum:(\\d+)");
                Matcher amMatcher = actionMaxPattern.matcher(groupContent);
                if (amMatcher.find()) {
                    group.actionMaximum = Integer.parseInt(amMatcher.group(1));
                }

                // 解析 rules
                group.rules = parseRules(groupContent);
            }

            groups.add(group);
            System.out.println(TAG + " Parsed group: " + group.name + " with " + group.rules.size() + " rules");
        }

        return groups;
    }

    /**
     * 解析规则数组
     */
    private List<GkdRule.Rule> parseRules(String groupContent) {
        List<GkdRule.Rule> rules = new ArrayList<>();

        // 找到 rules 数组
        int rulesStart = groupContent.indexOf("rules:[");
        if (rulesStart < 0) return rules;

        int arrayStart = rulesStart + "rules:[".length();
        int arrayEnd = findMatchingBracket(groupContent, rulesStart + "rules:".length());

        if (arrayEnd <= arrayStart) return rules;

        String rulesContent = groupContent.substring(arrayStart, arrayEnd);

        // 解析每条规则
        // 规则格式: {key:0, matches:'...', ...}
        int pos = 0;
        while (pos < rulesContent.length()) {
            int ruleStart = rulesContent.indexOf('{', pos);
            if (ruleStart < 0) break;

            int ruleEnd = findMatchingBrace(rulesContent, ruleStart);
            if (ruleEnd <= ruleStart) break;

            String ruleContent = rulesContent.substring(ruleStart, ruleEnd + 1);
            GkdRule.Rule rule = parseRule(ruleContent);
            if (rule != null && (rule.matches != null || (rule.anyMatches != null && !rule.anyMatches.isEmpty()))) {
                rules.add(rule);
            }

            pos = ruleEnd + 1;
        }

        return rules;
    }

    /**
     * 解析单条规则
     */
    private GkdRule.Rule parseRule(String ruleContent) {
        GkdRule.Rule rule = new GkdRule.Rule();

        // 解析 key
        Pattern keyPattern = Pattern.compile("key:(\\d+)");
        Matcher keyMatcher = keyPattern.matcher(ruleContent);
        if (keyMatcher.find()) {
            rule.key = Integer.parseInt(keyMatcher.group(1));
        }

        // 解析 matches (单引号字符串)
        rule.matches = extractQuotedString(ruleContent, "matches:");

        // 解析 anyMatches (数组)
        rule.anyMatches = extractStringArray(ruleContent, "anyMatches:");

        // 解析 excludeMatches (数组)
        rule.excludeMatches = extractStringArray(ruleContent, "excludeMatches:");

        // 解析 action
        String action = extractQuotedString(ruleContent, "action:");
        if (action != null) {
            rule.action = action;
        }

        return rule;
    }

    /**
     * 提取引号字符串
     */
    private String extractQuotedString(String content, String key) {
        int keyPos = content.indexOf(key);
        if (keyPos < 0) return null;

        int start = keyPos + key.length();
        // 跳过空白
        while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
            start++;
        }

        if (start >= content.length()) return null;

        char quote = content.charAt(start);
        if (quote != '\'' && quote != '"') return null;

        start++;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
                sb.append(c);
            } else if (c == quote) {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }

        return null;
    }

    /**
     * 提取字符串数组
     */
    private List<String> extractStringArray(String content, String key) {
        List<String> result = new ArrayList<>();

        int keyPos = content.indexOf(key);
        if (keyPos < 0) return result;

        int arrayStart = content.indexOf('[', keyPos);
        if (arrayStart < 0) return result;

        int arrayEnd = findMatchingBracket(content, arrayStart);
        if (arrayEnd <= arrayStart) return result;

        String arrayContent = content.substring(arrayStart + 1, arrayEnd);

        // 提取每个字符串
        int pos = 0;
        while (pos < arrayContent.length()) {
            // 找到引号开始
            int quoteStart = -1;
            char quote = 0;
            for (int i = pos; i < arrayContent.length(); i++) {
                char c = arrayContent.charAt(i);
                if (c == '\'' || c == '"') {
                    quoteStart = i;
                    quote = c;
                    break;
                }
            }

            if (quoteStart < 0) break;

            // 找到引号结束
            StringBuilder sb = new StringBuilder();
            boolean escaped = false;
            int quoteEnd = -1;

            for (int i = quoteStart + 1; i < arrayContent.length(); i++) {
                char c = arrayContent.charAt(i);
                if (escaped) {
                    sb.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                    sb.append(c);
                } else if (c == quote) {
                    quoteEnd = i;
                    break;
                } else {
                    sb.append(c);
                }
            }

            if (quoteEnd > quoteStart) {
                result.add(sb.toString());
                pos = quoteEnd + 1;
            } else {
                break;
            }
        }

        return result;
    }

    /**
     * 解析应用规则
     */
    private List<GkdRule.AppRule> parseApps(String content) {
        List<GkdRule.AppRule> apps = new ArrayList<>();

        // 找到 apps 数组
        int start = content.indexOf("apps:[");
        if (start < 0) {
            // 尝试另一种格式
            start = content.indexOf("\"apps\":[");
            if (start < 0) return apps;
            start += "\"apps\":[".length();
        } else {
            start += "apps:[".length();
        }

        // 简化解析：只提取 id 和 enable
        Pattern appPattern = Pattern.compile("\\{id:'([^']*)'(?:,enable:(true|false))?");
        Matcher appMatcher = appPattern.matcher(content.substring(start));

        while (appMatcher.find()) {
            GkdRule.AppRule app = new GkdRule.AppRule();
            app.id = appMatcher.group(1);
            if (appMatcher.group(2) != null) {
                app.enable = "true".equals(appMatcher.group(2));
            }
            apps.add(app);
        }

        return apps;
    }

    /**
     * 找到匹配的大括号
     */
    private int findMatchingBrace(String content, int start) {
        if (start >= content.length() || content.charAt(start) != '{') {
            // 找到第一个 {
            start = content.indexOf('{', start);
            if (start < 0) return -1;
        }

        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (inString) {
                if (c == stringChar) {
                    inString = false;
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                inString = true;
                stringChar = c;
                continue;
            }

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }

        return -1;
    }

    /**
     * 找到匹配的方括号
     */
    private int findMatchingBracket(String content, int start) {
        if (start >= content.length() || content.charAt(start) != '[') {
            start = content.indexOf('[', start);
            if (start < 0) return -1;
        }

        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (inString) {
                if (c == stringChar) {
                    inString = false;
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                inString = true;
                stringChar = c;
                continue;
            }

            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }

        return -1;
    }
}
