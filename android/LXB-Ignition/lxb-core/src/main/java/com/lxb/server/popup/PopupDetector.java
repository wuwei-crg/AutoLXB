package com.lxb.server.popup;

import com.lxb.server.system.UiAutomationWrapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹窗检测引擎
 *
 * 基于 GKD 规则库检测并关闭弹窗。
 *
 * 工作模式:
 * 1. 被动模式: 通过 0x34/0x35 命令触发检测
 * 2. 主动模式: 后台守护线程自动检测 (可配置开关)
 *
 * 功能:
 * 1. 开屏广告检测与跳过
 * 2. 更新提示弹窗关闭
 * 3. 青少年模式弹窗关闭
 * 4. 通知权限弹窗关闭
 * 5. 评价提示弹窗关闭
 */
public class PopupDetector {

    private static final String TAG = "[LXB][PopupDetector]";

    // 规则组 key
    public static final int GROUP_SPLASH_AD = 0;      // 开屏广告
    public static final int GROUP_UPDATE = 1;         // 更新提示
    public static final int GROUP_TEEN_MODE = 2;      // 青少年模式

    // 检测模式
    public static final int MODE_ALL = 0xFF;          // 检测所有类型
    public static final int MODE_SPLASH_ONLY = 0x01;  // 只检测开屏广告
    public static final int MODE_UPDATE_ONLY = 0x02;  // 只检测更新提示
    public static final int MODE_TEEN_ONLY = 0x04;    // 只检测青少年模式

    private UiAutomationWrapper uiAutomation;
    private GkdRuleParser parser;
    private GkdSelectorMatcher matcher;
    private GkdRule rules;

    // 执行计数 (用于 actionMaximum 限制)
    private Map<String, Integer> actionCounts = new HashMap<>();

    // 上次检测时间
    private long lastDetectTime = 0;

    // 最小检测间隔 (ms)
    private static final long MIN_DETECT_INTERVAL = 500;

    // =========================================================================
    // 后台守护线程相关
    // =========================================================================

    private Thread watchdogThread;
    private volatile boolean watchdogRunning = false;
    private volatile boolean watchdogEnabled = true;  // 默认启用
    private volatile int watchdogInterval = 1000;     // 检测间隔 (ms)
    private volatile int watchdogMode = MODE_ALL;     // 检测模式

    // 统计信息
    private volatile int totalDetected = 0;
    private volatile int totalDismissed = 0;
    private volatile long lastDismissTime = 0;
    private volatile String lastDismissedText = "";

    /**
     * 初始化检测器
     */
    public void initialize(UiAutomationWrapper uiAutomation) {
        this.uiAutomation = uiAutomation;
        this.parser = new GkdRuleParser();
        this.matcher = new GkdSelectorMatcher();

        // 初始化选择器匹配器
        matcher.initialize();

        // 加载规则
        rules = parser.loadDefault();

        System.out.println(TAG + " Initialized with " +
            (rules != null ? rules.globalGroups.size() : 0) + " global groups");

        // 启动后台守护线程
        startWatchdog();
    }

    /**
     * 启动后台守护线程
     */
    public void startWatchdog() {
        if (watchdogThread != null && watchdogThread.isAlive()) {
            System.out.println(TAG + " Watchdog already running");
            return;
        }

        watchdogRunning = true;
        watchdogThread = new Thread(this::watchdogLoop, "PopupWatchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();

        System.out.println(TAG + " Watchdog started (interval=" + watchdogInterval + "ms)");
    }

    /**
     * 停止后台守护线程
     */
    public void stopWatchdog() {
        watchdogRunning = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            try {
                watchdogThread.join(1000);
            } catch (InterruptedException ignored) {}
            watchdogThread = null;
        }
        System.out.println(TAG + " Watchdog stopped");
    }

    /**
     * 设置守护线程启用状态
     */
    public void setWatchdogEnabled(boolean enabled) {
        this.watchdogEnabled = enabled;
        System.out.println(TAG + " Watchdog " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * 设置守护线程检测间隔
     */
    public void setWatchdogInterval(int intervalMs) {
        this.watchdogInterval = Math.max(200, intervalMs);  // 最小 200ms
        System.out.println(TAG + " Watchdog interval set to " + this.watchdogInterval + "ms");
    }

    /**
     * 设置守护线程检测模式
     */
    public void setWatchdogMode(int mode) {
        this.watchdogMode = mode;
        System.out.println(TAG + " Watchdog mode set to 0x" + String.format("%02X", mode));
    }

    /**
     * 守护线程主循环
     */
    private void watchdogLoop() {
        System.out.println(TAG + " Watchdog loop started");

        while (watchdogRunning) {
            try {
                // 检查是否启用
                if (!watchdogEnabled) {
                    Thread.sleep(watchdogInterval);
                    continue;
                }

                // 获取当前 Activity
                String packageName = "";
                String activityName = "";
                if (uiAutomation != null) {
                    String[] activity = uiAutomation.getCurrentActivity();
                    packageName = activity[0];
                    activityName = activity[1];
                }

                // 跳过系统应用和桌面
                if (shouldSkipPackage(packageName)) {
                    Thread.sleep(watchdogInterval);
                    continue;
                }

                // 执行检测
                DetectResult result = detect(packageName, activityName, watchdogMode);

                if (result.found) {
                    totalDetected++;

                    // 执行关闭动作
                    boolean success = executeAction(result);

                    if (success) {
                        totalDismissed++;
                        lastDismissTime = System.currentTimeMillis();
                        lastDismissedText = result.matchedText;

                        // 更新执行计数
                        String countKey = packageName + ":" + result.groupKey;
                        int count = actionCounts.getOrDefault(countKey, 0);
                        actionCounts.put(countKey, count + 1);

                        System.out.println(TAG + " [Watchdog] Dismissed: " + result.groupName +
                            " - \"" + result.matchedText + "\" @ " + packageName);

                        // 关闭后短暂等待，让 UI 更新
                        Thread.sleep(300);
                    }
                }

                Thread.sleep(watchdogInterval);

            } catch (InterruptedException e) {
                System.out.println(TAG + " Watchdog interrupted");
                break;
            } catch (Exception e) {
                System.err.println(TAG + " Watchdog error: " + e.getMessage());
                try {
                    Thread.sleep(watchdogInterval);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }

        System.out.println(TAG + " Watchdog loop ended");
    }

    /**
     * 判断是否应该跳过某个包名
     */
    private boolean shouldSkipPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }

        // 跳过系统应用
        if (packageName.startsWith("com.android.") ||
            packageName.equals("android") ||
            packageName.startsWith("com.google.android.") ||
            packageName.equals("com.miui.home") ||
            packageName.equals("com.huawei.android.launcher") ||
            packageName.equals("com.oppo.launcher") ||
            packageName.equals("com.vivo.launcher")) {
            return true;
        }

        return false;
    }

    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("detected=%d, dismissed=%d, lastText='%s'",
            totalDetected, totalDismissed, lastDismissedText);
    }

    /**
     * 重新加载规则
     */
    public void reloadRules() {
        rules = parser.loadDefault();
        actionCounts.clear();
        System.out.println(TAG + " Rules reloaded");
    }

    /**
     * 从指定路径加载规则
     */
    public void loadRulesFromPath(String path) {
        GkdRule newRules = parser.loadFromFile(path);
        if (newRules != null) {
            rules = newRules;
            actionCounts.clear();
            System.out.println(TAG + " Rules loaded from " + path);
        }
    }

    /**
     * 检测结果
     */
    public static class DetectResult {
        public boolean found;           // 是否检测到弹窗
        public int groupKey;            // 规则组 key
        public String groupName;        // 规则组名称
        public int ruleKey;             // 规则 key
        public int[] clickPoint;        // 点击坐标 [x, y]
        public String matchedText;      // 匹配到的文本
        public String action;           // 动作类型

        @Override
        public String toString() {
            return "DetectResult{found=" + found +
                   ", group='" + groupName + "'" +
                   ", text='" + matchedText + "'" +
                   ", point=" + (clickPoint != null ? clickPoint[0] + "," + clickPoint[1] : "null") + "}";
        }
    }

    /**
     * 检测弹窗
     *
     * @param packageName 当前应用包名
     * @param activityName 当前 Activity 名称
     * @return 检测结果
     */
    public DetectResult detect(String packageName, String activityName) {
        return detect(packageName, activityName, MODE_ALL);
    }

    /**
     * 检测弹窗 (指定模式)
     *
     * 检测顺序:
     * 1. 使用内置简化规则 (GkdBuiltinRules) - 高效且覆盖常见场景
     * 2. 使用 GKD 规则文件 (如果加载成功)
     *
     * @param packageName 当前应用包名
     * @param activityName 当前 Activity 名称
     * @param mode 检测模式
     * @return 检测结果
     */
    public DetectResult detect(String packageName, String activityName, int mode) {
        DetectResult result = new DetectResult();
        result.found = false;

        if (uiAutomation == null) {
            return result;
        }

        // 获取 UI 树根节点
        Object rootNode = uiAutomation.getRootNode();
        if (rootNode == null) {
            return result;
        }

        // =====================================================================
        // 第一优先级: 使用内置简化规则
        // =====================================================================
        List<GkdBuiltinRules.SimpleRule> builtinRules = GkdBuiltinRules.getAllRules();

        // 根据模式过滤规则
        List<GkdBuiltinRules.SimpleRule> filteredRules = new java.util.ArrayList<>();
        for (GkdBuiltinRules.SimpleRule rule : builtinRules) {
            if (shouldCheckGroup(rule.groupKey, mode)) {
                // 检查执行次数限制
                String countKey = packageName + ":" + rule.groupKey;
                int count = actionCounts.getOrDefault(countKey, 0);
                if (count < 5) {  // 每个规则组最多执行 5 次
                    filteredRules.add(rule);
                }
            }
        }

        // 使用简化规则匹配
        GkdSelectorMatcher.MatchResult match = matcher.findMatchWithSimpleRules(rootNode, filteredRules);

        if (match != null) {
            result.found = true;
            result.clickPoint = match.center;
            result.matchedText = match.matchedText;
            result.action = "click";

            // 根据匹配的规则名称确定 groupKey
            for (GkdBuiltinRules.SimpleRule rule : filteredRules) {
                if (rule.name.equals(match.ruleName)) {
                    result.groupKey = rule.groupKey;
                    result.groupName = getGroupName(rule.groupKey);
                    break;
                }
            }

            System.out.println(TAG + " [Builtin] Detected: " + result);
            return result;
        }

        // =====================================================================
        // 第二优先级: 使用 GKD 规则文件 (如果有)
        // =====================================================================
        if (rules != null && !rules.globalGroups.isEmpty()) {
            for (GkdRule.RuleGroup group : rules.globalGroups) {
                if (!shouldCheckGroup(group.key, mode)) {
                    continue;
                }

                String countKey = packageName + ":" + group.key;
                int count = actionCounts.getOrDefault(countKey, 0);
                if (count >= group.actionMaximum) {
                    continue;
                }

                for (GkdRule.Rule rule : group.rules) {
                    GkdSelectorMatcher.MatchResult gkdMatch = null;

                    if (rule.excludeMatches != null && !rule.excludeMatches.isEmpty()) {
                        if (matcher.hasExcludeMatch(rootNode, rule.excludeMatches)) {
                            continue;
                        }
                    }

                    if (rule.matches != null) {
                        List<GkdSelectorMatcher.MatchResult> matches =
                            matcher.findMatches(rootNode, rule.matches);
                        if (!matches.isEmpty()) {
                            gkdMatch = matches.get(0);
                        }
                    }

                    if (gkdMatch == null && rule.anyMatches != null) {
                        gkdMatch = matcher.findFirstMatch(rootNode, rule.anyMatches);
                    }

                    if (gkdMatch != null) {
                        result.found = true;
                        result.groupKey = group.key;
                        result.groupName = group.name;
                        result.ruleKey = rule.key;
                        result.clickPoint = gkdMatch.center;
                        result.matchedText = gkdMatch.matchedText;
                        result.action = rule.action;

                        System.out.println(TAG + " [GKD] Detected: " + result);
                        return result;
                    }
                }
            }
        }

        return result;
    }

    /**
     * 获取规则组名称
     */
    private String getGroupName(int groupKey) {
        switch (groupKey) {
            case 0: return "开屏广告";
            case 1: return "更新提示";
            case 2: return "青少年模式";
            case 3: return "通知权限";
            case 4: return "评价提示";
            case 5: return "隐私协议";
            case 6: return "广告弹窗";
            default: return "未知";
        }
    }

    /**
     * 检测并关闭弹窗
     *
     * @param packageName 当前应用包名
     * @param activityName 当前 Activity 名称
     * @return 是否成功关闭弹窗
     */
    public boolean detectAndDismiss(String packageName, String activityName) {
        return detectAndDismiss(packageName, activityName, MODE_ALL);
    }

    /**
     * 检测并关闭弹窗 (指定模式)
     *
     * @param packageName 当前应用包名
     * @param activityName 当前 Activity 名称
     * @param mode 检测模式
     * @return 是否成功关闭弹窗
     */
    public boolean detectAndDismiss(String packageName, String activityName, int mode) {
        // 检测间隔限制
        long now = System.currentTimeMillis();
        if (now - lastDetectTime < MIN_DETECT_INTERVAL) {
            return false;
        }
        lastDetectTime = now;

        DetectResult result = detect(packageName, activityName, mode);

        if (!result.found) {
            return false;
        }

        // 执行动作
        boolean success = executeAction(result);

        if (success) {
            // 更新执行计数
            String countKey = packageName + ":" + result.groupKey;
            int count = actionCounts.getOrDefault(countKey, 0);
            actionCounts.put(countKey, count + 1);

            System.out.println(TAG + " Dismissed popup: " + result.groupName +
                " (count=" + (count + 1) + ")");
        }

        return success;
    }

    /**
     * 执行动作
     */
    private boolean executeAction(DetectResult result) {
        if (uiAutomation == null || result.clickPoint == null) {
            return false;
        }

        String action = result.action != null ? result.action : "click";

        switch (action) {
            case "click":
            case "clickCenter":
                return uiAutomation.click(result.clickPoint[0], result.clickPoint[1]);

            case "back":
                return uiAutomation.pressKey(uiAutomation.getKeycodeBack());

            default:
                return uiAutomation.click(result.clickPoint[0], result.clickPoint[1]);
        }
    }

    /**
     * 检查是否应该检测指定规则组
     */
    private boolean shouldCheckGroup(int groupKey, int mode) {
        if (mode == MODE_ALL) return true;

        // 位掩码检查
        // 0x01 = 开屏广告 (group 0)
        // 0x02 = 更新提示 (group 1)
        // 0x04 = 青少年模式 (group 2)
        // 0x08 = 通知权限 (group 3)
        // 0x10 = 评价提示 (group 4)
        // 0x20 = 隐私协议 (group 5)
        // 0x40 = 广告弹窗 (group 6)

        int mask = 1 << groupKey;
        return (mode & mask) != 0;
    }

    /**
     * 重置执行计数
     */
    public void resetActionCounts() {
        actionCounts.clear();
    }

    /**
     * 重置指定应用的执行计数
     */
    public void resetActionCounts(String packageName) {
        actionCounts.entrySet().removeIf(entry -> entry.getKey().startsWith(packageName + ":"));
    }

    // =========================================================================
    // 协议处理方法 (供 CommandDispatcher 调用)
    // =========================================================================

    /**
     * 处理 DISMISS_POPUP 命令 (0x34)
     *
     * 请求格式: mode[1B]
     *   mode: 0xFF=all, 0x01=splash, 0x02=update, 0x04=teen
     *
     * 响应格式: status[1B] + found[1B] + group_key[1B] + text_len[1B] + text[UTF-8]
     *
     * @param payload 请求负载
     * @return 响应数据
     */
    public byte[] handleDismissPopup(byte[] payload) {
        int mode = MODE_ALL;
        if (payload != null && payload.length >= 1) {
            mode = payload[0] & 0xFF;
        }

        // 获取当前 Activity
        String packageName = "";
        String activityName = "";
        if (uiAutomation != null) {
            String[] activity = uiAutomation.getCurrentActivity();
            packageName = activity[0];
            activityName = activity[1];
        }

        System.out.println(TAG + " DISMISS_POPUP: pkg=" + packageName + ", mode=0x" +
            String.format("%02X", mode));

        // 检测并关闭
        DetectResult result = detect(packageName, activityName, mode);

        if (result.found) {
            // 执行关闭动作
            boolean success = executeAction(result);

            if (success) {
                String countKey = packageName + ":" + result.groupKey;
                int count = actionCounts.getOrDefault(countKey, 0);
                actionCounts.put(countKey, count + 1);
            }

            // 构建响应
            byte[] textBytes = result.matchedText != null ?
                result.matchedText.getBytes(StandardCharsets.UTF_8) : new byte[0];
            int textLen = Math.min(textBytes.length, 255);

            ByteBuffer buffer = ByteBuffer.allocate(4 + textLen);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.put((byte) (success ? 0x01 : 0x00));  // status
            buffer.put((byte) 0x01);                      // found
            buffer.put((byte) result.groupKey);           // group_key
            buffer.put((byte) textLen);                   // text_len
            buffer.put(textBytes, 0, textLen);            // text

            return buffer.array();
        }

        // 未检测到弹窗
        return new byte[]{0x01, 0x00, 0x00, 0x00};
    }

    /**
     * 处理 DETECT_POPUP 命令 (0x35) - 只检测不关闭
     *
     * 请求格式: mode[1B]
     *
     * 响应格式: status[1B] + found[1B] + group_key[1B] + x[2B] + y[2B] + text_len[1B] + text[UTF-8]
     *
     * @param payload 请求负载
     * @return 响应数据
     */
    public byte[] handleDetectPopup(byte[] payload) {
        int mode = MODE_ALL;
        if (payload != null && payload.length >= 1) {
            mode = payload[0] & 0xFF;
        }

        // 获取当前 Activity
        String packageName = "";
        String activityName = "";
        if (uiAutomation != null) {
            String[] activity = uiAutomation.getCurrentActivity();
            packageName = activity[0];
            activityName = activity[1];
        }

        System.out.println(TAG + " DETECT_POPUP: pkg=" + packageName + ", mode=0x" +
            String.format("%02X", mode));

        // 只检测
        DetectResult result = detect(packageName, activityName, mode);

        if (result.found) {
            byte[] textBytes = result.matchedText != null ?
                result.matchedText.getBytes(StandardCharsets.UTF_8) : new byte[0];
            int textLen = Math.min(textBytes.length, 255);

            ByteBuffer buffer = ByteBuffer.allocate(8 + textLen);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.put((byte) 0x01);                      // status
            buffer.put((byte) 0x01);                      // found
            buffer.put((byte) result.groupKey);           // group_key
            buffer.putShort((short) result.clickPoint[0]); // x
            buffer.putShort((short) result.clickPoint[1]); // y
            buffer.put((byte) textLen);                   // text_len
            buffer.put(textBytes, 0, textLen);            // text

            return buffer.array();
        }

        // 未检测到弹窗
        return new byte[]{0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    }

    /**
     * 处理 POPUP_WATCHDOG_CTRL 命令 (0x38) - 控制守护线程
     *
     * 请求格式: action[1B] + param[2B]
     *   action:
     *     0x00 = 获取状态
     *     0x01 = 启用守护线程
     *     0x02 = 禁用守护线程
     *     0x03 = 设置检测间隔 (param = interval_ms)
     *     0x04 = 设置检测模式 (param = mode)
     *     0x05 = 重置统计
     *
     * 响应格式: status[1B] + enabled[1B] + interval[2B] + mode[1B] +
     *           detected[2B] + dismissed[2B]
     *
     * @param payload 请求负载
     * @return 响应数据
     */
    public byte[] handleWatchdogCtrl(byte[] payload) {
        int action = 0;
        int param = 0;

        if (payload != null && payload.length >= 1) {
            action = payload[0] & 0xFF;
        }
        if (payload != null && payload.length >= 3) {
            param = ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
        }

        System.out.println(TAG + " WATCHDOG_CTRL: action=" + action + ", param=" + param);

        // 执行动作
        switch (action) {
            case 0x01:  // 启用
                setWatchdogEnabled(true);
                break;
            case 0x02:  // 禁用
                setWatchdogEnabled(false);
                break;
            case 0x03:  // 设置间隔
                setWatchdogInterval(param);
                break;
            case 0x04:  // 设置模式
                setWatchdogMode(param & 0xFF);
                break;
            case 0x05:  // 重置统计
                totalDetected = 0;
                totalDismissed = 0;
                lastDismissedText = "";
                System.out.println(TAG + " Stats reset");
                break;
            default:
                // 0x00 或其他: 只返回状态
                break;
        }

        // 构建响应
        ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 0x01);                          // status
        buffer.put((byte) (watchdogEnabled ? 0x01 : 0x00)); // enabled
        buffer.putShort((short) watchdogInterval);        // interval
        buffer.put((byte) watchdogMode);                  // mode
        buffer.putShort((short) Math.min(totalDetected, 65535));   // detected
        buffer.putShort((short) Math.min(totalDismissed, 65535));  // dismissed

        return buffer.array();
    }
}
