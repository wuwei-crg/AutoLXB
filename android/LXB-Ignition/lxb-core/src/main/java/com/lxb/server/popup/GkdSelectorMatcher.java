package com.lxb.server.popup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GKD 选择器匹配引擎
 *
 * 支持两种匹配模式:
 * 1. 简化规则匹配 (GkdBuiltinRules.SimpleRule)
 * 2. GKD 选择器字符串匹配 (部分支持)
 */
public class GkdSelectorMatcher {

    private static final String TAG = "[LXB][GkdMatcher]";

    // 反射缓存
    private Method getTextMethod;
    private Method getContentDescriptionMethod;
    private Method getViewIdResourceNameMethod;
    private Method getClassNameMethod;
    private Method isClickableMethod;
    private Method isVisibleToUserMethod;
    private Method isEnabledMethod;
    private Method getChildCountMethod;
    private Method getChildMethod;
    private Method getBoundsInScreenMethod;
    private Class<?> rectClass;

    private boolean initialized = false;

    /**
     * 初始化反射缓存
     */
    public void initialize() {
        if (initialized) return;

        try {
            Class<?> nodeClass = Class.forName("android.view.accessibility.AccessibilityNodeInfo");
            rectClass = Class.forName("android.graphics.Rect");

            getTextMethod = nodeClass.getMethod("getText");
            getContentDescriptionMethod = nodeClass.getMethod("getContentDescription");
            getViewIdResourceNameMethod = nodeClass.getMethod("getViewIdResourceName");
            getClassNameMethod = nodeClass.getMethod("getClassName");
            isClickableMethod = nodeClass.getMethod("isClickable");
            isVisibleToUserMethod = nodeClass.getMethod("isVisibleToUser");
            isEnabledMethod = nodeClass.getMethod("isEnabled");
            getChildCountMethod = nodeClass.getMethod("getChildCount");
            getChildMethod = nodeClass.getMethod("getChild", int.class);
            getBoundsInScreenMethod = nodeClass.getMethod("getBoundsInScreen", rectClass);

            initialized = true;
            System.out.println(TAG + " Initialized");

        } catch (Exception e) {
            System.err.println(TAG + " Init failed: " + e.getMessage());
        }
    }

    /**
     * 匹配结果
     */
    public static class MatchResult {
        public Object node;           // AccessibilityNodeInfo
        public int[] bounds;          // [left, top, right, bottom]
        public int[] center;          // [x, y]
        public String matchedText;    // 匹配到的文本
        public String ruleName;       // 匹配的规则名称

        public MatchResult(Object node, int[] bounds) {
            this.node = node;
            this.bounds = bounds;
            this.center = new int[]{
                (bounds[0] + bounds[2]) / 2,
                (bounds[1] + bounds[3]) / 2
            };
        }
    }

    // =========================================================================
    // 简化规则匹配 (推荐使用)
    // =========================================================================

    /**
     * 使用简化规则在 UI 树中查找匹配节点
     *
     * @param root 根节点
     * @param rules 简化规则列表
     * @return 第一个匹配的结果，或 null
     */
    public MatchResult findMatchWithSimpleRules(Object root, List<GkdBuiltinRules.SimpleRule> rules) {
        if (!initialized || root == null || rules == null || rules.isEmpty()) {
            return null;
        }

        // BFS 遍历
        List<Object> queue = new ArrayList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            Object node = queue.remove(0);
            if (node == null) continue;

            try {
                // 检查是否可见
                boolean visible = (Boolean) isVisibleToUserMethod.invoke(node);
                if (!visible) {
                    continue;
                }

                // 获取节点属性
                String text = getStringValue(getTextMethod.invoke(node));
                String desc = getStringValue(getContentDescriptionMethod.invoke(node));
                String resId = getStringValue(getViewIdResourceNameMethod.invoke(node));
                String vid = extractViewId(resId);  // 提取 view id 部分

                // 检查每个规则
                for (GkdBuiltinRules.SimpleRule rule : rules) {
                    for (GkdBuiltinRules.MatchCondition cond : rule.conditions) {
                        if (matchesCondition(text, desc, resId, vid, cond)) {
                            int[] bounds = getNodeBounds(node);
                            if (bounds != null && isValidBounds(bounds)) {
                                MatchResult result = new MatchResult(node, bounds);
                                result.matchedText = !text.isEmpty() ? text : desc;
                                result.ruleName = rule.name;
                                return result;
                            }
                        }
                    }
                }

                // 添加子节点
                int childCount = (Integer) getChildCountMethod.invoke(node);
                for (int i = 0; i < childCount; i++) {
                    Object child = getChildMethod.invoke(node, i);
                    if (child != null) {
                        queue.add(child);
                    }
                }

            } catch (Exception e) {
                // 忽略单个节点的错误
            }
        }

        return null;
    }

    /**
     * 检查节点是否匹配条件
     */
    private boolean matchesCondition(String text, String desc, String resId, String vid,
                                     GkdBuiltinRules.MatchCondition cond) {
        String value;
        switch (cond.field) {
            case TEXT:
                value = text;
                break;
            case DESC:
                value = desc;
                break;
            case ID:
                value = resId;
                break;
            case VID:
                value = vid;
                break;
            default:
                return false;
        }

        if (value == null) value = "";

        switch (cond.op) {
            case EQUALS:
                return value.equals(cond.value);
            case CONTAINS:
                return value.contains(cond.value);
            case STARTS_WITH:
                return value.startsWith(cond.value);
            case ENDS_WITH:
                return value.endsWith(cond.value);
            case REGEX:
                if (cond.pattern != null) {
                    return cond.pattern.matcher(value).find();
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * 从 resource-id 提取 view id 部分
     * 例如: "com.example:id/btn_close" -> "btn_close"
     */
    private String extractViewId(String resId) {
        if (resId == null || resId.isEmpty()) return "";
        int idx = resId.lastIndexOf("/");
        if (idx >= 0 && idx < resId.length() - 1) {
            return resId.substring(idx + 1);
        }
        return resId;
    }

    /**
     * 检查边界是否有效 (可见且大小合理)
     *
     * 严格检查:
     * 1. 按钮大小要合理 (不能太大也不能太小)
     * 2. 位置要在屏幕内
     * 3. 跳过按钮通常在右上角或右下角
     */
    private boolean isValidBounds(int[] bounds) {
        int width = bounds[2] - bounds[0];
        int height = bounds[3] - bounds[1];

        // 必须有正的宽高
        if (width <= 0 || height <= 0) return false;

        // 太小的元素可能是隐藏的或不可点击的
        if (width < 20 || height < 20) return false;

        // 太大的元素可能是容器而不是按钮 (按钮通常不超过 300x150)
        if (width > 300 || height > 150) return false;

        // 必须在屏幕内 (假设屏幕至少 320x480)
        if (bounds[0] < 0 || bounds[1] < 0) return false;

        return true;
    }

    // =========================================================================
    // GKD 选择器字符串匹配 (兼容模式)
    // =========================================================================

    /**
     * 在 UI 树中查找匹配选择器的节点
     *
     * @param root 根节点
     * @param selector GKD 选择器字符串
     * @return 匹配的节点列表
     */
    public List<MatchResult> findMatches(Object root, String selector) {
        List<MatchResult> results = new ArrayList<>();

        if (!initialized || root == null || selector == null) {
            return results;
        }

        // 解析选择器
        SelectorCondition condition = parseSelector(selector);
        if (condition == null) {
            return results;
        }

        // BFS 遍历查找
        findMatchesBFS(root, condition, results);

        return results;
    }

    /**
     * 检查节点是否匹配任意一个选择器
     */
    public MatchResult findFirstMatch(Object root, List<String> selectors) {
        for (String selector : selectors) {
            List<MatchResult> matches = findMatches(root, selector);
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
        }
        return null;
    }

    /**
     * 检查是否存在排除匹配
     */
    public boolean hasExcludeMatch(Object root, List<String> excludeSelectors) {
        if (excludeSelectors == null || excludeSelectors.isEmpty()) {
            return false;
        }

        for (String selector : excludeSelectors) {
            List<MatchResult> matches = findMatches(root, selector);
            if (!matches.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * BFS 遍历查找匹配节点
     */
    private void findMatchesBFS(Object root, SelectorCondition condition, List<MatchResult> results) {
        List<Object> queue = new ArrayList<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            Object node = queue.remove(0);
            if (node == null) continue;

            try {
                if (matchesSelectorCondition(node, condition)) {
                    int[] bounds = getNodeBounds(node);
                    if (bounds != null) {
                        MatchResult result = new MatchResult(node, bounds);
                        result.matchedText = getNodeText(node);
                        results.add(result);
                    }
                }

                int childCount = (Integer) getChildCountMethod.invoke(node);
                for (int i = 0; i < childCount; i++) {
                    Object child = getChildMethod.invoke(node, i);
                    if (child != null) {
                        queue.add(child);
                    }
                }

            } catch (Exception e) {
                // 忽略
            }
        }
    }

    /**
     * 检查节点是否匹配选择器条件
     */
    private boolean matchesSelectorCondition(Object node, SelectorCondition condition) throws Exception {
        // 检查可见性
        if (condition.visibleToUser != null) {
            boolean visible = (Boolean) isVisibleToUserMethod.invoke(node);
            if (visible != condition.visibleToUser) return false;
        }

        // 检查可点击
        if (condition.clickable != null) {
            boolean clickable = (Boolean) isClickableMethod.invoke(node);
            if (clickable != condition.clickable) return false;
        }

        // 检查文本
        if (condition.textCondition != null) {
            CharSequence text = (CharSequence) getTextMethod.invoke(node);
            String textStr = text != null ? text.toString() : "";
            if (!matchesStringCondition(textStr, condition.textCondition)) {
                return false;
            }
        }

        // 检查 content-description
        if (condition.descCondition != null) {
            CharSequence desc = (CharSequence) getContentDescriptionMethod.invoke(node);
            String descStr = desc != null ? desc.toString() : "";
            if (!matchesStringCondition(descStr, condition.descCondition)) {
                return false;
            }
        }

        // 检查 resource-id
        if (condition.idCondition != null) {
            String resId = (String) getViewIdResourceNameMethod.invoke(node);
            String idStr = resId != null ? resId : "";
            if (!matchesStringCondition(idStr, condition.idCondition)) {
                return false;
            }
        }

        // 检查类名
        if (condition.classCondition != null) {
            CharSequence className = (CharSequence) getClassNameMethod.invoke(node);
            String classStr = className != null ? className.toString() : "";
            if (!matchesStringCondition(classStr, condition.classCondition)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查字符串是否匹配条件
     */
    private boolean matchesStringCondition(String value, StringCondition condition) {
        if (value == null) value = "";

        switch (condition.type) {
            case EQUALS:
                return value.equals(condition.value);
            case CONTAINS:
                return value.contains(condition.value);
            case STARTS_WITH:
                return value.startsWith(condition.value);
            case ENDS_WITH:
                return value.endsWith(condition.value);
            case REGEX:
                try {
                    return Pattern.compile(condition.value, Pattern.CASE_INSENSITIVE)
                                  .matcher(value).find();
                } catch (Exception e) {
                    return false;
                }
            case NOT_EQUALS:
                return !value.equals(condition.value);
            default:
                return false;
        }
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private int[] getNodeBounds(Object node) {
        try {
            Object rect = rectClass.newInstance();
            getBoundsInScreenMethod.invoke(node, rect);

            int left = rectClass.getField("left").getInt(rect);
            int top = rectClass.getField("top").getInt(rect);
            int right = rectClass.getField("right").getInt(rect);
            int bottom = rectClass.getField("bottom").getInt(rect);

            return new int[]{left, top, right, bottom};
        } catch (Exception e) {
            return null;
        }
    }

    private String getNodeText(Object node) {
        try {
            CharSequence text = (CharSequence) getTextMethod.invoke(node);
            if (text != null && text.length() > 0) {
                return text.toString();
            }
            CharSequence desc = (CharSequence) getContentDescriptionMethod.invoke(node);
            if (desc != null && desc.length() > 0) {
                return desc.toString();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    private String getStringValue(Object obj) {
        if (obj == null) return "";
        if (obj instanceof CharSequence) {
            return obj.toString();
        }
        return "";
    }

    /**
     * 解析选择器字符串
     */
    private SelectorCondition parseSelector(String selector) {
        SelectorCondition condition = new SelectorCondition();

        Pattern attrPattern = Pattern.compile("\\[([^\\]]+)\\]");
        Matcher matcher = attrPattern.matcher(selector);

        while (matcher.find()) {
            String attr = matcher.group(1);
            parseAttribute(attr, condition);
        }

        return condition;
    }

    private void parseAttribute(String attr, SelectorCondition condition) {
        Pattern kvPattern = Pattern.compile("(\\w+)([~*^$!]?)=(.+)");
        Matcher matcher = kvPattern.matcher(attr);

        if (!matcher.matches()) return;

        String key = matcher.group(1);
        String op = matcher.group(2);
        String value = matcher.group(3);

        // 去除引号
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }

        StringCondition.Type type;
        switch (op) {
            case "~":
                type = StringCondition.Type.REGEX;
                break;
            case "*":
                type = StringCondition.Type.CONTAINS;
                break;
            case "^":
                type = StringCondition.Type.STARTS_WITH;
                break;
            case "$":
                type = StringCondition.Type.ENDS_WITH;
                break;
            case "!":
                type = StringCondition.Type.NOT_EQUALS;
                break;
            default:
                type = StringCondition.Type.EQUALS;
        }

        switch (key.toLowerCase()) {
            case "text":
                condition.textCondition = new StringCondition(type, value);
                break;
            case "desc":
            case "contentdesc":
            case "content-desc":
                condition.descCondition = new StringCondition(type, value);
                break;
            case "id":
            case "vid":
            case "resource-id":
                condition.idCondition = new StringCondition(type, value);
                break;
            case "name":
            case "class":
            case "classname":
                condition.classCondition = new StringCondition(type, value);
                break;
            case "clickable":
                condition.clickable = "true".equalsIgnoreCase(value);
                break;
            case "visibletouser":
                condition.visibleToUser = "true".equalsIgnoreCase(value);
                break;
        }
    }

    private static class SelectorCondition {
        StringCondition textCondition;
        StringCondition descCondition;
        StringCondition idCondition;
        StringCondition classCondition;
        Boolean clickable;
        Boolean visibleToUser;
    }

    private static class StringCondition {
        enum Type {
            EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX, NOT_EQUALS
        }

        Type type;
        String value;

        StringCondition(Type type, String value) {
            this.type = type;
            this.value = value;
        }
    }
}
