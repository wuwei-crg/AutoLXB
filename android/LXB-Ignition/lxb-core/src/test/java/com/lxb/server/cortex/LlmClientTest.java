package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class LlmClientTest {

    @Test
    public void buildEndpointUrl_expandsOpenAiCompatibleBaseUrl() {
        Assert.assertEquals(
                "https://api.example.test/v1/chat/completions",
                LlmClient.buildEndpointUrl(
                        "https://api.example.test/v1",
                        LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS,
                        "vision-model"
                )
        );
    }

    @Test
    public void buildEndpointUrl_doesNotDoubleAppendOpenAiEndpoint() {
        Assert.assertEquals(
                "https://api.example.test/v1/chat/completions",
                LlmClient.buildEndpointUrl(
                        "https://api.example.test/v1/chat/completions",
                        LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS,
                        "vision-model"
                )
        );
    }

    @Test
    public void buildEndpointUrl_expandsGeminiBaseUrlWithModel() {
        Assert.assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
                LlmClient.buildEndpointUrl(
                        "https://generativelanguage.googleapis.com/v1beta",
                        LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT,
                        "gemini-2.0-flash"
                )
        );
    }

    @Test
    public void buildEndpointUrl_doesNotDoubleAppendGeminiEndpoint() {
        Assert.assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
                LlmClient.buildEndpointUrl(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
                        LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT,
                        "gemini-2.0-flash"
                )
        );
    }

    @Test
    public void buildEndpointUrl_doesNotDoubleAppendCompleteEndpointWithQuery() {
        Assert.assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?alt=sse",
                LlmClient.buildEndpointUrl(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?alt=sse",
                        LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT,
                        "gemini-2.0-flash"
                )
        );
        Assert.assertEquals(
                "https://api.anthropic.com/v1/messages?debug=true",
                LlmClient.buildEndpointUrl(
                        "https://api.anthropic.com/v1/messages?debug=true",
                        LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES,
                        "claude-3-5-sonnet-latest"
                )
        );
    }

    @Test
    public void buildEndpointUrl_expandsAnthropicBaseUrl() {
        Assert.assertEquals(
                "https://api.anthropic.com/v1/messages",
                LlmClient.buildEndpointUrl(
                        "https://api.anthropic.com/v1",
                        LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES,
                        "claude-3-5-sonnet-latest"
                )
        );
    }

    @Test
    public void buildRequest_openAiTextUsesBearerAndChatMessages() throws Exception {
        LlmClient.PreparedRequest req = LlmClient.buildRequest(
                new LlmConfig(
                        "https://api.example.test/v1",
                        "sk-test",
                        "vision-model",
                        LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS
                ),
                "system text",
                "user text",
                null
        );

        Assert.assertEquals("https://api.example.test/v1/chat/completions", req.endpoint);
        Assert.assertEquals("Bearer sk-test", req.headers.get("Authorization"));
        Map<String, Object> body = Json.parseObject(req.body);
        Assert.assertEquals("vision-model", body.get("model"));
        Assert.assertTrue(body.containsKey("max_tokens"));
        List<?> messages = (List<?>) body.get("messages");
        Assert.assertEquals("system", ((Map<?, ?>) messages.get(0)).get("role"));
        Assert.assertEquals("user", ((Map<?, ?>) messages.get(1)).get("role"));
    }

    @Test
    public void buildRequest_openAiImageKeepsUserPromptPlusImageOnly() throws Exception {
        LlmClient.PreparedRequest req = LlmClient.buildRequest(
                new LlmConfig(
                        "https://api.example.test/v1",
                        "sk-test",
                        "vision-model",
                        LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS
                ),
                "ignored system",
                "read digits",
                new byte[]{1, 2, 3}
        );

        Map<String, Object> body = Json.parseObject(req.body);
        List<?> messages = (List<?>) body.get("messages");
        Assert.assertEquals(1, messages.size());
        Map<?, ?> message = (Map<?, ?>) messages.get(0);
        Assert.assertEquals("user", message.get("role"));
        List<?> content = (List<?>) message.get("content");
        Assert.assertEquals("image_url", ((Map<?, ?>) content.get(0)).get("type"));
        Assert.assertEquals("text", ((Map<?, ?>) content.get(1)).get("type"));
    }

    @Test
    public void buildRequest_geminiUsesQueryKeyAndGenerateContentPayload() throws Exception {
        LlmClient.PreparedRequest req = LlmClient.buildRequest(
                new LlmConfig(
                        "https://generativelanguage.googleapis.com/v1beta",
                        "AIza test",
                        "gemini-2.0-flash",
                        LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT
                ),
                "system text",
                "user text",
                null
        );

        Assert.assertTrue(req.endpoint.startsWith("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="));
        Assert.assertFalse(req.headers.containsKey("Authorization"));
        Map<String, Object> body = Json.parseObject(req.body);
        Assert.assertTrue(body.containsKey("systemInstruction"));
        Assert.assertTrue(body.containsKey("contents"));
    }

    @Test
    public void buildRequest_geminiDoesNotDuplicateExistingKeyQueryParam() {
        LlmClient.PreparedRequest req = LlmClient.buildRequest(
                new LlmConfig(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=existing",
                        "configured",
                        "gemini-2.0-flash",
                        LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT
                ),
                null,
                "user text",
                null
        );

        Assert.assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=existing",
                req.endpoint
        );
    }

    @Test
    public void buildRequest_geminiImageUsesInlineDataWithoutSystemInstruction() throws Exception {
        LlmClient.PreparedRequest req = LlmClient.buildRequest(
                new LlmConfig(
                        "https://generativelanguage.googleapis.com/v1beta",
                        "key",
                        "gemini-2.0-flash",
                        LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT
                ),
                "ignored system",
                "read digits",
                new byte[]{1, 2, 3}
        );

        Map<String, Object> body = Json.parseObject(req.body);
        Assert.assertFalse(body.containsKey("systemInstruction"));
        List<?> contents = (List<?>) body.get("contents");
        Map<?, ?> content = (Map<?, ?>) contents.get(0);
        List<?> parts = (List<?>) content.get("parts");
        Assert.assertTrue(((Map<?, ?>) parts.get(0)).containsKey("inlineData"));
        Assert.assertEquals("read digits", ((Map<?, ?>) parts.get(1)).get("text"));
    }

    @Test
    public void buildRequest_anthropicUsesNativeHeadersAndMessagesPayload() throws Exception {
        LlmClient.PreparedRequest req = LlmClient.buildRequest(
                new LlmConfig(
                        "https://api.anthropic.com/v1",
                        "sk-ant-test",
                        "claude-3-5-sonnet-latest",
                        LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES
                ),
                "system text",
                "user text",
                null
        );

        Assert.assertEquals("https://api.anthropic.com/v1/messages", req.endpoint);
        Assert.assertEquals("sk-ant-test", req.headers.get("x-api-key"));
        Assert.assertEquals(LlmClient.ANTHROPIC_VERSION, req.headers.get("anthropic-version"));
        Map<String, Object> body = Json.parseObject(req.body);
        Assert.assertEquals("system text", body.get("system"));
        Assert.assertEquals("claude-3-5-sonnet-latest", body.get("model"));
        Assert.assertTrue(body.containsKey("messages"));
    }

    @Test
    public void buildRequest_anthropicImageUsesBase64SourceWithoutSystem() throws Exception {
        LlmClient.PreparedRequest req = LlmClient.buildRequest(
                new LlmConfig(
                        "https://api.anthropic.com/v1",
                        "sk-ant-test",
                        "claude-3-5-sonnet-latest",
                        LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES
                ),
                "ignored system",
                "read digits",
                new byte[]{1, 2, 3}
        );

        Map<String, Object> body = Json.parseObject(req.body);
        Assert.assertFalse(body.containsKey("system"));
        List<?> messages = (List<?>) body.get("messages");
        Map<?, ?> message = (Map<?, ?>) messages.get(0);
        List<?> content = (List<?>) message.get("content");
        Assert.assertEquals("image", ((Map<?, ?>) content.get(0)).get("type"));
        Assert.assertEquals("text", ((Map<?, ?>) content.get(1)).get("type"));
    }

    @Test
    public void extractAssistantText_openAiKeepsFallbacks() throws Exception {
        Map<String, Object> root = Json.parseObject("{\"output_text\":\"fallback text\"}");

        Assert.assertEquals(
                "fallback text",
                LlmClient.extractAssistantText(LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS, root)
        );
    }

    @Test
    public void extractAssistantText_geminiReadsCandidateParts() throws Exception {
        Map<String, Object> root = Json.parseObject("{"
                + "\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"},{\"text\":\"world\"}]}}]"
                + "}");

        Assert.assertEquals(
                "hello\nworld",
                LlmClient.extractAssistantText(LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT, root)
        );
    }

    @Test
    public void extractAssistantText_anthropicReadsTextContent() throws Exception {
        Map<String, Object> root = Json.parseObject("{"
                + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"text\",\"text\":\"world\"}]"
                + "}");

        Assert.assertEquals(
                "hello\nworld",
                LlmClient.extractAssistantText(LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES, root)
        );
    }

    @Test
    public void extractAssistantText_nativeShapesDoNotUseOpenAiFallbacks() throws Exception {
        Map<String, Object> root = Json.parseObject("{\"output_text\":\"fallback text\"}");

        Assert.assertEquals(
                "",
                LlmClient.extractAssistantText(LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT, root)
        );
        Assert.assertEquals(
                "",
                LlmClient.extractAssistantText(LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES, root)
        );
    }
}
