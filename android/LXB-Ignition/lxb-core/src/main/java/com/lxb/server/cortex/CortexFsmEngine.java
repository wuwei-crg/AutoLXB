package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal Java port of the Python CortexFSMEngine skeleton.
 *
 * Goal for now:
 * - Mirror the high-level FSM structure and state transitions:
 *   INIT -> APP_RESOLVE -> ROUTE_PLAN -> ROUTING -> VISION_ACT -> FINISH/FAIL
 * - Provide a Context object and run() signature compatible with Python:
 *   status/state/package_name/target_page/route_result/command_log/llm_history/lessons/reason
 * - Gradually fill internal behavior with end-side engines (LLM planner, routing, VLM actions).
 */
public class CortexFsmEngine {

    public enum State {
        INIT,
        APP_RESOLVE,
        ROUTE_PLAN,
        ROUTING,
        VISION_ACT,
        FINISH,
        FAIL
    }

    /**
     * Execution context for a single Cortex automation task.
     * Mirrors the Python CortexContext dataclass in a minimal form.
     */
    public static class Context {
        public final String taskId;
        public String userTask = "";
        public String mapPath = null;
        public String startPage = null;

        public String selectedPackage = "";
        public String targetPage = "";

        public final List<String> routeTrace = new ArrayList<>();
        public final List<Map<String, Object>> commandLog = new ArrayList<>();
        public final Map<String, Object> routeResult = new LinkedHashMap<>();

        public String error = "";
        public final Map<String, Object> output = new LinkedHashMap<>();

        public int visionTurns = 0;
        public final List<Map<String, Object>> llmHistory = new ArrayList<>();
        public final List<String> lessons = new ArrayList<>();

        // INIT-related fields
        public final Map<String, Object> deviceInfo = new LinkedHashMap<>();
        public final Map<String, Object> currentActivity = new LinkedHashMap<>();
        public final List<Map<String, Object>> appCandidates = new ArrayList<>();
        public final List<Map<String, Object>> pageCandidates = new ArrayList<>();
        public final Map<String, Object> coordProbe = new LinkedHashMap<>();

        public Context(String taskId) {
            this.taskId = taskId;
        }
    }

    private final PerceptionEngine perception;
    private final ExecutionEngine execution;
    private final TraceLogger trace;
    private final LlmClient llmClient;
    private final MapManager mapManager;
    private final MapPromptPlanner mapPlanner;

    public CortexFsmEngine(PerceptionEngine perception,
                           ExecutionEngine execution,
                           MapManager mapManager,
                           TraceLogger trace) {
        this.perception = perception;
        this.execution = execution;
        this.trace = trace;
        this.llmClient = new LlmClient();
        this.mapManager = mapManager;
        this.mapPlanner = new MapPromptPlanner(llmClient);
    }

    /**
     * Run a Cortex FSM task.
     *
     * For now this only wires state transitions; internal behaviors are stubs:
     * - INIT       -> APP_RESOLVE
     * - APP_RESOLVE: if package_name provided -> ROUTE_PLAN else FAIL
     * - ROUTE_PLAN : if target_page already set -> ROUTING else FAIL (placeholder)
     * - ROUTING    -> VISION_ACT (no real routing yet)
     * - VISION_ACT -> FINISH (no real vision actions yet)
     */
    public Map<String, Object> run(
            String userTask,
            String packageName,
            String mapPath,
            String startPage
    ) {
        Context ctx = new Context(UUID.randomUUID().toString());
        ctx.userTask = userTask != null ? userTask : "";
        ctx.selectedPackage = packageName != null ? packageName : "";
        ctx.mapPath = mapPath;
        ctx.startPage = startPage;

        State state = State.INIT;

        for (int i = 0; i < 30; i++) {
            if (state == State.INIT) {
                state = runInitState(ctx);
                continue;
            }
            if (state == State.APP_RESOLVE) {
                state = runAppResolveState(ctx);
                continue;
            }
            if (state == State.ROUTE_PLAN) {
                state = runRoutePlanState(ctx);
                continue;
            }
            if (state == State.ROUTING) {
                state = runRoutingState(ctx);
                continue;
            }
            if (state == State.VISION_ACT) {
                state = runVisionActState(ctx);
                continue;
            }
            if (state == State.FINISH || state == State.FAIL) {
                break;
            }
            // Safety: unknown state
            ctx.error = "unknown_state:" + state.name();
            state = State.FAIL;
            break;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", state == State.FINISH ? "success" : "failed");
        out.put("task_id", ctx.taskId);
        out.put("state", state.name());
        out.put("package_name", ctx.selectedPackage);
        out.put("target_page", ctx.targetPage != null ? ctx.targetPage : "");
        out.put("route_trace", new ArrayList<>(ctx.routeTrace));
        out.put("route_result", new LinkedHashMap<>(ctx.routeResult));
        out.put("command_log", new ArrayList<>(ctx.commandLog));
        out.put("llm_history", new ArrayList<>(ctx.llmHistory));
        out.put("lessons", new ArrayList<>(ctx.lessons));
        if (ctx.error != null && !ctx.error.isEmpty()) {
            out.put("reason", ctx.error);
        }
        if (!ctx.output.isEmpty()) {
            out.put("output", new LinkedHashMap<>(ctx.output));
        }
        return out;
    }

    private State runInitState(Context ctx) {
        Map<String, Object> enterEv = new LinkedHashMap<>();
        enterEv.put("task_id", ctx.taskId);
        enterEv.put("state", State.INIT.name());
        enterEv.put("user_task", ctx.userTask);
        trace.event("fsm_state_enter", enterEv);

        // 1) Screen size (width/height/density), via PerceptionEngine.GET_SCREEN_SIZE
        int width = 0, height = 0, density = 0;
        try {
            byte[] resp = perception != null ? perception.handleGetScreenSize() : null;
            if (resp != null && resp.length >= 7) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                if (status != 0) {
                    width = buf.getShort() & 0xFFFF;
                    height = buf.getShort() & 0xFFFF;
                    density = buf.getShort() & 0xFFFF;
                }
            }
        } catch (Exception ignored) {
        }
        ctx.deviceInfo.put("width", width);
        ctx.deviceInfo.put("height", height);
        ctx.deviceInfo.put("density", density);

        // 2) Current activity, via PerceptionEngine.GET_ACTIVITY
        try {
            byte[] resp = perception != null ? perception.handleGetActivity() : null;
            boolean ok = false;
            String pkg = "";
            String act = "";
            if (resp != null && resp.length >= 5) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                ok = status != 0;
                if (resp.length >= 5) {
                    int pkgLen = buf.getShort() & 0xFFFF;
                    if (pkgLen >= 0 && buf.remaining() >= pkgLen + 2) {
                        byte[] pkgBytes = new byte[pkgLen];
                        buf.get(pkgBytes);
                        pkg = new String(pkgBytes, StandardCharsets.UTF_8);
                        int actLen = buf.getShort() & 0xFFFF;
                        if (actLen >= 0 && buf.remaining() >= actLen) {
                            byte[] actBytes = new byte[actLen];
                            buf.get(actBytes);
                            act = new String(actBytes, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", ok);
            ctx.currentActivity.put("package", pkg != null ? pkg : "");
            ctx.currentActivity.put("activity", act != null ? act : "");
        } catch (Exception e) {
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", false);
            ctx.currentActivity.put("package", "");
            ctx.currentActivity.put("activity", "");
        }

        // 3) App list (user apps) as candidates, via ExecutionEngine.LIST_APPS(filter=1)
        if (ctx.appCandidates.isEmpty()) {
            try {
                byte[] payload = new byte[]{0x01}; // filter=1 (user apps)
                byte[] resp = execution != null ? execution.handleListApps(payload) : null;
                if (resp != null && resp.length >= 3) {
                    ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                    byte status = buf.get();
                    int jsonLen = buf.getShort() & 0xFFFF;
                    if (status != 0 && jsonLen > 0 && buf.remaining() >= jsonLen) {
                        byte[] jsonBytes = new byte[jsonLen];
                        buf.get(jsonBytes);
                        String json = new String(jsonBytes, StandardCharsets.UTF_8);
                        Object parsed = Json.parse(json);
                        List<Map<String, Object>> candidates = normalizeAppCandidates(parsed);
                        int limit = Math.min(200, candidates.size());
                        ctx.appCandidates.addAll(candidates.subList(0, limit));
                    }
                }
            } catch (Exception ignored) {
                // Keep appCandidates empty on error.
            }
        }

        // 4) Coordinate probe is intentionally skipped for now (no VLM in Java FSM yet).
        //    We keep coordProbe empty to stay schema-compatible with Python.

        Map<String, Object> readyEv = new LinkedHashMap<>();
        readyEv.put("task_id", ctx.taskId);
        readyEv.put("device_info", new LinkedHashMap<>(ctx.deviceInfo));
        readyEv.put("current_activity", new LinkedHashMap<>(ctx.currentActivity));
        readyEv.put("app_candidates", ctx.appCandidates.size());
        readyEv.put("page_candidates", ctx.pageCandidates.size());
        readyEv.put("coord_probe", ctx.coordProbe.isEmpty() ? null : new LinkedHashMap<>(ctx.coordProbe));
        trace.event("fsm_init_ready", readyEv);

        return State.APP_RESOLVE;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeAppCandidates(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List) {
            List<?> arr = (List<?>) raw;
            for (Object item : arr) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    String pkg = stringOrEmpty(m.get("package"));
                    if (pkg.isEmpty()) continue;
                    String name = stringOrEmpty(m.get("name"));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("package", pkg);
                    row.put("name", !name.isEmpty() ? name : pkg.substring(pkg.lastIndexOf('.') + 1));
                    out.add(row);
                    continue;
                }
                String pkg = stringOrEmpty(item);
                if (pkg.isEmpty()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("name", pkg.substring(pkg.lastIndexOf('.') + 1));
                out.add(row);
            }
        } else if (raw instanceof Map) {
            // Fallback: a single map, try to interpret as one app entry.
            Map<String, Object> m = (Map<String, Object>) raw;
            String pkg = stringOrEmpty(m.get("package"));
            if (!pkg.isEmpty()) {
                String name = stringOrEmpty(m.get("name"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("name", !name.isEmpty() ? name : pkg.substring(pkg.lastIndexOf('.') + 1));
                out.add(row);
            }
        }
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /**
     * Build LLM prompt for APP_RESOLVE, similar in spirit to Python PromptBuilder(APP_RESOLVE).
     */
    private String buildAppResolvePrompt(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : ctx.appCandidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            String pkg = stringOrEmpty(c.get("package"));
            String name = stringOrEmpty(c.get("name"));
            if (pkg.isEmpty()) continue;
            row.put("package", pkg);
            row.put("name", name);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that selects the best Android app to handle a task.\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"name\"}):\n");
        sb.append(Json.stringify(payload)).append("\n\n");
        sb.append("Output JSON only, no extra text:\n");
        sb.append("{\"package_name\":\"one_package_from_apps\"}\n");
        sb.append("Rules:\n");
        sb.append("1) package_name MUST be exactly one of the \"package\" values above.\n");
        sb.append("2) If the task clearly refers to a specific brand (e.g., Bilibili, Taobao), map it to that app.\n");
        sb.append("3) If ambiguous, choose the app that typical users would most likely use.\n");
        sb.append("4) Do NOT explain, do NOT add markdown, do NOT add comments.\n");
        return sb.toString();
    }

    /**
     * System prompt for APP_RESOLVE to mirror Python-side LLM usage:
     * - clearly separates system role from user prompt
     * - enforces JSON-only output with package_name field.
     */
    private String buildAppResolveSystemPrompt() {
        return "You are an assistant that selects the best Android app to handle a task.\n"
                + "You MUST output strict JSON only with a single field: package_name.\n"
                + "Do not output markdown or any extra commentary.";
    }

    /**
     * Extract package_name from LLM JSON response.
     */
    @SuppressWarnings("unchecked")
    private String extractPackageFromResponse(String raw) {
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

    /**
     * Best-effort extraction of a JSON object from arbitrary text.
     *
     * Mirrors the Python-side _extract_json_object helper and the Java MapPromptPlanner
     * implementation so that we can recover even when the model wraps JSON in prose
     * or markdown/code fences.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractJsonObjectFromText(String text) {
        String s = text != null ? text.trim() : "";
        if (s.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        // 1) Direct parse as an object.
        try {
            Map<String, Object> obj = Json.parseObject(s);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }

        // 2) Fallback: slice between first '{' and last '}' and parse that.
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

    /**
     * Fallback when LLM is unavailable or returns invalid package.
     * Simple heuristic: match task text against app names, else pick first candidate.
     */
    private String heuristicPickPackage(Context ctx) {
        String task = ctx.userTask != null ? ctx.userTask.toLowerCase() : "";
        if (ctx.appCandidates.isEmpty()) return "";

        // Prefer name/label substring matches.
        for (Map<String, Object> c : ctx.appCandidates) {
            String pkg = stringOrEmpty(c.get("package"));
            String name = stringOrEmpty(c.get("name")).toLowerCase();
            if (!task.isEmpty() && !name.isEmpty() && task.contains(name)) {
                return pkg;
            }
        }
        // Fallback: first candidate.
        Map<String, Object> first = ctx.appCandidates.get(0);
        return stringOrEmpty(first.get("package"));
    }

    private State runAppResolveState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.APP_RESOLVE.name());
        ev.put("selected_package", ctx.selectedPackage);
        trace.event("fsm_state_enter", ev);
        // 1) Caller-specified package: accept directly and skip LLM.
        if (ctx.selectedPackage != null && !ctx.selectedPackage.trim().isEmpty()) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("package", ctx.selectedPackage);
            done.put("source", "caller");
            trace.event("fsm_app_resolve_fixed_package", done);
            return State.ROUTE_PLAN;
        }

        // 2) Need app candidates collected in INIT.
        if (ctx.appCandidates.isEmpty()) {
            ctx.error = "app_resolve_no_candidates";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_app_resolve_no_candidates", fail);
            return State.FAIL;
        }

        // 3) Build prompt and call end-side LLM to choose package.
        String prompt = buildAppResolvePrompt(ctx);
        Map<String, Object> promptEv = new LinkedHashMap<>();
        promptEv.put("task_id", ctx.taskId);
        promptEv.put("state", State.APP_RESOLVE.name());
        promptEv.put("prompt", prompt);
        trace.event("llm_prompt_app_resolve", promptEv);

        String raw = null;
        String chosenPackage = "";
        boolean usedFallback = false;

        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            raw = llmClient.chatOnce(cfg, buildAppResolveSystemPrompt(), prompt);

            Map<String, Object> respEv = new LinkedHashMap<>();
            respEv.put("task_id", ctx.taskId);
            respEv.put("state", State.APP_RESOLVE.name());
            String snippet = raw != null && raw.length() > 800 ? raw.substring(0, 800) + "..." : raw;
            respEv.put("response", snippet != null ? snippet : "");
            trace.event("llm_response_app_resolve", respEv);

            chosenPackage = extractPackageFromResponse(raw);
        } catch (Exception e) {
            usedFallback = true;
            Map<String, Object> errEv = new LinkedHashMap<>();
            errEv.put("task_id", ctx.taskId);
            errEv.put("state", State.APP_RESOLVE.name());
            errEv.put("err", String.valueOf(e));
            trace.event("llm_error_app_resolve", errEv);
        }

        if (chosenPackage == null || chosenPackage.trim().isEmpty()) {
            usedFallback = true;
            chosenPackage = heuristicPickPackage(ctx);
        }

        if (chosenPackage == null || chosenPackage.trim().isEmpty()) {
            ctx.error = "app_resolve_failed:no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_app_resolve_failed", fail);
            return State.FAIL;
        }

        ctx.selectedPackage = chosenPackage.trim();
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", ctx.selectedPackage);
        done.put("source", usedFallback ? "fallback" : "llm");
        trace.event("fsm_app_resolve_done", done);

        return State.ROUTE_PLAN;
    }

    private State runRoutePlanState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.ROUTE_PLAN.name());
        ev.put("selected_package", ctx.selectedPackage);
        ev.put("map_path", ctx.mapPath);
        trace.event("fsm_state_enter", ev);

        // 1) Require selected package from APP_RESOLVE.
        if (ctx.selectedPackage == null || ctx.selectedPackage.trim().isEmpty()) {
            ctx.error = "route_plan_no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_failed", fail);
            return State.FAIL;
        }

        String pkg = ctx.selectedPackage.trim();
        File mapFile = mapManager.getCurrentMapFile(pkg);

        // 2) No map for this package: keep pipeline, but mark as no-map mode.
        if (!mapFile.exists() || !mapFile.isFile() || mapFile.length() == 0) {
            ctx.mapPath = null;
            Map<String, Object> noMapEv = new LinkedHashMap<>();
            noMapEv.put("task_id", ctx.taskId);
            noMapEv.put("package", pkg);
            noMapEv.put("map_path", mapFile.getAbsolutePath());
            trace.event("fsm_route_plan_no_map", noMapEv);
            // 路由阶段将按照“无路径”模式执行：启动应用后直接进入 VISION_ACT。
            return State.ROUTING;
        }

        // 3) Map exists: load RouteMap and ask LLM to choose target_page.
        RouteMap routeMap;
        try {
            routeMap = RouteMap.loadFromFile(mapFile);
        } catch (Exception e) {
            ctx.mapPath = null;
            ctx.error = "route_plan_map_load_failed:" + e.getMessage();
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", pkg);
            fail.put("map_path", mapFile.getAbsolutePath());
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_map_load_failed", fail);
            // 不中断流水线，仍然进入 ROUTING，由 ROUTING 做无 map 模式处理。
            return State.ROUTING;
        }

        ctx.mapPath = mapFile.getAbsolutePath();

        String targetPage = null;
        boolean usedFallback = false;
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            MapPromptPlanner.PlanResult plan = mapPlanner.plan(cfg, ctx.userTask, routeMap);
            if (plan.packageName != null && !plan.packageName.trim().isEmpty()) {
                ctx.selectedPackage = plan.packageName.trim();
            }
            targetPage = plan.targetPage != null ? plan.targetPage.trim() : "";
            usedFallback = plan.usedFallback;

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("package", ctx.selectedPackage);
            done.put("target_page", targetPage);
            done.put("used_fallback", usedFallback);
            trace.event("fsm_route_plan_done", done);
        } catch (Exception e) {
            ctx.error = "route_plan_llm_error:" + e.getMessage();
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", pkg);
            fail.put("map_path", mapFile.getAbsolutePath());
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_llm_error", fail);
            return State.FAIL;
        }

        if (targetPage == null || targetPage.isEmpty()) {
            ctx.error = "route_plan_failed:no_target_page";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", ctx.selectedPackage);
            fail.put("map_path", ctx.mapPath);
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_failed", fail);
            return State.FAIL;
        }

        ctx.targetPage = targetPage;
        return State.ROUTING;
    }

    private State runRoutingState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.ROUTING.name());
        ev.put("selected_package", ctx.selectedPackage);
        ev.put("target_page", ctx.targetPage);
        ev.put("map_path", ctx.mapPath);
        trace.event("fsm_state_enter", ev);

        if (ctx.selectedPackage == null || ctx.selectedPackage.trim().isEmpty()) {
            ctx.error = "routing_no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_routing_failed", fail);
            return State.FAIL;
        }

        String pkg = ctx.selectedPackage.trim();
        boolean hasMap = ctx.mapPath != null && !ctx.mapPath.trim().isEmpty();
        RouteMap routeMap = null;
        List<RouteMap.Transition> path = null;
        String fromPage = "";
        String toPage = ctx.targetPage != null ? ctx.targetPage.trim() : "";

        // 1) If mapPath is available, try to load RouteMap and find BFS path.
        if (hasMap) {
            File mapFile = new File(ctx.mapPath);
            try {
                routeMap = RouteMap.loadFromFile(mapFile);
            } catch (Exception e) {
                hasMap = false;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("task_id", ctx.taskId);
                m.put("package", pkg);
                m.put("map_path", mapFile.getAbsolutePath());
                m.put("reason", "routing_map_load_failed:" + e.getMessage());
                trace.event("fsm_routing_map_load_failed", m);
            }
        }

        if (hasMap && routeMap != null && toPage != null && !toPage.isEmpty()) {
            int maxSteps = 64;

            if (ctx.startPage != null && !ctx.startPage.trim().isEmpty()) {
                fromPage = ctx.startPage.trim();
                path = routeMap.findPath(fromPage, toPage, maxSteps);
            } else {
                fromPage = routeMap.inferHomePage();
                if (fromPage == null || fromPage.isEmpty()) {
                    ctx.error = "routing_no_home_page";
                    Map<String, Object> fail = new LinkedHashMap<>();
                    fail.put("task_id", ctx.taskId);
                    fail.put("package", pkg);
                    fail.put("reason", ctx.error);
                    trace.event("fsm_routing_failed", fail);
                    return State.FAIL;
                }
                path = routeMap.findPathFromHome(toPage, maxSteps);
            }

            if (path == null) {
                ctx.error = "routing_no_path";
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("package", pkg);
                fail.put("from_page", fromPage);
                fail.put("to_page", toPage);
                fail.put("reason", ctx.error);
                trace.event("fsm_routing_no_path", fail);
                return State.FAIL;
            }
        } else {
            hasMap = false;
        }

        // 2) Launch app once for both map and no-map modes.
        boolean launchOk = launchAppForRouting(pkg);
        Map<String, Object> launchEv = new LinkedHashMap<>();
        launchEv.put("task_id", ctx.taskId);
        launchEv.put("package", pkg);
        launchEv.put("clear_task", true);
        launchEv.put("result", launchOk ? "ok" : "fail");
        trace.event("fsm_routing_launch_app", launchEv);

        // No-map mode: nothing to tap, route_result just records launch.
        if (!hasMap || path == null || path.isEmpty()) {
            ctx.routeTrace.clear();
            ctx.routeResult.clear();
            ctx.routeResult.put("ok", launchOk);
            ctx.routeResult.put("mode", "no_map");
            ctx.routeResult.put("package", pkg);
            ctx.routeResult.put("steps", new ArrayList<Map<String, Object>>());
            if (!launchOk) {
                ctx.error = "routing_launch_failed";
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("package", pkg);
                fail.put("reason", ctx.error);
                trace.event("fsm_routing_failed", fail);
                return State.FAIL;
            }
            trace.event("fsm_routing_done", new LinkedHashMap<String, Object>() {{
                put("task_id", ctx.taskId);
                put("package", pkg);
                put("mode", "no_map");
                put("steps", 0);
            }});
            return State.VISION_ACT;
        }

        // Small settle delay before first tap.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }

        // 3) Execute route steps with locator resolution and tap.
        LocatorResolver resolver = new LocatorResolver(perception, trace);
        List<Map<String, Object>> stepSummaries = new ArrayList<>();
        boolean allOk = true;
        int index = 0;

        for (RouteMap.Transition t : path) {
            Map<String, Object> stepEv = new LinkedHashMap<>();
            stepEv.put("task_id", ctx.taskId);
            stepEv.put("package", pkg);
            stepEv.put("from_page", t.fromPage);
            stepEv.put("to_page", t.toPage);
            stepEv.put("index", index);
            stepEv.put("description", t.description);
            trace.event("fsm_routing_step_start", stepEv);

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", index);
            step.put("from", t.fromPage);
            step.put("to", t.toPage);
            step.put("description", t.description);

            String result = "ok";
            String reason = "";
            String pickedStage = "";
            List<Object> pickedBounds = null;

            try {
                Locator locator = t.action != null ? t.action.locator : null;
                if (locator == null) {
                    result = "resolve_fail";
                    reason = "missing_locator";
                    allOk = false;
                } else {
                    ResolvedNode node = resolver.resolve(locator);
                    pickedStage = node.pickedStage;
                    pickedBounds = node.bounds.toList();

                    int cx = (node.bounds.left + node.bounds.right) / 2;
                    int cy = (node.bounds.top + node.bounds.bottom) / 2;

                    ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                    tapPayload.putShort((short) cx);
                    tapPayload.putShort((short) cy);
                    byte[] resp = execution.handleTap(tapPayload.array());

                    step.put("tap_resp_len", resp != null ? resp.length : 0);
                }
            } catch (Exception e) {
                allOk = false;
                String msg = String.valueOf(e);
                if (result.startsWith("resolve")) {
                    result = "resolve_fail";
                } else {
                    result = "tap_fail";
                }
                reason = msg;
            }

            step.put("picked_stage", pickedStage);
            if (pickedBounds != null) {
                step.put("picked_bounds", pickedBounds);
            }
            step.put("result", result);
            step.put("reason", reason);

            trace.event("fsm_routing_step_end", step);
            stepSummaries.add(step);

            if (!"ok".equals(result)) {
                break;
            }
            index++;

            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
        }

        // 4) Populate context route_result and route_trace.
        ctx.routeTrace.clear();
        if (fromPage != null && !fromPage.isEmpty()) {
            ctx.routeTrace.add(fromPage);
        }
        for (RouteMap.Transition t : path) {
            if (t.toPage != null && !t.toPage.isEmpty()) {
                ctx.routeTrace.add(t.toPage);
            }
        }

        ctx.routeResult.clear();
        ctx.routeResult.put("ok", allOk);
        ctx.routeResult.put("package", pkg);
        ctx.routeResult.put("from_page", fromPage);
        ctx.routeResult.put("to_page", toPage);
        ctx.routeResult.put("steps", stepSummaries);
        if (!allOk) {
            ctx.routeResult.put("reason", "step_failed");
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", pkg);
        done.put("from_page", fromPage);
        done.put("to_page", toPage);
        done.put("ok", allOk);
        done.put("steps", stepSummaries.size());
        trace.event("fsm_routing_done", done);

        if (!allOk) {
            ctx.error = "routing_step_failed";
            return State.FAIL;
        }

        return State.VISION_ACT;
    }

    /**
     * Launch app with CLEAR_TASK flag for routing stage, mirroring CortexFacade behavior.
     */
    private boolean launchAppForRouting(String packageName) {
        try {
            byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + pkgBytes.length).order(ByteOrder.BIG_ENDIAN);
            int flags = 0x01; // CLEAR_TASK
            buf.put((byte) flags);
            buf.putShort((short) pkgBytes.length);
            buf.put(pkgBytes);
            byte[] resp = execution.handleLaunchApp(buf.array());
            return resp != null && resp.length > 0 && resp[0] == 0x01;
        } catch (Exception e) {
            return false;
        }
    }

    private State runVisionActState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.VISION_ACT.name());
        trace.event("fsm_state_enter", ev);

        // TODO: later integrate VLM-based vision_act loop; for now we just finish.
        return State.FINISH;
    }
}
