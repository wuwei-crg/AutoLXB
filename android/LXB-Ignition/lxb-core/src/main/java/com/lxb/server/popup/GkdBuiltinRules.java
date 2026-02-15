package com.lxb.server.popup;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * GKD 内置规则集 (保守版)
 *
 * 只保留高置信度的匹配规则，避免误触正常界面元素。
 *
 * 设计原则:
 * 1. 开屏广告规则可以稍微激进 (因为开屏页面元素简单)
 * 2. 普通弹窗规则要保守 (避免误触正常按钮)
 * 3. 优先匹配特征明显的元素 (如 "跳过广告"、"×" 图标)
 */
public class GkdBuiltinRules {

    /**
     * 简化规则
     */
    public static class SimpleRule {
        public String name;
        public int groupKey;
        public MatchCondition[] conditions;
        public String action = "click";
        public int priority;  // 优先级，数字越大越优先

        public SimpleRule(String name, int groupKey, int priority, MatchCondition... conditions) {
            this.name = name;
            this.groupKey = groupKey;
            this.priority = priority;
            this.conditions = conditions;
        }
    }

    /**
     * 匹配条件
     */
    public static class MatchCondition {
        public enum Field { TEXT, DESC, ID, VID }
        public enum Op { EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX }

        public Field field;
        public Op op;
        public String value;
        public Pattern pattern;

        public MatchCondition(Field field, Op op, String value) {
            this.field = field;
            this.op = op;
            this.value = value;
            if (op == Op.REGEX) {
                try {
                    this.pattern = Pattern.compile(value, Pattern.CASE_INSENSITIVE);
                } catch (Exception e) {
                    this.pattern = null;
                }
            }
        }

        public static MatchCondition textEquals(String v) { return new MatchCondition(Field.TEXT, Op.EQUALS, v); }
        public static MatchCondition textContains(String v) { return new MatchCondition(Field.TEXT, Op.CONTAINS, v); }
        public static MatchCondition textStartsWith(String v) { return new MatchCondition(Field.TEXT, Op.STARTS_WITH, v); }
        public static MatchCondition textEndsWith(String v) { return new MatchCondition(Field.TEXT, Op.ENDS_WITH, v); }
        public static MatchCondition textRegex(String v) { return new MatchCondition(Field.TEXT, Op.REGEX, v); }

        public static MatchCondition descEquals(String v) { return new MatchCondition(Field.DESC, Op.EQUALS, v); }
        public static MatchCondition descContains(String v) { return new MatchCondition(Field.DESC, Op.CONTAINS, v); }
        public static MatchCondition descRegex(String v) { return new MatchCondition(Field.DESC, Op.REGEX, v); }

        public static MatchCondition idContains(String v) { return new MatchCondition(Field.ID, Op.CONTAINS, v); }
        public static MatchCondition idRegex(String v) { return new MatchCondition(Field.ID, Op.REGEX, v); }

        public static MatchCondition vidContains(String v) { return new MatchCondition(Field.VID, Op.CONTAINS, v); }
        public static MatchCondition vidRegex(String v) { return new MatchCondition(Field.VID, Op.REGEX, v); }
    }

    /**
     * 获取所有内置规则
     */
    public static List<SimpleRule> getAllRules() {
        List<SimpleRule> rules = new ArrayList<>();

        // =====================================================================
        // 开屏广告规则 (groupKey = 0) - 可以稍微激进
        // =====================================================================

        // 高置信度: 明确的跳过广告文本
        rules.add(new SimpleRule("跳过广告文本", 0, 100,
            MatchCondition.textEquals("跳过广告"),
            MatchCondition.textEquals("跳過廣告"),
            MatchCondition.textEquals("点击跳过"),
            MatchCondition.textEquals("點擊跳過"),
            MatchCondition.descEquals("跳过广告"),
            MatchCondition.descEquals("点击跳过")
        ));

        // 高置信度: 跳过 + 数字/秒
        rules.add(new SimpleRule("跳过倒计时", 0, 90,
            MatchCondition.textRegex("^跳过\\s*\\d+$"),           // "跳过 3" 或 "跳过3"
            MatchCondition.textRegex("^跳过\\s*\\d+\\s*[sS秒]$"), // "跳过 3s" 或 "跳过3秒"
            MatchCondition.textRegex("^\\d+\\s*[sS秒]?\\s*跳过$"), // "3s 跳过" 或 "3 跳过"
            MatchCondition.textRegex("^\\d+\\s*[sS秒]\\s*后跳过$"), // "3秒后跳过"
            MatchCondition.descRegex("^跳过\\s*\\d+"),
            MatchCondition.descRegex("^\\d+\\s*[sS秒]?\\s*跳过")
        ));

        // 中置信度: 单独的 "跳过" (需要配合位置检查)
        rules.add(new SimpleRule("跳过按钮", 0, 70,
            MatchCondition.textEquals("跳过"),
            MatchCondition.textEquals("跳過"),
            MatchCondition.textEquals("SKIP"),
            MatchCondition.textEquals("Skip"),
            MatchCondition.textEquals("skip"),
            MatchCondition.descEquals("跳过"),
            MatchCondition.descEquals("skip")
        ));

        // 中置信度: resource-id 包含 skip + splash
        rules.add(new SimpleRule("跳过ID", 0, 60,
            MatchCondition.idRegex(".*splash.*skip.*"),
            MatchCondition.idRegex(".*skip.*splash.*"),
            MatchCondition.idRegex(".*tt_splash_skip.*"),
            MatchCondition.vidRegex(".*splash.*skip.*"),
            MatchCondition.vidRegex("skip_btn"),
            MatchCondition.vidRegex("btn_skip"),
            MatchCondition.vidRegex("skipButton")
        ));

        // =====================================================================
        // 弹窗关闭图标 (groupKey = 1) - 高置信度
        // =====================================================================

        // 高置信度: 关闭图标字符 (叉号)
        rules.add(new SimpleRule("关闭图标", 1, 100,
            MatchCondition.textEquals("×"),
            MatchCondition.textEquals("✕"),
            MatchCondition.textEquals("✖"),
            MatchCondition.textEquals("✗"),
            MatchCondition.textEquals("✘"),
            MatchCondition.textEquals("╳"),
            MatchCondition.textEquals("X"),  // 大写 X 作为关闭
            MatchCondition.descEquals("×"),
            MatchCondition.descEquals("✕"),
            MatchCondition.descEquals("关闭"),
            MatchCondition.descEquals("關閉"),
            MatchCondition.descEquals("close"),
            MatchCondition.descEquals("Close")
        ));

        // 高置信度: resource-id 明确是关闭按钮
        rules.add(new SimpleRule("关闭按钮ID", 1, 90,
            MatchCondition.vidRegex("^close$"),
            MatchCondition.vidRegex("^closeBtn$"),
            MatchCondition.vidRegex("^btn_close$"),
            MatchCondition.vidRegex("^iv_close$"),
            MatchCondition.vidRegex("^close_btn$"),
            MatchCondition.vidRegex("^close_iv$"),
            MatchCondition.vidRegex("^dialog_close$"),
            MatchCondition.vidRegex("^popup_close$"),
            MatchCondition.vidRegex("^ad_close$"),
            MatchCondition.vidRegex("^closeButton$")
        ));

        // =====================================================================
        // 更新提示弹窗 (groupKey = 1) - 保守
        // =====================================================================

        // 高置信度: 明确的拒绝更新文本
        rules.add(new SimpleRule("拒绝更新", 1, 80,
            MatchCondition.textEquals("暂不更新"),
            MatchCondition.textEquals("暂不升级"),
            MatchCondition.textEquals("忽略此版本"),
            MatchCondition.textEquals("忽略本次"),
            MatchCondition.textEquals("残忍拒绝"),
            MatchCondition.textEquals("狠心拒绝"),
            MatchCondition.textEquals("下次再说"),
            MatchCondition.textEquals("以后再说"),
            MatchCondition.textEquals("稍后提醒"),
            MatchCondition.textEquals("稍後提醒"),
            MatchCondition.descEquals("暂不更新"),
            MatchCondition.descEquals("以后再说")
        ));

        // =====================================================================
        // 青少年模式 (groupKey = 2) - 保守
        // =====================================================================

        rules.add(new SimpleRule("青少年模式关闭", 2, 80,
            MatchCondition.textEquals("我知道了"),
            MatchCondition.textEquals("已满18岁"),
            MatchCondition.textEquals("已满14岁"),
            MatchCondition.descEquals("我知道了"),
            MatchCondition.descEquals("关闭")
        ));

        // =====================================================================
        // 通知权限 (groupKey = 3) - 保守
        // =====================================================================

        rules.add(new SimpleRule("拒绝通知", 3, 80,
            MatchCondition.textEquals("暂不开启"),
            MatchCondition.textEquals("不再提醒"),
            MatchCondition.textEquals("不允许"),
            MatchCondition.textEquals("拒绝"),
            MatchCondition.textRegex("^Don't [Aa]llow$"),
            MatchCondition.textRegex("^Deny$"),
            MatchCondition.descEquals("不允许"),
            MatchCondition.descEquals("拒绝")
        ));

        // =====================================================================
        // 评价提示 (groupKey = 4) - 保守
        // =====================================================================

        rules.add(new SimpleRule("拒绝评价", 4, 80,
            MatchCondition.textEquals("不了，谢谢"),
            MatchCondition.textEquals("不了,谢谢"),
            MatchCondition.textEquals("残忍拒绝"),
            MatchCondition.textEquals("以后再说"),
            MatchCondition.textRegex("^[Nn]ot [Nn]ow$"),
            MatchCondition.textRegex("^[Mm]aybe [Ll]ater$"),
            MatchCondition.textRegex("^[Nn]o [Tt]hanks$")
        ));

        return rules;
    }

    /**
     * 获取指定规则组的规则
     */
    public static List<SimpleRule> getRulesByGroup(int groupKey) {
        List<SimpleRule> result = new ArrayList<>();
        for (SimpleRule rule : getAllRules()) {
            if (rule.groupKey == groupKey) {
                result.add(rule);
            }
        }
        return result;
    }
}
