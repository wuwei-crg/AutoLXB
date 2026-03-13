package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM-based planner for selecting target_page from a RouteMap.
 *
 * This is a Java port of the Python MapPromptPlanner used by RouteThenActCortex:
 * - Builds a compact JSON view of pages (page_id/name/description/features)
 * - Sends a single text prompt to an OpenAI-style chat completion API
 * - Expects JSON with {"package_name": "...", "target_page": "..."}
 * - Falls back to heuristic matching when parsing fails
 */
public class MapPromptPlanner {

    private final LlmClient llmClient;

    public MapPromptPlanner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public static class PlanResult {
        public final String packageName;
        public final String targetPage;
        public final String rawResponse;
        public final boolean usedFallback;

        public PlanResult(String packageName, String targetPage, String rawResponse, boolean usedFallback) {
            this.packageName = packageName;
            this.targetPage = targetPage;
            this.rawResponse = rawResponse;
            this.usedFallback = usedFallback;
        }
    }

    /**
     * Plan target page using LLM analysis with heuristic fallback.
     *
     * @param config   LLM configuration loaded from file
     * @param userTask Natural language description of the task
     * @param routeMap Route map for the target app
     */
    public PlanResult plan(LlmConfig config, String userTask, RouteMap routeMap) throws Exception {
        String prompt = buildPrompt(userTask, routeMap);
        String raw = null;
        String pkg = routeMap.packageName;
        String target = null;
        boolean usedFallback = false;

        try {
            // Mirror Python _build_llm_complete: use a system prompt that enforces JSON-only output.
            String systemPrompt = "You are a route planner. Output strict JSON only with keys: "
                    + "package_name, target_page.";
            raw = llmClient.chatOnce(config, systemPrompt, prompt);
            Map<String, Object> obj = extractJsonObject(raw);
            String pkgCandidate = stringOrEmpty(obj.get("package_name"));
            if (!pkgCandidate.isEmpty()) {
                pkg = pkgCandidate;
            }
            target = stringOrEmpty(obj.get("target_page"));
        } catch (Exception e) {
            // Keep raw as-is; will fall back below.
        }

        if (target == null || target.isEmpty()) {
            usedFallback = true;
            target = heuristicTargetPage(userTask, routeMap);
        }

        return new PlanResult(pkg, target, raw != null ? raw : "", usedFallback);
    }

    private static String buildPrompt(String userTask, RouteMap routeMap) {
        List<Map<String, Object>> pageRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : routeMap.pages.entrySet()) {
            String pageId = entry.getKey();
            Map<String, Object> pageMeta = entry.getValue();
            Object nameObj = pageMeta.get("name");
            Object descObj = pageMeta.get("description");
            Object featuresObj = pageMeta.get("features");

            List<Object> features = new ArrayList<>();
            if (featuresObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> src = (List<Object>) featuresObj;
                for (int i = 0; i < src.size() && i < 12; i++) {
                    features.add(src.get(i));
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page_id", pageId);
            row.put("name", nameObj != null ? nameObj : "");
            row.put("description", descObj != null ? descObj : "");
            row.put("features", features);
            pageRows.add(row);
        }

        Map<String, Object> briefMap = new LinkedHashMap<>();
        briefMap.put("package", routeMap.packageName);
        briefMap.put("pages", pageRows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are a mobile route planner.\n");
        sb.append("Given user task and route map, output JSON only:\n");
        sb.append("{\"package_name\":\"...\",\"target_page\":\"...\"}\n");
        sb.append("Rules:\n");
        sb.append("1) target_page must be one page_id from map.pages.\n");
        sb.append("2) You MUST use semantic fields (name/description/features) to match intent.\n");
        sb.append("3) If intent is ambiguous, choose the page whose semantic description is most specific.\n\n");
        sb.append("user_task:\n").append(userTask != null ? userTask : "").append("\n\n");
        sb.append("route_map:\n").append(Json.stringify(briefMap));
        return sb.toString();
    }

    private static String heuristicTargetPage(String userTask, RouteMap routeMap) {
        String task = userTask != null ? userTask.toLowerCase() : "";
        Map<String, Map<String, Object>> pages = routeMap.pages;

        // 1) direct match by page_id / alias / legacy / name
        for (Map.Entry<String, Map<String, Object>> entry : pages.entrySet()) {
            String pageId = entry.getKey();
            Map<String, Object> page = entry.getValue();

            String pageIdLower = pageId.toLowerCase();
            Object legacyObj = page.get("legacy_page_id");
            String legacy = legacyObj != null ? String.valueOf(legacyObj).toLowerCase() : "";

            List<?> aliases = null;
            Object aliasObj = page.get("target_aliases");
            if (aliasObj instanceof List) {
                aliases = (List<?>) aliasObj;
            }

            String name = "";
            Object nameObj = page.get("name");
            if (nameObj != null) {
                name = String.valueOf(nameObj).toLowerCase();
            }

            if (!task.isEmpty()) {
                if (!pageIdLower.isEmpty() && task.contains(pageIdLower)) {
                    return pageId;
                }
                if (!legacy.isEmpty() && task.contains(legacy)) {
                    return pageId;
                }
                if (aliases != null) {
                    for (Object a : aliases) {
                        String al = a != null ? String.valueOf(a).toLowerCase() : "";
                        if (!al.isEmpty() && task.contains(al)) {
                            return pageId;
                        }
                    }
                }
                if (!name.isEmpty() && task.contains(name)) {
                    return pageId;
                }
            }
        }

        // 2) fallback to inferred home page (RouteMap logic)
        String home = routeMap.inferHomePage();
        return home != null ? home : "";
    }

    private static Map<String, Object> extractJsonObject(String text) {
        String s = text != null ? text.trim() : "";
        if (s.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) Json.parseObject(s);
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
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) Json.parseObject(slice);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<String, Object>();
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
