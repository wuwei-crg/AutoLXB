package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LlmClient {
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    public static class PreparedRequest {
        public final String endpoint;
        public final Map<String, String> headers;
        public final String body;

        PreparedRequest(String endpoint, Map<String, String> headers, String body) {
            this.endpoint = endpoint;
            this.headers = headers;
            this.body = body;
        }
    }

    public String chatOnce(LlmConfig config, String systemPrompt, String userMessage, byte[] imagePng) throws Exception {
        return chatOnce(config, systemPrompt, userMessage, imagePng, 10000, 60000);
    }

    public String chatOnce(
            LlmConfig config,
            String systemPrompt,
            String userMessage,
            byte[] imagePng,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws Exception {
        int normalizedConnectTimeout = normalizeTimeout(connectTimeoutMs, 10000);
        int normalizedReadTimeout = normalizeTimeout(readTimeoutMs, 60000);
        final int maxAttempts = 3;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return chatOnceSingleAttempt(
                        config,
                        systemPrompt,
                        userMessage,
                        imagePng,
                        normalizedConnectTimeout,
                        normalizedReadTimeout
                );
            } catch (Exception e) {
                last = e;
                if (attempt >= maxAttempts || !shouldRetry(e)) {
                    throw e;
                }
                sleepBackoff(attempt);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("LLM call failed with unknown error");
    }

    private String chatOnceSingleAttempt(
            LlmConfig config,
            String systemPrompt,
            String userMessage,
            byte[] imagePng,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws Exception {
        HttpURLConnection conn = null;
        try {
            PreparedRequest request = buildRequest(config, systemPrompt, userMessage, imagePng);

            URL url = new URL(request.endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            for (Map.Entry<String, String> header : request.headers.entrySet()) {
                conn.setRequestProperty(header.getKey(), header.getValue());
            }

            byte[] bytes = request.body.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String resp = readAll(is);
            if (code >= 200 && code < 300 && (resp == null || resp.trim().isEmpty())) {
                throw new IllegalStateException("LLM HTTP " + code + ": empty body");
            }

            if (code < 200 || code >= 300) {
                String snippet = resp;
                if (snippet != null && snippet.length() > 256) {
                    snippet = snippet.substring(0, 256) + "...";
                }
                throw new IllegalStateException("LLM HTTP " + code + ": " + snippet);
            }

            try {
                Object parsed = Json.parse(resp);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> root = (Map<String, Object>) parsed;
                    String extracted = extractAssistantText(config.requestType, root);
                    if (extracted != null && !extracted.isEmpty()) {
                        return extracted;
                    }
                }
            } catch (Exception ignored) {
            }

            if (!LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS.equals(config.requestType)) {
                throw new IllegalStateException("LLM response missing text for request_type=" + config.requestType);
            }
            return resp;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean shouldRetry(Exception e) {
        if (e instanceof SocketTimeoutException) {
            return true;
        }
        if (e instanceof InterruptedIOException) {
            // Includes read timeout and interrupted I/O variants.
            return true;
        }
        // Retry once/multiple times on transient HTTP failures wrapped in IllegalStateException.
        if (e instanceof IllegalStateException) {
            String msg = String.valueOf(e.getMessage());
            // Retry for timeout and 5xx/429 classes; skip hard auth/missing endpoint failures.
            if (msg.contains("timeout")) return true;
            if (msg.contains("HTTP 5")) return true;
            if (msg.contains("HTTP 429")) return true;
            if (msg.contains("HTTP 408")) return true;
            if (msg.contains("HTTP 401")) return false;
            if (msg.contains("HTTP 403")) return false;
            if (msg.contains("HTTP 404")) return false;
            return true;
        }
        // Fallback: retry unknown transient exceptions as requested.
        return true;
    }

    private static void sleepBackoff(int attempt) throws InterruptedException {
        // 1st retry: 600ms, 2nd retry: 1200ms.
        long ms = 600L * Math.max(1, attempt);
        Thread.sleep(ms);
    }

    public static PreparedRequest buildRequest(
            LlmConfig config,
            String systemPrompt,
            String userMessage,
            byte[] imagePng
    ) {
        String requestType = LlmConfig.normalizeRequestType(config != null ? config.requestType : "");
        String model = config != null ? config.model : "";
        String body;
        if (LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT.equals(requestType)) {
            body = (imagePng != null && imagePng.length > 0)
                    ? buildGeminiPayloadWithImage(userMessage, imagePng)
                    : buildGeminiPayload(systemPrompt, userMessage);
        } else if (LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES.equals(requestType)) {
            body = (imagePng != null && imagePng.length > 0)
                    ? buildAnthropicPayloadWithImage(model, userMessage, imagePng)
                    : buildAnthropicPayload(model, systemPrompt, userMessage);
        } else {
            body = (imagePng != null && imagePng.length > 0)
                    ? buildOpenAiChatPayloadWithImage(model, userMessage, imagePng)
                    : buildOpenAiChatPayload(model, systemPrompt, userMessage);
        }

        Map<String, String> headers = buildHeaders(requestType, config != null ? config.apiKey : "");
        String endpoint = buildEndpointUrl(config != null ? config.apiBaseUrl : "", requestType, model);
        if (LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT.equals(requestType) && !hasQueryParam(endpoint, "key")) {
            endpoint = appendQueryParam(endpoint, "key", config != null ? config.apiKey : "");
        }
        return new PreparedRequest(endpoint, headers, body);
    }

    private static Map<String, String> buildHeaders(String requestType, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        String key = apiKey != null ? apiKey.trim() : "";
        if (LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES.equals(requestType)) {
            headers.put("anthropic-version", ANTHROPIC_VERSION);
            if (!key.isEmpty()) {
                headers.put("x-api-key", key);
            }
        } else if (!LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT.equals(requestType) && !key.isEmpty()) {
            headers.put("Authorization", "Bearer " + key);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    public static String extractAssistantText(String requestType, Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            return "";
        }
        String normalized = LlmConfig.normalizeRequestType(requestType);
        if (LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT.equals(normalized)) {
            return extractGeminiText(root);
        }
        if (LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES.equals(normalized)) {
            return extractAnthropicText(root);
        }
        // Common OpenAI-compatible chat field.
        Object choicesObj = root.get("choices");
        if (choicesObj instanceof List) {
            List<?> choices = (List<?>) choicesObj;
            if (!choices.isEmpty() && choices.get(0) instanceof Map) {
                Map<String, Object> c0 = (Map<String, Object>) choices.get(0);
                Object messageObj = c0.get("message");
                if (messageObj instanceof Map) {
                    Map<String, Object> msg = (Map<String, Object>) messageObj;
                    String s = extractContentText(msg.get("content"));
                    if (!s.isEmpty()) return s;
                    s = extractContentText(msg.get("reasoning_content"));
                    if (!s.isEmpty()) return s;
                }
                String s = extractContentText(c0.get("text"));
                if (!s.isEmpty()) return s;
            }
        }
        // Some providers expose direct output_text field.
        String outText = extractContentText(root.get("output_text"));
        if (!outText.isEmpty()) {
            return outText;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String extractGeminiText(Map<String, Object> root) {
        Object candidatesObj = root.get("candidates");
        if (!(candidatesObj instanceof List)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object candidateObj : (List<Object>) candidatesObj) {
            if (!(candidateObj instanceof Map)) {
                continue;
            }
            Map<String, Object> candidate = (Map<String, Object>) candidateObj;
            Object contentObj = candidate.get("content");
            if (!(contentObj instanceof Map)) {
                continue;
            }
            Map<String, Object> content = (Map<String, Object>) contentObj;
            Object partsObj = content.get("parts");
            if (!(partsObj instanceof List)) {
                continue;
            }
            for (Object partObj : (List<Object>) partsObj) {
                if (partObj instanceof Map) {
                    String s = extractContentText(((Map<String, Object>) partObj).get("text"));
                    if (!s.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(s);
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static String extractAnthropicText(Map<String, Object> root) {
        Object contentObj = root.get("content");
        if (!(contentObj instanceof List)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object itemObj : (List<Object>) contentObj) {
            if (itemObj instanceof Map) {
                Map<String, Object> item = (Map<String, Object>) itemObj;
                String type = String.valueOf(item.get("type"));
                if ("text".equals(type)) {
                    String s = extractContentText(item.get("text"));
                    if (!s.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(s);
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private static int normalizeTimeout(int timeoutMs, int defVal) {
        if (timeoutMs <= 0) return defVal;
        if (timeoutMs < 500) return 500;
        if (timeoutMs > 180000) return 180000;
        return timeoutMs;
    }

    @SuppressWarnings("unchecked")
    private static String extractContentText(Object contentObj) {
        if (contentObj == null) {
            return "";
        }
        if (contentObj instanceof String) {
            return ((String) contentObj).trim();
        }
        if (contentObj instanceof Number || contentObj instanceof Boolean) {
            return String.valueOf(contentObj).trim();
        }
        if (contentObj instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<Object>) contentObj) {
                String s = extractContentText(item);
                if (!s.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(s);
                }
            }
            return sb.toString().trim();
        }
        if (contentObj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) contentObj;
            // Common multimodal content item shapes.
            String s = extractContentText(m.get("text"));
            if (!s.isEmpty()) return s;
            s = extractContentText(m.get("content"));
            if (!s.isEmpty()) return s;
            s = extractContentText(m.get("value"));
            if (!s.isEmpty()) return s;
            // As a last resort keep a compact string form.
            return String.valueOf(m).trim();
        }
        return String.valueOf(contentObj).trim();
    }

    public String chatOnce(LlmConfig config, String userMessage) throws Exception {
        return chatOnce(config, null, userMessage, null);
    }

    public String chatOnce(LlmConfig config, String systemPrompt, String userMessage) throws Exception {
        return chatOnce(config, systemPrompt, userMessage, null);
    }

    public static String buildEndpointUrl(String rawBase) {
        return buildEndpointUrl(rawBase, LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS, "");
    }

    public static String buildEndpointUrl(String rawBase, String requestType, String model) {
        String base = rawBase != null ? rawBase.trim() : "";
        if (base.isEmpty()) {
            throw new IllegalStateException("LLM api_base_url is empty");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new IllegalStateException("LLM api_base_url is empty");
        }
        String lower = base.toLowerCase(Locale.ROOT);
        int queryIdx = lower.indexOf('?');
        String lowerPath = queryIdx >= 0 ? lower.substring(0, queryIdx) : lower;
        String normalized = LlmConfig.normalizeRequestType(requestType);
        if (LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT.equals(normalized)) {
            if (lowerPath.endsWith(":generatecontent")) {
                return base;
            }
            String modelPath = model != null ? model.trim() : "";
            if (modelPath.startsWith("models/")) {
                return base + "/" + modelPath + ":generateContent";
            }
            return base + "/models/" + modelPath + ":generateContent";
        }
        if (LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES.equals(normalized)) {
            if (lowerPath.endsWith("/messages")) {
                return base;
            }
            return base + "/messages";
        }
        if (lowerPath.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private static String buildOpenAiChatPayload(String model, String systemPrompt, String userMessage) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", model);

        List<Map<String, Object>> msgs = new ArrayList<Map<String, Object>>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, Object> sys = new LinkedHashMap<String, Object>();
            sys.put("role", "system");
            sys.put("content", systemPrompt.trim());
            msgs.add(sys);
        }
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        user.put("content", userMessage);
        msgs.add(user);

        root.put("messages", msgs);
        root.put("max_tokens", 16384);
        return Json.stringify(root);
    }

    private static String buildOpenAiChatPayloadWithImage(String model, String userMessage, byte[] imagePng) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", model);

        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");

        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        Map<String, Object> imgPart = new LinkedHashMap<String, Object>();
        imgPart.put("type", "image_url");
        Map<String, Object> imgUrl = new LinkedHashMap<String, Object>();
        String b64 = Base64.getEncoder().encodeToString(imagePng);
        imgUrl.put("url", "data:image/png;base64," + b64);
        imgPart.put("image_url", imgUrl);
        content.add(imgPart);

        Map<String, Object> textPart = new LinkedHashMap<String, Object>();
        textPart.put("type", "text");
        textPart.put("text", userMessage);
        content.add(textPart);

        user.put("content", content);
        messages.add(user);

        root.put("messages", messages);
        root.put("max_tokens", 16384);
        return Json.stringify(root);
    }

    private static String buildGeminiPayload(String systemPrompt, String userMessage) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        String sys = systemPrompt != null ? systemPrompt.trim() : "";
        if (!sys.isEmpty()) {
            Map<String, Object> systemInstruction = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
            Map<String, Object> part = new LinkedHashMap<String, Object>();
            part.put("text", sys);
            parts.add(part);
            systemInstruction.put("parts", parts);
            root.put("systemInstruction", systemInstruction);
        }
        root.put("contents", geminiUserContents(userMessage, null));
        return Json.stringify(root);
    }

    private static String buildGeminiPayloadWithImage(String userMessage, byte[] imagePng) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("contents", geminiUserContents(userMessage, imagePng));
        return Json.stringify(root);
    }

    private static List<Map<String, Object>> geminiUserContents(String userMessage, byte[] imagePng) {
        List<Map<String, Object>> contents = new ArrayList<Map<String, Object>>();
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        List<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
        if (imagePng != null && imagePng.length > 0) {
            Map<String, Object> imagePart = new LinkedHashMap<String, Object>();
            Map<String, Object> inlineData = new LinkedHashMap<String, Object>();
            inlineData.put("mimeType", "image/png");
            inlineData.put("data", Base64.getEncoder().encodeToString(imagePng));
            imagePart.put("inlineData", inlineData);
            parts.add(imagePart);
        }
        Map<String, Object> textPart = new LinkedHashMap<String, Object>();
        textPart.put("text", userMessage);
        parts.add(textPart);
        user.put("parts", parts);
        contents.add(user);
        return contents;
    }

    private static String buildAnthropicPayload(String model, String systemPrompt, String userMessage) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", model);
        root.put("max_tokens", 16384);
        String sys = systemPrompt != null ? systemPrompt.trim() : "";
        if (!sys.isEmpty()) {
            root.put("system", sys);
        }
        root.put("messages", anthropicUserMessages(userMessage, null));
        return Json.stringify(root);
    }

    private static String buildAnthropicPayloadWithImage(String model, String userMessage, byte[] imagePng) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", model);
        root.put("max_tokens", 16384);
        root.put("messages", anthropicUserMessages(userMessage, imagePng));
        return Json.stringify(root);
    }

    private static List<Map<String, Object>> anthropicUserMessages(String userMessage, byte[] imagePng) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        List<Map<String, Object>> content = new ArrayList<Map<String, Object>>();
        if (imagePng != null && imagePng.length > 0) {
            Map<String, Object> imagePart = new LinkedHashMap<String, Object>();
            imagePart.put("type", "image");
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("type", "base64");
            source.put("media_type", "image/png");
            source.put("data", Base64.getEncoder().encodeToString(imagePng));
            imagePart.put("source", source);
            content.add(imagePart);
        }
        Map<String, Object> textPart = new LinkedHashMap<String, Object>();
        textPart.put("type", "text");
        textPart.put("text", userMessage);
        content.add(textPart);
        user.put("content", content);
        messages.add(user);
        return messages;
    }

    private static String appendQueryParam(String endpoint, String key, String value) {
        String v = value != null ? value.trim() : "";
        if (v.isEmpty()) {
            return endpoint;
        }
        try {
            String encoded = URLEncoder.encode(v, "UTF-8");
            return endpoint + (endpoint.contains("?") ? "&" : "?") + key + "=" + encoded;
        } catch (Exception e) {
            return endpoint + (endpoint.contains("?") ? "&" : "?") + key + "=" + v;
        }
    }

    private static boolean hasQueryParam(String endpoint, String key) {
        if (endpoint == null || key == null || key.isEmpty()) {
            return false;
        }
        int queryIdx = endpoint.indexOf('?');
        if (queryIdx < 0 || queryIdx >= endpoint.length() - 1) {
            return false;
        }
        String query = endpoint.substring(queryIdx + 1);
        String[] parts = query.split("&");
        for (String part : parts) {
            int eqIdx = part.indexOf('=');
            String name = eqIdx >= 0 ? part.substring(0, eqIdx) : part;
            if (key.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
