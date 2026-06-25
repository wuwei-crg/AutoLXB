package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End-side LLM configuration loaded from a simple JSON file.
 *
 * File format (UTF-8 JSON), written by the APK side via Shizuku:
 * {
 *   "api_base_url": "https://api.openai.com/v1/chat/completions",
 *   "api_key": "sk-...",
 *   "model": "gpt-4o-mini",
 *   "request_type": "openai_chat_completions",
 *   "providers": [
 *     {
 *       "provider_id": "uuid",
 *       "name": "default",
 *       "api_base_url": "https://api.openai.com/v1",
 *       "api_key": "sk-...",
 *       "model": "gpt-4o-mini",
 *       "request_type": "openai_chat_completions"
 *     }
 *   ],
 *   "model_routing": {
 *     "mode": "unified",
 *     "script_action": {
 *       "unified_provider_id": "uuid",
 *       "semantic_locator_provider_id": "uuid",
 *       "vision_act_provider_id": "uuid"
 *     }
 *   },
 *   "auto_unlock_before_route": true,
 *   "auto_lock_after_task": true,
 *   "unlock_pin": "1234",
 *   "use_map": true,
 *   "map_source": "stable",
 *   "task_dnd_mode": "none",
 *   "max_task_steps": 100
 * }
 */
public class LlmConfig {

    public static final String DEFAULT_CONFIG_PATH = "/data/local/tmp/lxb-llm-config.json";
    public static final int DEFAULT_MAX_TASK_STEPS = 100;
    public static final String REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS = "openai_chat_completions";
    public static final String REQUEST_TYPE_GEMINI_GENERATE_CONTENT = "gemini_generate_content";
    public static final String REQUEST_TYPE_ANTHROPIC_MESSAGES = "anthropic_messages";
    public static final String ROUTING_MODE_UNIFIED = "unified";
    public static final String ROUTING_MODE_SPLIT = "split";
    public static final String ROUTE_DEFAULT = "default";
    public static final String ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR = "script_action.semantic_locator";
    public static final String ROUTE_SCRIPT_ACTION_VISION_ACT = "script_action.vision_act";

    public final String providerId;
    public final String providerName;
    public final String apiBaseUrl;
    public final String apiKey;
    public final String model;
    public final String requestType;
    public final boolean autoUnlockBeforeRoute;
    public final boolean autoLockAfterTask;
    public final String unlockPin;
    public final boolean useMap;
    public final String mapSource;
    public final String taskDndMode;
    /**
     * Maximum FSM/vision steps per sub task. A value <= 0 means unlimited.
     */
    public final int maxTaskSteps;

    public LlmConfig(String apiBaseUrl, String apiKey, String model) {
        this(
                apiBaseUrl,
                apiKey,
                model,
                REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS,
                true,
                true,
                "",
                true,
                "stable",
                "none",
                DEFAULT_MAX_TASK_STEPS
        );
    }

    public LlmConfig(String apiBaseUrl, String apiKey, String model, String requestType) {
        this(apiBaseUrl, apiKey, model, requestType, true, true, "", true, "stable", "none", DEFAULT_MAX_TASK_STEPS);
    }

    public LlmConfig(
            String apiBaseUrl,
            String apiKey,
            String model,
            boolean autoUnlockBeforeRoute,
            boolean autoLockAfterTask,
            String unlockPin,
            boolean useMap,
            String mapSource,
            String taskDndMode
    ) {
        this(
                apiBaseUrl,
                apiKey,
                model,
                REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS,
                autoUnlockBeforeRoute,
                autoLockAfterTask,
                unlockPin,
                useMap,
                mapSource,
                taskDndMode,
                DEFAULT_MAX_TASK_STEPS
        );
    }

    public LlmConfig(
            String apiBaseUrl,
            String apiKey,
            String model,
            boolean autoUnlockBeforeRoute,
            boolean autoLockAfterTask,
            String unlockPin,
            boolean useMap,
            String mapSource,
            String taskDndMode,
            int maxTaskSteps
    ) {
        this(
                apiBaseUrl,
                apiKey,
                model,
                REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS,
                autoUnlockBeforeRoute,
                autoLockAfterTask,
                unlockPin,
                useMap,
                mapSource,
                taskDndMode,
                maxTaskSteps
        );
    }

    public LlmConfig(
            String apiBaseUrl,
            String apiKey,
            String model,
            String requestType,
            boolean autoUnlockBeforeRoute,
            boolean autoLockAfterTask,
            String unlockPin,
            boolean useMap,
            String mapSource,
            String taskDndMode
    ) {
        this(
                apiBaseUrl,
                apiKey,
                model,
                requestType,
                autoUnlockBeforeRoute,
                autoLockAfterTask,
                unlockPin,
                useMap,
                mapSource,
                taskDndMode,
                DEFAULT_MAX_TASK_STEPS
        );
    }

    public LlmConfig(
            String apiBaseUrl,
            String apiKey,
            String model,
            String requestType,
            boolean autoUnlockBeforeRoute,
            boolean autoLockAfterTask,
            String unlockPin,
            boolean useMap,
            String mapSource,
            String taskDndMode,
            int maxTaskSteps
    ) {
        this("", "", apiBaseUrl, apiKey, model, requestType, autoUnlockBeforeRoute, autoLockAfterTask, unlockPin, useMap, mapSource, taskDndMode, maxTaskSteps);
    }

    private LlmConfig(
            String providerId,
            String providerName,
            String apiBaseUrl,
            String apiKey,
            String model,
            String requestType,
            boolean autoUnlockBeforeRoute,
            boolean autoLockAfterTask,
            String unlockPin,
            boolean useMap,
            String mapSource,
            String taskDndMode,
            int maxTaskSteps
    ) {
        this.providerId = providerId != null ? providerId.trim() : "";
        this.providerName = providerName != null ? providerName.trim() : "";
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.requestType = normalizeRequestType(requestType);
        this.autoUnlockBeforeRoute = autoUnlockBeforeRoute;
        this.autoLockAfterTask = autoLockAfterTask;
        this.unlockPin = unlockPin != null ? unlockPin : "";
        this.useMap = useMap;
        this.mapSource = normalizeMapSource(mapSource);
        this.taskDndMode = normalizeTaskDndMode(taskDndMode);
        this.maxTaskSteps = normalizeMaxTaskSteps(maxTaskSteps);
    }

    public static LlmConfig loadDefault() throws Exception {
        return loadDefaultForRoute(ROUTE_DEFAULT);
    }

    public static LlmConfig loadDefaultForRoute(String route) throws Exception {
        String override = System.getProperty("lxb.llm.config.path");
        String path = (override != null && !override.trim().isEmpty())
                ? override.trim()
                : DEFAULT_CONFIG_PATH;
        return loadFromFileForRoute(path, route);
    }

    public static LlmConfig loadFromFile(String path) throws Exception {
        return loadFromFileForRoute(path, ROUTE_DEFAULT);
    }

    public static LlmConfig loadFromFileForRoute(String path, String route) throws Exception {
        File f = new File(path);
        if (!f.exists()) {
            throw new IllegalStateException("LLM config file not found: " + path);
        }

        byte[] data = readAllBytes(f);
        String s = new String(data, StandardCharsets.UTF_8);
        Map<String, Object> obj = Json.parseObject(s);
        return fromRoot(obj, normalizeRoute(route));
    }

    private static LlmConfig fromRoot(Map<String, Object> obj, String route) {
        String baseUrl = stringOrEmpty(obj.get("api_base_url"));
        String key = stringOrEmpty(obj.get("api_key"));
        String model = stringOrEmpty(obj.get("model"));
        String requestType = normalizeRequestType(stringOrEmpty(obj.get("request_type")));
        boolean autoUnlockBeforeRoute = parseBool(obj.get("auto_unlock_before_route"), true);
        boolean autoLockAfterTask = parseBool(obj.get("auto_lock_after_task"), true);
        String unlockPin = stringOrEmpty(obj.get("unlock_pin"));
        boolean useMap = parseBool(obj.get("use_map"), true);
        String mapSource = normalizeMapSource(stringOrEmpty(obj.get("map_source")));
        String taskDndMode = normalizeTaskDndMode(stringOrEmpty(obj.get("task_dnd_mode")));
        int maxTaskSteps = parseMaxTaskSteps(obj.get("max_task_steps"), DEFAULT_MAX_TASK_STEPS);

        Map<String, ProviderConfig> providers = parseProviders(obj);
        String providerId = resolveProviderId(obj, route);
        if (providerId.isEmpty() && isMissingRequiredSplitRoute(obj, route)) {
            throw new IllegalStateException("LLM route provider not configured: " + route);
        }
        if (!providerId.isEmpty()) {
            ProviderConfig provider = providers.get(providerId);
            if (provider == null) {
                throw new IllegalStateException("LLM provider not found for route " + route + ": " + providerId);
            }
            if (provider.apiBaseUrl.isEmpty() || provider.model.isEmpty()) {
                throw new IllegalStateException("LLM provider missing api_base_url or model: " + providerId);
            }
            return new LlmConfig(
                    provider.providerId,
                    provider.name,
                    provider.apiBaseUrl,
                    provider.apiKey,
                    provider.model,
                    provider.requestType,
                    autoUnlockBeforeRoute,
                    autoLockAfterTask,
                    unlockPin,
                    useMap,
                    mapSource,
                    taskDndMode,
                    maxTaskSteps
            );
        }

        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw new IllegalStateException("LLM config missing api_base_url or model");
        }

        return new LlmConfig(
                baseUrl,
                key,
                model,
                requestType,
                autoUnlockBeforeRoute,
                autoLockAfterTask,
                unlockPin,
                useMap,
                mapSource,
                taskDndMode,
                maxTaskSteps
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ProviderConfig> parseProviders(Map<String, Object> root) {
        Map<String, ProviderConfig> out = new LinkedHashMap<String, ProviderConfig>();
        Object providersObj = root.get("providers");
        if (providersObj instanceof List) {
            List<Object> providers = (List<Object>) providersObj;
            for (Object item : providers) {
                if (!(item instanceof Map)) {
                    continue;
                }
                ProviderConfig provider = providerFromMap((Map<String, Object>) item);
                if (!provider.providerId.isEmpty()) {
                    out.put(provider.providerId, provider);
                }
            }
            return out;
        }
        if (providersObj instanceof Map) {
            Map<String, Object> providers = (Map<String, Object>) providersObj;
            for (Map.Entry<String, Object> entry : providers.entrySet()) {
                if (!(entry.getValue() instanceof Map)) {
                    continue;
                }
                ProviderConfig provider = providerFromMap((Map<String, Object>) entry.getValue());
                if (provider.providerId.isEmpty()) {
                    provider.providerId = stringOrEmpty(entry.getKey());
                }
                if (!provider.providerId.isEmpty()) {
                    out.put(provider.providerId, provider);
                }
            }
        }
        return out;
    }

    private static ProviderConfig providerFromMap(Map<String, Object> map) {
        ProviderConfig out = new ProviderConfig();
        out.providerId = firstNonEmpty(
                stringOrEmpty(map.get("provider_id")),
                stringOrEmpty(map.get("id"))
        );
        out.name = stringOrEmpty(map.get("name"));
        out.apiBaseUrl = stringOrEmpty(map.get("api_base_url"));
        out.apiKey = stringOrEmpty(map.get("api_key"));
        out.model = stringOrEmpty(map.get("model"));
        out.requestType = normalizeRequestType(stringOrEmpty(map.get("request_type")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String resolveProviderId(Map<String, Object> root, String route) {
        Object routingObj = root.get("model_routing");
        if (!(routingObj instanceof Map)) {
            return "";
        }
        Map<String, Object> routing = (Map<String, Object>) routingObj;
        String mode = normalizeRoutingMode(stringOrEmpty(routing.get("mode")));
        Object scriptObj = routing.get("script_action");
        Map<String, Object> script = scriptObj instanceof Map
                ? (Map<String, Object>) scriptObj
                : new LinkedHashMap<String, Object>();
        String unified = firstNonEmpty(
                stringOrEmpty(script.get("unified_provider_id")),
                stringOrEmpty(routing.get("unified_provider_id")),
                stringOrEmpty(root.get("active_provider_id"))
        );
        if (ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR.equals(route)) {
            if (ROUTING_MODE_SPLIT.equals(mode)) {
                return firstNonEmpty(
                        stringOrEmpty(script.get("semantic_locator_provider_id")),
                        stringOrEmpty(routing.get("semantic_locator_provider_id"))
                );
            }
            return unified;
        }
        if (ROUTE_SCRIPT_ACTION_VISION_ACT.equals(route)) {
            if (ROUTING_MODE_SPLIT.equals(mode)) {
                return firstNonEmpty(
                        stringOrEmpty(script.get("vision_act_provider_id")),
                        stringOrEmpty(routing.get("vision_act_provider_id"))
                );
            }
            return unified;
        }
        return unified;
    }

    @SuppressWarnings("unchecked")
    private static boolean isMissingRequiredSplitRoute(Map<String, Object> root, String route) {
        if (!ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR.equals(route)
                && !ROUTE_SCRIPT_ACTION_VISION_ACT.equals(route)) {
            return false;
        }
        Object routingObj = root.get("model_routing");
        if (!(routingObj instanceof Map)) {
            return false;
        }
        Map<String, Object> routing = (Map<String, Object>) routingObj;
        return ROUTING_MODE_SPLIT.equals(normalizeRoutingMode(stringOrEmpty(routing.get("mode"))));
    }

    public static String normalizeRoutingMode(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (ROUTING_MODE_SPLIT.equals(s)) {
            return ROUTING_MODE_SPLIT;
        }
        return ROUTING_MODE_UNIFIED;
    }

    private static String normalizeRoute(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR.equals(s)) {
            return ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR;
        }
        if (ROUTE_SCRIPT_ACTION_VISION_ACT.equals(s)) {
            return ROUTE_SCRIPT_ACTION_VISION_ACT;
        }
        return ROUTE_DEFAULT;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return !stringOrEmpty(a).isEmpty() ? stringOrEmpty(a) : stringOrEmpty(b);
    }

    private static String firstNonEmpty(String a, String b, String c) {
        String first = firstNonEmpty(a, b);
        return !first.isEmpty() ? first : stringOrEmpty(c);
    }

    private static boolean parseBool(Object o, boolean defVal) {
        if (o == null) {
            return defVal;
        }
        if (o instanceof Boolean) {
            return ((Boolean) o).booleanValue();
        }
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.isEmpty()) {
            return defVal;
        }
        if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s)) {
            return false;
        }
        return defVal;
    }

    private static int parseMaxTaskSteps(Object o, int defVal) {
        if (o == null) {
            return normalizeMaxTaskSteps(defVal);
        }
        if (o instanceof Number) {
            long n = ((Number) o).longValue();
            if (n > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return normalizeMaxTaskSteps((int) n);
        }
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.isEmpty()) {
            return normalizeMaxTaskSteps(defVal);
        }
        if ("unlimited".equals(s) || "none".equals(s) || "off".equals(s) || "no_limit".equals(s)) {
            return 0;
        }
        try {
            long n = Long.parseLong(s);
            if (n > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return normalizeMaxTaskSteps((int) n);
        } catch (Exception ignored) {
            return normalizeMaxTaskSteps(defVal);
        }
    }

    private static int normalizeMaxTaskSteps(int raw) {
        return raw <= 0 ? 0 : raw;
    }

    private static String normalizeMapSource(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if ("candidates".equals(s)) {
            return "candidate";
        }
        if ("stable".equals(s) || "candidate".equals(s) || "burn".equals(s)) {
            return s;
        }
        return "stable";
    }

    public static String normalizeRequestType(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (REQUEST_TYPE_GEMINI_GENERATE_CONTENT.equals(s)) {
            return REQUEST_TYPE_GEMINI_GENERATE_CONTENT;
        }
        if (REQUEST_TYPE_ANTHROPIC_MESSAGES.equals(s)) {
            return REQUEST_TYPE_ANTHROPIC_MESSAGES;
        }
        return REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS;
    }

    private static String normalizeTaskDndMode(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if ("skip".equals(s)) {
            return "skip";
        }
        if ("off".equals(s)) {
            return "off";
        }
        if ("none".equals(s)) {
            return "none";
        }
        return "none";
    }

    private static byte[] readAllBytes(File f) throws Exception {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static final class ProviderConfig {
        String providerId = "";
        String name = "";
        String apiBaseUrl = "";
        String apiKey = "";
        String model = "";
        String requestType = REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS;
    }
}
