package com.lxb.server.cortex.workflow;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TaskTemplate {

    public String templateId = "";
    public String name = "";
    public String description = "";
    public String packageName = "";
    public String startPage = "";
    public String mapPath = "";
    public String userPlaybook = "";
    public boolean recordEnabled = false;
    public String taskMapMode = "off";
    public String routeId = "";
    public boolean decomposeEnabled = false;
    public String legacyKind = "";
    public String legacyId = "";
    public long createdAtMs = 0L;
    public long updatedAtMs = 0L;

    public static TaskTemplate createNew(String name, String description) {
        long now = System.currentTimeMillis();
        TaskTemplate out = new TaskTemplate();
        out.templateId = "tpl_" + UUID.randomUUID().toString();
        out.name = stringOrEmpty(name);
        out.description = stringOrEmpty(description);
        out.createdAtMs = now;
        out.updatedAtMs = now;
        return out;
    }

    public void normalizeForSave() {
        templateId = stringOrEmpty(templateId);
        if (templateId.isEmpty()) {
            templateId = "tpl_" + UUID.randomUUID().toString();
        }
        name = stringOrEmpty(name);
        description = stringOrEmpty(description);
        packageName = stringOrEmpty(packageName);
        startPage = stringOrEmpty(startPage);
        mapPath = stringOrEmpty(mapPath);
        userPlaybook = stringOrEmpty(userPlaybook);
        taskMapMode = normalizeTaskMapMode(taskMapMode);
        routeId = stringOrEmpty(routeId);
        if (routeId.isEmpty()) {
            routeId = "template:" + templateId;
        }
        legacyKind = stringOrEmpty(legacyKind);
        legacyId = stringOrEmpty(legacyId);
        long now = System.currentTimeMillis();
        if (createdAtMs <= 0L) {
            createdAtMs = now;
        }
        updatedAtMs = now;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("template_id", templateId);
        out.put("name", name);
        out.put("description", description);
        out.put("package_name", packageName);
        out.put("start_page", startPage);
        out.put("map_path", mapPath);
        out.put("user_playbook", userPlaybook);
        out.put("record_enabled", recordEnabled);
        out.put("task_map_mode", taskMapMode);
        out.put("route_id", routeId);
        out.put("decompose_enabled", decomposeEnabled);
        out.put("legacy_kind", legacyKind);
        out.put("legacy_id", legacyId);
        out.put("created_at_ms", createdAtMs);
        out.put("updated_at_ms", updatedAtMs);
        return out;
    }

    public static TaskTemplate fromMap(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        TaskTemplate out = new TaskTemplate();
        out.templateId = stringOrEmpty(row.get("template_id"));
        out.name = stringOrEmpty(row.get("name"));
        out.description = stringOrEmpty(row.get("description"));
        out.packageName = stringOrEmpty(row.get("package_name"));
        if (out.packageName.isEmpty()) {
            out.packageName = stringOrEmpty(row.get("package"));
        }
        out.startPage = stringOrEmpty(row.get("start_page"));
        out.mapPath = stringOrEmpty(row.get("map_path"));
        out.userPlaybook = stringOrEmpty(row.get("user_playbook"));
        out.recordEnabled = toBool(row.get("record_enabled"), false);
        out.taskMapMode = normalizeTaskMapMode(stringOrEmpty(row.get("task_map_mode")));
        out.routeId = stringOrEmpty(row.get("route_id"));
        out.decomposeEnabled = toBool(row.get("decompose_enabled"), false);
        out.legacyKind = stringOrEmpty(row.get("legacy_kind"));
        out.legacyId = stringOrEmpty(row.get("legacy_id"));
        out.createdAtMs = toLong(row.get("created_at_ms"), toLong(row.get("created_at"), 0L));
        out.updatedAtMs = toLong(row.get("updated_at_ms"), out.createdAtMs);
        if (out.templateId.isEmpty()) {
            return null;
        }
        return out;
    }

    public static String normalizeTaskMapMode(String mode) {
        String v = stringOrEmpty(mode).toLowerCase(Locale.ROOT);
        if ("ai".equals(v) || "manual".equals(v) || "off".equals(v)) {
            return v;
        }
        return "off";
    }

    public static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    public static long toLong(Object o, long defVal) {
        if (o == null) return defVal;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }

    public static int toInt(Object o, int defVal) {
        if (o == null) return defVal;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception ignored) {
            return defVal;
        }
    }

    public static boolean toBool(Object o, boolean defVal) {
        if (o == null) return defVal;
        if (o instanceof Boolean) return ((Boolean) o).booleanValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return defVal;
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
