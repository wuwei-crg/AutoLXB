package com.lxb.server.cortex;

import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Cortex bootstrap facade (Milestone 1.x):
 * - burn map (gzip json) to device local filesystem
 * - resolve locator using staged matching (self -> parent_rid -> bounds_hint)
 * - tap resolved node
 * - trace pull (jsonl ring buffer)
 * - route-only execution: given target_page, infer home + BFS + tap transitions
 *
 * Full end-side Cortex planner/LLM will be added in later milestones.
 */
public class CortexFacade {

    private static final String TAG = "[LXB][Cortex]";

    private final ExecutionEngine executionEngine;
    private final PerceptionEngine perceptionEngine;

    private final MapManager mapManager;
    private final TraceLogger trace;
    private final LocatorResolver locatorResolver;
    private final LlmClient llmClient;
    private final CortexFsmEngine fsmEngine;

    public CortexFacade(PerceptionEngine perceptionEngine, ExecutionEngine executionEngine) {
        this.perceptionEngine = perceptionEngine;
        this.executionEngine = executionEngine;
        this.mapManager = new MapManager();
        this.trace = new TraceLogger(300);
        this.locatorResolver = new LocatorResolver(perceptionEngine, trace);
        this.llmClient = new LlmClient();
        this.fsmEngine = new CortexFsmEngine(perceptionEngine, executionEngine, mapManager, trace);
    }

    public byte[] handleMapSetGz(byte[] payload) {
        // payload: package_len[2B] + package[UTF-8] + gzipped_map_json[...]
        try {
            if (payload.length < 2) {
                return err("payload too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            int pkgLen = buf.getShort() & 0xFFFF;
            if (pkgLen <= 0 || pkgLen > 256 || payload.length < 2 + pkgLen + 8) {
                return err("invalid package_len=" + pkgLen);
            }
            byte[] pkgBytes = new byte[pkgLen];
            buf.get(pkgBytes);
            String pkg = new String(pkgBytes, StandardCharsets.UTF_8).trim();
            if (pkg.isEmpty()) {
                return err("empty package");
            }

            byte[] gz = new byte[buf.remaining()];
            buf.get(gz);
            String json = gunzipToString(gz);
            mapManager.setCurrentMap(pkg, json);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", pkg);
            ev.put("bytes_gz", gz.length);
            ev.put("bytes_json", json.getBytes(StandardCharsets.UTF_8).length);
            trace.event("map_set", ev);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("package", pkg);
            out.put("path", mapManager.getCurrentMapFile(pkg).getAbsolutePath());
            out.put("size", json.length());
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("map_set_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleMapGetInfo(byte[] payload) {
        // payload: package[UTF-8]
        try {
            String pkg = new String(payload, StandardCharsets.UTF_8).trim();
            if (pkg.isEmpty()) return err("empty package");
            File f = mapManager.getCurrentMapFile(pkg);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", f.exists());
            out.put("package", pkg);
            out.put("path", f.getAbsolutePath());
            out.put("exists", f.exists());
            out.put("bytes", f.exists() ? f.length() : 0);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            return err(String.valueOf(e));
        }
    }

    public byte[] handleResolveLocator(byte[] payload) {
        // payload: locator json (UTF-8)
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> obj = Json.parseObject(s);
            Locator locator = Locator.fromMap(obj);

            ResolvedNode node = locatorResolver.resolve(locator);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("bounds", node.bounds.toList());
            out.put("candidates", node.candidateCount);
            out.put("picked_stage", node.pickedStage);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("resolve_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleTapLocator(byte[] payload) {
        // payload: locator json (UTF-8)
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> obj = Json.parseObject(s);
            Locator locator = Locator.fromMap(obj);

            ResolvedNode node = locatorResolver.resolve(locator);
            int cx = (node.bounds.left + node.bounds.right) / 2;
            int cy = (node.bounds.top + node.bounds.bottom) / 2;

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("x", cx);
            ev.put("y", cy);
            ev.put("stage", node.pickedStage);
            ev.put("candidates", node.candidateCount);
            trace.event("tap", ev);

            // Reuse existing tap handler (binary protocol expects >HH).
            ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            tapPayload.putShort((short) cx);
            tapPayload.putShort((short) cy);
            byte[] resp = executionEngine.handleTap(tapPayload.array());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("tap_resp_len", resp != null ? resp.length : 0);
            out.put("bounds", node.bounds.toList());
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("tap_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleTracePull(byte[] payload) {
        // payload: max_lines[2B] (optional, default 200)
        int max = 200;
        if (payload != null && payload.length >= 2) {
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            max = Math.max(1, Math.min(1000, buf.getShort() & 0xFFFF));
        }
        String data = trace.dumpLastLines(max);
        return ok(data);
    }

    /**
     * High-level Cortex entry: use end-side LLM to plan target_page from RouteMap,
     * then execute routing to that page. This is the first step of migrating the
     * Python Route-Then-Act / Cortex FSM to Java.
     *
     * Payload JSON UTF-8:
     * {
     *   "package": "tv.danmaku.bili",
     *   "user_task": "open search page",
     *   "start_page": "bilibili_home",   // optional override of inferred home
     *   "max_steps": 16                  // optional route upper bound
     * }
     *
     * Response JSON:
     * {
     *   "ok": true/false,
     *   "package": "...",
     *   "target_page": "...",
     *   "route": { ... same as handleRouteRun(...) result ... },
     *   "llm_used_fallback": true/false,
     *   "reason": "error message if any"
     * }
     */
    public byte[] handleCortexMapPlanRoute(byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> req = Json.parseObject(s);
            String pkg = stringOrEmpty(req.get("package"));
            String userTask = stringOrEmpty(req.get("user_task"));
            String startPageOverride = stringOrEmpty(req.get("start_page"));
            int maxSteps = toInt(req.get("max_steps"), 0);

            if (pkg.isEmpty()) return err("package is required");
            if (userTask.isEmpty()) return err("user_task is required");

            File mapFile = mapManager.getCurrentMapFile(pkg);
            if (!mapFile.exists()) {
                return err("map file not found for package=" + pkg + ", path=" + mapFile.getAbsolutePath());
            }
            RouteMap routeMap = RouteMap.loadFromFile(mapFile);
            if (routeMap.pages.isEmpty()) {
                return err("route map has no pages");
            }
            if (routeMap.transitions.isEmpty()) {
                return err("route map has no transitions");
            }
            if (maxSteps <= 0) {
                maxSteps = 64;
            }

            // Trace: task begin
            Map<String, Object> beginEv = new LinkedHashMap<>();
            beginEv.put("package", pkg);
            beginEv.put("user_task", userTask);
            trace.event("cortex_begin", beginEv);

            // Load LLM config and run MapPromptPlanner
            LlmConfig llmConfig = LlmConfig.loadDefault();
            MapPromptPlanner planner = new MapPromptPlanner(llmClient);
            MapPromptPlanner.PlanResult planResult = planner.plan(llmConfig, userTask, routeMap);

            String targetPage = planResult.targetPage != null ? planResult.targetPage.trim() : "";
            if (targetPage.isEmpty()) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("package", pkg);
                ev.put("reason", "empty_target_page_after_planning");
                trace.event("cortex_plan_fail", ev);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("package", pkg);
                out.put("target_page", "");
                out.put("reason", "empty_target_page_after_planning");
                return ok(Json.stringify(out));
            }

            Map<String, Object> planEv = new LinkedHashMap<>();
            planEv.put("package", pkg);
            planEv.put("target_page", targetPage);
            planEv.put("used_fallback", planResult.usedFallback);
            trace.event("cortex_plan_done", planEv);

            // Compute route path from map (same semantics as handleRouteRun target_page branch).
            String effectiveFrom;
            java.util.List<RouteMap.Transition> path;
            if (!startPageOverride.isEmpty()) {
                effectiveFrom = startPageOverride;
                path = routeMap.findPath(effectiveFrom, targetPage, maxSteps);
            } else {
                effectiveFrom = routeMap.inferHomePage();
                if (effectiveFrom == null || effectiveFrom.isEmpty()) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", false);
                    out.put("package", pkg);
                    out.put("target_page", targetPage);
                    out.put("reason", "cannot_infer_home_page");
                    return ok(Json.stringify(out));
                }
                path = routeMap.findPathFromHome(targetPage, maxSteps);
            }

            if (path == null) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("package", pkg);
                ev.put("from_page", effectiveFrom);
                ev.put("to_page", targetPage);
                trace.event("cortex_route_no_path", ev);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("package", pkg);
                out.put("target_page", targetPage);
                out.put("reason", "no_path");
                return ok(Json.stringify(out));
            }

            Map<String, Object> routeResult = executeRoute(pkg, effectiveFrom, targetPage, path);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", routeResult.get("ok"));
            out.put("package", pkg);
            out.put("target_page", targetPage);
            out.put("route", routeResult);
            out.put("llm_used_fallback", planResult.usedFallback);
            if (Boolean.FALSE.equals(routeResult.get("ok"))) {
                Object reason = routeResult.get("reason");
                if (reason != null) {
                    out.put("reason", String.valueOf(reason));
                }
            }

            trace.event("cortex_end", out);

            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * High-level Cortex FSM entry point.
     *
     * Payload JSON UTF-8:
     * {
     *   "user_task": "open xxx",
     *   "package": "tv.danmaku.bili",    // optional, if omitted INIT+APP_RESOLVE will be extended later
     *   "map_path": "/data/local/tmp/nav_map.json", // optional, reserved for future
     *   "start_page": "bilibili_home"    // optional
     * }
     *
     * For now this wires only INIT -> APP_RESOLVE, and requires package to be provided.
     */
    public byte[] handleCortexFsmRun(byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> req = Json.parseObject(s);
            String userTask = stringOrEmpty(req.get("user_task"));
            String pkg = stringOrEmpty(req.get("package"));
            String mapPath = stringOrEmpty(req.get("map_path"));
            String startPage = stringOrEmpty(req.get("start_page"));

            if (userTask.isEmpty()) {
                return err("user_task is required");
            }

            Map<String, Object> result = fsmEngine.run(
                    userTask,
                    pkg,
                    mapPath.isEmpty() ? null : mapPath,
                    startPage.isEmpty() ? null : startPage
            );
            return ok(Json.stringify(result));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("cortex_fsm_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Route-only 闁圭瑳鍡╂斀闁挎稒鑹鹃悢鈧ù?map transitions闁挎稑濂旂划?home闁挎稑鐗婇崹銊╁及閹呯 start_page闁挎稑顦抽惌楣冩偨閸楃偛鐓?target_page闁?     *
     * payload: JSON UTF-8:
     * {
     *   "package": "tv.danmaku.bili",
     *   "target_page": "search_page__n_xxx",   // 闁烩晩鍠楅悥锝嗐亜绾板绀勯煫鍥ф噹閿濈偤鏁?     *   "start_page": "bilibili_home",         // 闁告瑯鍨堕埀顒€顧€缁辨繈鎮惧▎鎴旀晞闁哄啫澧庨顒佺瑹瑜庣€?map 闁规亽鍔嶉弻?home
     *   "max_steps": 16                        // 闁告瑯鍨堕埀顒€顧€缁? 闁瑰瓨鐗滃閬嶆儑娴ｅ鈧啰绮堟潪鏉库枏闁活潿鍔戠划顖滄媼閵堝嫮鐟愰梻?     * }
     *
     * 闁稿繒鍘ч鎰板籍瑜忔晶妤冩嫬閸愵厾妲搁柨娑欒壘椤┭囧几濠婂嫭寮撻柟缁樺姃缁?target_page 濞达絽妫欏﹢?from_page/to_page闁挎稑鑻崹顖滄導閻楀牊鈻旂€?from->to 閻犱警鍨抽弫閬嶅Υ?     *
     * 閺夆晜鏌ㄥú?JSON:
     * {
     *   "ok": true/false,
     *   "package": "...",
     *   "from_page": "...",
     *   "to_page": "...",
     *   "steps": [ { index, from, to, description, picked_stage, picked_bounds, result, reason }, ... ],
     *   "reason": "step_failed|no_path" // 濞寸姴鎳庨妵鎴犳嫻閵夛附顦ч悗娑櫭﹢?     * }
     */
        public byte[] handleRouteRun(byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> req = Json.parseObject(s);
            String pkg = stringOrEmpty(req.get("package"));
            String targetPage = stringOrEmpty(req.get("target_page"));
            String startPageOverride = stringOrEmpty(req.get("start_page"));
            // Legacy compatibility: allow from_page/to_page when target_page is empty
            String fromPageLegacy = stringOrEmpty(req.get("from_page"));
            String toPageLegacy = stringOrEmpty(req.get("to_page"));
            int maxSteps = toInt(req.get("max_steps"), 0);

            if (pkg.isEmpty()) return err("package is required");
            boolean useLegacyFromTo = targetPage.isEmpty() && !fromPageLegacy.isEmpty() && !toPageLegacy.isEmpty();
            if (!useLegacyFromTo && targetPage.isEmpty()) {
                return err("target_page is required");
            }

            File mapFile = mapManager.getCurrentMapFile(pkg);
            if (!mapFile.exists()) {
                return err("map file not found for package=" + pkg + ", path=" + mapFile.getAbsolutePath());
            }

            RouteMap routeMap = RouteMap.loadFromFile(mapFile);
            if (routeMap.transitions.isEmpty()) {
                return err("route map has no transitions");
            }

            if (maxSteps <= 0) {
                maxSteps = 64; // default upper bound to avoid pathological long paths
            }

            String effectiveFrom;
            String effectiveTo;
            java.util.List<RouteMap.Transition> path;

            if (useLegacyFromTo) {
                effectiveFrom = fromPageLegacy;
                effectiveTo = toPageLegacy;
                trace.event("route_begin", buildRouteEvent(pkg, effectiveFrom, effectiveTo, "begin"));
                path = routeMap.findPath(effectiveFrom, effectiveTo, maxSteps);
            } else {
                // New semantics: only target_page is required; start_page is optional override of inferred home.
                String start = !startPageOverride.isEmpty() ? startPageOverride : routeMap.inferHomePage();
                if (start == null || start.isEmpty()) {
                    return err("cannot infer home page from map");
                }
                effectiveFrom = start;
                effectiveTo = targetPage;
                trace.event("route_begin", buildRouteEvent(pkg, effectiveFrom, effectiveTo, "begin"));
                path = routeMap.findPathFromHome(effectiveTo, maxSteps);
            }

            if (path == null) {
                Map<String, Object> ev = buildRouteEvent(pkg, effectiveFrom, effectiveTo, "no_path");
                trace.event("route_no_path", ev);
                Map<String, Object> outFail = new LinkedHashMap<>();
                outFail.put("ok", false);
                outFail.put("package", pkg);
                outFail.put("from_page", effectiveFrom);
                outFail.put("to_page", effectiveTo);
                outFail.put("steps", new java.util.ArrayList<>());
                outFail.put("reason", "no_path");
                return ok(Json.stringify(outFail));
            }

            Map<String, Object> out = executeRoute(pkg, effectiveFrom, effectiveTo, path);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("route_err", ev);
            return err(String.valueOf(e));
        }
    }

    /**
     * Run full route execution for the given path and package.
     * Shared by handleRouteRun and higher-level FSM/LLM entry points to keep behavior aligned.
     */
    private Map<String, Object> executeRoute(
            String pkg,
            String effectiveFrom,
            String effectiveTo,
            java.util.List<RouteMap.Transition> path
    ) {
        // Before executing route steps, launch app with CLEAR_TASK, aligned with Python RouteThenActCortex.
        boolean launchOk = launchAppForRoute(pkg);
        Map<String, Object> launchEv = new LinkedHashMap<>();
        launchEv.put("package", pkg);
        launchEv.put("clear_task", true);
        launchEv.put("result", launchOk ? "ok" : "fail");
        trace.event("route_launch_app", launchEv);
        // Python side sleeps ~1.5s after launch_app; do the same to avoid resolving on a half-loaded UI.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }

        java.util.List<Map<String, Object>> stepSummaries = new java.util.ArrayList<>();
        int index = 0;
        boolean allOk = true;

        for (RouteMap.Transition t : path) {
            Map<String, Object> stepEv = new LinkedHashMap<>();
            stepEv.put("package", pkg);
            stepEv.put("from_page", t.fromPage);
            stepEv.put("to_page", t.toPage);
            stepEv.put("index", index);
            stepEv.put("description", t.description);
            trace.event("route_step_start", stepEv);

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", index);
            step.put("from", t.fromPage);
            step.put("to", t.toPage);
            step.put("description", t.description);

            String result = "ok";
            String reason = "";
            String pickedStage = "";
            java.util.List<Object> pickedBounds = null;

            try {
                Locator locator = t.action != null ? t.action.locator : null;
                if (locator == null) {
                    result = "resolve_fail";
                    reason = "missing_locator";
                    allOk = false;
                } else {
                    // For robustness, mirror Python RouteThenActCortex: retry when there are temporarily no candidates,
                    // but still fail-fast on ambiguous candidates to keep strict semantics.
                    ResolvedNode node = resolveWithRetry(locator);
                    pickedStage = node.pickedStage;
                    pickedBounds = node.bounds.toList();

                    int cx = (node.bounds.left + node.bounds.right) / 2;
                    int cy = (node.bounds.top + node.bounds.bottom) / 2;

                    ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                    tapPayload.putShort((short) cx);
                    tapPayload.putShort((short) cy);
                    byte[] resp = executionEngine.handleTap(tapPayload.array());

                    step.put("tap_resp_len", resp != null ? resp.length : 0);
                }
            } catch (Exception e) {
                allOk = false;
                String msg = String.valueOf(e);
                result = result.startsWith("resolve") ? result : "tap_fail";
                reason = msg;
            }

            step.put("picked_stage", pickedStage);
            if (pickedBounds != null) {
                step.put("picked_bounds", pickedBounds);
            }
            step.put("result", result);
            step.put("reason", reason);

            trace.event("route_step_end", step);

            stepSummaries.add(step);
            if (!"ok".equals(result)) {
                break;
            }
            index++;

            // Give UI some time to finish transitions to the next page before tapping again.
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", allOk);
        out.put("package", pkg);
        out.put("from_page", effectiveFrom);
        out.put("to_page", effectiveTo);
        out.put("steps", stepSummaries);
        if (!allOk) {
            out.put("reason", "step_failed");
        }

        trace.event("route_end", buildRouteEvent(pkg, effectiveFrom, effectiveTo, allOk ? "ok" : "failed"));

        return out;
    }

    /**
     * Route-only helper: resolve locator with small retry window for "no candidates" cases
     * to tolerate UI loading races, while keeping strict behavior for ambiguous matches.
     */
    private ResolvedNode resolveWithRetry(Locator locator) throws Exception {
        final int maxAttempts = 3;
        final long intervalMs = 300L;
        Exception last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResolvedNode node = locatorResolver.resolve(locator);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("attempt", attempt);
                ev.put("result", "ok");
                trace.event("route_resolve_locator", ev);
                return node;
            } catch (IllegalStateException e) {
                last = e;
                String msg = String.valueOf(e.getMessage());
                boolean isNoCandidates = msg != null && msg.contains("no candidates");

                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("attempt", attempt);
                ev.put("err", msg);
                trace.event("route_resolve_retry", ev);

                // For ambiguous candidates, do not retry to avoid random taps.
                if (!isNoCandidates || attempt == maxAttempts) {
                    throw e;
                }

                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException ignored) {
                }
            }
        }

        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("locator resolve failed");
    }

    private static String gunzipToString(byte[] gz) throws Exception {
        GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = gis.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        gis.close();
        return baos.toString("UTF-8");
    }

    private static byte[] ok(String s) {
        // response is plain UTF-8 (JSON or JSONL)
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] err(String msg) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", false);
        o.put("err", msg);
        return Json.stringify(o).getBytes(StandardCharsets.UTF_8);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int toInt(Object o, int defaultValue) {
        if (o == null) return defaultValue;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static Map<String, Object> buildRouteEvent(String pkg, String from, String to, String status) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("package", pkg);
        ev.put("from_page", from);
        ev.put("to_page", to);
        ev.put("status", status);
        return ev;
    }

    /**
     * 启动应用用于路由，相当于 Python 端 RouteThenActCortex._execute_route 中的 client.launch_app(..., clear_task=True)。
     */
    private boolean launchAppForRoute(String packageName) {
        try {
            byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + pkgBytes.length).order(ByteOrder.BIG_ENDIAN);
            int flags = 0x01; // CLEAR_TASK
            buf.put((byte) flags);
            buf.putShort((short) pkgBytes.length);
            buf.put(pkgBytes);
            byte[] resp = executionEngine.handleLaunchApp(buf.array());
            return resp != null && resp.length > 0 && resp[0] == 0x01;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            ev.put("package", packageName);
            trace.event("route_launch_err", ev);
            return false;
        }
    }
}
