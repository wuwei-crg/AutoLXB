package com.lxb.server.popup;

import java.util.ArrayList;
import java.util.List;

/**
 * GKD 规则数据结构
 *
 * 对应 GKD 规则库的 JSON5 格式:
 * - globalGroups: 全局规则组 (开屏广告、更新提示、青少年模式)
 * - apps: 应用特定规则
 */
public class GkdRule {

    /**
     * 规则组
     */
    public static class RuleGroup {
        public int key;
        public String name;
        public String desc;
        public boolean enable = true;
        public int matchTime = 10000;  // 匹配超时 (ms)
        public int actionMaximum = 1;  // 最大执行次数
        public boolean fastQuery = false;
        public List<Rule> rules = new ArrayList<>();

        @Override
        public String toString() {
            return "RuleGroup{key=" + key + ", name='" + name + "', rules=" + rules.size() + "}";
        }
    }

    /**
     * 单条规则
     */
    public static class Rule {
        public int key;
        public String name;
        public String matches;              // 主匹配选择器
        public List<String> anyMatches;     // 任意匹配 (OR 关系)
        public List<String> excludeMatches; // 排除匹配
        public List<String> activityIds;    // 限定 Activity
        public String action = "click";     // 动作: click, clickCenter, back

        @Override
        public String toString() {
            return "Rule{key=" + key + ", matches='" +
                   (matches != null ? matches.substring(0, Math.min(50, matches.length())) + "..." : "null") + "'}";
        }
    }

    /**
     * 应用规则
     */
    public static class AppRule {
        public String id;  // 包名
        public boolean enable = true;
        public List<RuleGroup> groups = new ArrayList<>();

        @Override
        public String toString() {
            return "AppRule{id='" + id + "', groups=" + groups.size() + "}";
        }
    }

    // 全局规则组
    public List<RuleGroup> globalGroups = new ArrayList<>();

    // 应用特定规则 (按包名索引)
    public List<AppRule> apps = new ArrayList<>();

    /**
     * 根据包名获取应用规则
     */
    public AppRule getAppRule(String packageName) {
        for (AppRule app : apps) {
            if (app.id.equals(packageName)) {
                return app;
            }
        }
        return null;
    }

    /**
     * 获取指定类型的全局规则组
     *
     * @param key 规则组 key (0=开屏广告, 1=更新提示, 2=青少年模式)
     */
    public RuleGroup getGlobalGroup(int key) {
        for (RuleGroup group : globalGroups) {
            if (group.key == key) {
                return group;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "GkdRule{globalGroups=" + globalGroups.size() + ", apps=" + apps.size() + "}";
    }
}
