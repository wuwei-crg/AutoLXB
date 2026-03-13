package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP client for calling OpenAI-style chat completion APIs from
 * end-side Cortex.
 *
 * This client is deliberately simple:
 * - No streaming
 * - JSON-only payloads
 * - Caller is responsible for interpreting the response JSON
 */
public class LlmClient {

    /**
     * Send a single chat completion request using the given config and user message.
     *
     * Payload shape (OpenAI-style):
     * {
     *   "model": "...",
     *   "messages": [{ "role": "user", "content": userMessage }],
     *   "max_tokens": 64
     * }
     *
     * @return Assistant message content (choices[0].message.content) as UTF-8 string,
     *         or raw body if response cannot be parsed.
     */
    public String chatOnce(LlmConfig config, String systemPrompt, String userMessage) throws Exception {
        HttpURLConnection conn = null;
        try {
            String endpoint = buildEndpointUrl(config.apiBaseUrl);
            boolean useResponses = endpoint.toLowerCase().contains("/responses");

            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (config.apiKey != null && !config.apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            }

            String body = useResponses
                    ? buildResponsesPayload(config.model, systemPrompt, userMessage)
                    : buildChatPayload(config.model, systemPrompt, userMessage);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(bytes);
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String resp = readAll(is);

            if (code < 200 || code >= 300) {
                String snippet = resp;
                if (snippet != null && snippet.length() > 256) {
                    snippet = snippet.substring(0, 256) + "...";
                }
                throw new IllegalStateException("LLM HTTP " + code + ": " + snippet);
            }

            // Normalize response text:
            // - For /responses endpoints, extract assistant text from the new Responses schema.
            // - For chat completions, extract choices[0].message.content or choices[0].text.
            try {
                if (useResponses) {
                    String text = extractTextFromResponses(resp);
                    if (text != null && !text.trim().isEmpty()) {
                        return text.trim();
                    }
                } else {
                    Object parsed = Json.parse(resp);
                    if (parsed instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> root = (Map<String, Object>) parsed;
                        Object choicesObj = root.get("choices");
                        if (choicesObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> choices = (List<Object>) choicesObj;
                            if (!choices.isEmpty()) {
                                Object first = choices.get(0);
                                if (first instanceof Map) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> choice = (Map<String, Object>) first;
                                    Object messageObj = choice.get("message");
                                    if (messageObj instanceof Map) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> msg = (Map<String, Object>) messageObj;
                                        Object contentObj = msg.get("content");
                                        if (contentObj != null) {
                                            return String.valueOf(contentObj).trim();
                                        }
                                    }
                                    // Some providers may use "text" directly on choice.
                                    Object textObj = choice.get("text");
                                    if (textObj != null) {
                                        return String.valueOf(textObj).trim();
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fallback to raw body below.
            }

            // Fallback: return raw body for callers that want to parse full JSON themselves.
            return resp;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Backwards-compatible overload without system prompt.
     *
     * @return Assistant message content (choices[0].message.content) as UTF-8 string,
     *         or raw body if response cannot be parsed.
     */
    public String chatOnce(LlmConfig config, String userMessage) throws Exception {
        return chatOnce(config, null, userMessage);
    }

    /**
     * Build the final HTTP endpoint URL from apiBaseUrl.
     *
     * Python side typically configures base_url like "https://api.openai.com/v1".
     * To mirror that, we auto-append "/chat/completions" when apiBaseUrl ends with "/v1" or "/v1/".
     * If apiBaseUrl already points to a full chat/completions endpoint, we use it as-is.
     */
    public static String buildEndpointUrl(String rawBase) {
        String base = rawBase != null ? rawBase.trim() : "";
        if (base.isEmpty()) {
            throw new IllegalStateException("LLM api_base_url is empty");
        }

        String lower = base.toLowerCase();
        if (lower.endsWith("/chat/completions")) {
            return base;
        }
        if (lower.endsWith("/v1") || lower.endsWith("/v1/")) {
            // Normalize trailing slash and append chat/completions.
            if (base.endsWith("/")) {
                return base + "chat/completions";
            } else {
                return base + "/chat/completions";
            }
        }
        // Otherwise assume caller provided a full endpoint.
        return base;
    }

    private static String buildChatPayload(String model, String systemPrompt, String userMessage) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);

        List<Map<String, Object>> msgs = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", systemPrompt.trim());
            msgs.add(sys);
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", userMessage);
        msgs.add(user);

        root.put("messages", msgs);
        // Use a very high max_tokens; provider will clamp to its own limit.
        root.put("max_tokens", 60000);

        return Json.stringify(root);
    }

    /**
     * Build payload for /v1/responses style endpoints.
     *
     * Example shape (vendor demo):
     * {
     *   "model": "...",
     *   "input": [
     *     {"role": "user", "content": "..."}
     *   ]
     * }
     */
    private static String buildResponsesPayload(String model, String systemPrompt, String userMessage) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);

        List<Map<String, Object>> input = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", systemPrompt.trim());
            input.add(sys);
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", userMessage);
        input.add(user);

        root.put("input", input);
        // Use a very high max_output_tokens; provider will clamp if needed.
        root.put("max_output_tokens", 60000);

        return Json.stringify(root);
    }

    /**
     * Best-effort extraction of assistant text from /v1/responses style JSON.
     *
     * We try a few common shapes:
     * - { "output_text": "..." }
     * - { "output": [ { "content": [ { "text": "..." } ] } ] }
     * - { "output": [ { "content": [ { "text": { "value": "..." } } ] } ] }
     */
    @SuppressWarnings("unchecked")
    private static String extractTextFromResponses(String resp) {
        try {
            Object parsed = Json.parse(resp);
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<String, Object> root = (Map<String, Object>) parsed;

            Object outText = root.get("output_text");
            if (outText != null) {
                return String.valueOf(outText);
            }

            Object outputObj = root.get("output");
            if (outputObj instanceof List) {
                List<?> outList = (List<?>) outputObj;
                for (Object item : outList) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> im = (Map<String, Object>) item;
                    Object contentObj = im.get("content");
                    if (!(contentObj instanceof List)) continue;
                    List<?> contentList = (List<?>) contentObj;
                    for (Object c : contentList) {
                        if (!(c instanceof Map)) continue;
                        Map<String, Object> cm = (Map<String, Object>) c;

                        Object textObj = cm.get("text");
                        if (textObj instanceof String) {
                            return (String) textObj;
                        }
                        if (textObj instanceof Map) {
                            Map<String, Object> tm = (Map<String, Object>) textObj;
                            Object val = tm.get("value");
                            if (val instanceof String) {
                                return (String) val;
                            }
                        }

                        Object ot2 = cm.get("output_text");
                        if (ot2 instanceof String) {
                            return (String) ot2;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
