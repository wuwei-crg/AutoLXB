package com.lxb.server.cortex;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LlmConfigTest {

    @Test
    public void loadFromFile_defaultsMaxTaskStepsWhenMissing() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://example.test/v1\","
                + "\"model\":\"vision-model\""
                + "}");

        LlmConfig cfg = LlmConfig.loadFromFile(f.getAbsolutePath());

        Assert.assertEquals(LlmConfig.DEFAULT_MAX_TASK_STEPS, cfg.maxTaskSteps);
        Assert.assertEquals(LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS, cfg.requestType);
    }

    @Test
    public void loadFromFile_parsesConfiguredRequestType() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://example.test/v1\","
                + "\"model\":\"vision-model\","
                + "\"request_type\":\"gemini_generate_content\""
                + "}");

        LlmConfig cfg = LlmConfig.loadFromFile(f.getAbsolutePath());

        Assert.assertEquals(LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT, cfg.requestType);
    }

    @Test
    public void loadFromFile_normalizesUnknownRequestTypeToOpenAiCompatible() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://example.test/v1\","
                + "\"model\":\"vision-model\","
                + "\"request_type\":\"unknown\""
                + "}");

        LlmConfig cfg = LlmConfig.loadFromFile(f.getAbsolutePath());

        Assert.assertEquals(LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS, cfg.requestType);
    }

    @Test
    public void loadFromFile_parsesConfiguredMaxTaskSteps() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://example.test/v1\","
                + "\"model\":\"vision-model\","
                + "\"max_task_steps\":250"
                + "}");

        LlmConfig cfg = LlmConfig.loadFromFile(f.getAbsolutePath());

        Assert.assertEquals(250, cfg.maxTaskSteps);
    }

    @Test
    public void loadFromFile_treatsZeroAsUnlimitedMaxTaskSteps() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://example.test/v1\","
                + "\"model\":\"vision-model\","
                + "\"max_task_steps\":0"
                + "}");

        LlmConfig cfg = LlmConfig.loadFromFile(f.getAbsolutePath());

        Assert.assertEquals(0, cfg.maxTaskSteps);
    }

    @Test
    public void loadFromFileForRoute_resolvesUnifiedProviderForScriptActionRoutes() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://legacy.test/v1\","
                + "\"model\":\"legacy-model\","
                + "\"providers\":["
                + "{\"provider_id\":\"p1\",\"name\":\"Provider One\",\"api_base_url\":\"https://one.test/v1\",\"model\":\"model-one\",\"request_type\":\"gemini_generate_content\"}"
                + "],"
                + "\"model_routing\":{\"mode\":\"unified\",\"script_action\":{\"unified_provider_id\":\"p1\"}}"
                + "}");

        LlmConfig semantic = LlmConfig.loadFromFileForRoute(f.getAbsolutePath(), LlmConfig.ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR);
        LlmConfig vision = LlmConfig.loadFromFileForRoute(f.getAbsolutePath(), LlmConfig.ROUTE_SCRIPT_ACTION_VISION_ACT);

        Assert.assertEquals("p1", semantic.providerId);
        Assert.assertEquals("model-one", semantic.model);
        Assert.assertEquals(LlmConfig.REQUEST_TYPE_GEMINI_GENERATE_CONTENT, semantic.requestType);
        Assert.assertEquals("p1", vision.providerId);
        Assert.assertEquals("model-one", vision.model);
    }

    @Test
    public void loadFromFileForRoute_resolvesSplitScriptActionProviders() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://legacy.test/v1\","
                + "\"model\":\"legacy-model\","
                + "\"providers\":["
                + "{\"provider_id\":\"semantic\",\"name\":\"Semantic\",\"api_base_url\":\"https://semantic.test/v1\",\"model\":\"semantic-model\",\"request_type\":\"openai_chat_completions\"},"
                + "{\"provider_id\":\"vision\",\"name\":\"Vision\",\"api_base_url\":\"https://vision.test/v1\",\"model\":\"vision-model\",\"request_type\":\"anthropic_messages\"}"
                + "],"
                + "\"model_routing\":{\"mode\":\"split\",\"script_action\":{\"semantic_locator_provider_id\":\"semantic\",\"vision_act_provider_id\":\"vision\"}}"
                + "}");

        LlmConfig semantic = LlmConfig.loadFromFileForRoute(f.getAbsolutePath(), LlmConfig.ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR);
        LlmConfig vision = LlmConfig.loadFromFileForRoute(f.getAbsolutePath(), LlmConfig.ROUTE_SCRIPT_ACTION_VISION_ACT);

        Assert.assertEquals("semantic", semantic.providerId);
        Assert.assertEquals("semantic-model", semantic.model);
        Assert.assertEquals(LlmConfig.REQUEST_TYPE_OPENAI_CHAT_COMPLETIONS, semantic.requestType);
        Assert.assertEquals("vision", vision.providerId);
        Assert.assertEquals("vision-model", vision.model);
        Assert.assertEquals(LlmConfig.REQUEST_TYPE_ANTHROPIC_MESSAGES, vision.requestType);
    }

    @Test
    public void loadFromFileForRoute_legacyConfigFallbackStillWorks() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://legacy.test/v1\","
                + "\"model\":\"legacy-model\""
                + "}");

        LlmConfig cfg = LlmConfig.loadFromFileForRoute(f.getAbsolutePath(), LlmConfig.ROUTE_SCRIPT_ACTION_VISION_ACT);

        Assert.assertEquals("", cfg.providerId);
        Assert.assertEquals("legacy-model", cfg.model);
    }

    @Test
    public void loadFromFileForRoute_splitModeRequiresRouteProvider() throws Exception {
        File f = writeConfig("{"
                + "\"api_base_url\":\"https://legacy.test/v1\","
                + "\"model\":\"legacy-model\","
                + "\"providers\":[],"
                + "\"model_routing\":{\"mode\":\"split\",\"script_action\":{}}"
                + "}");

        try {
            LlmConfig.loadFromFileForRoute(f.getAbsolutePath(), LlmConfig.ROUTE_SCRIPT_ACTION_SEMANTIC_LOCATOR);
            Assert.fail("expected missing split route provider");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("LLM route provider not configured"));
        }
    }

    private static File writeConfig(String json) throws Exception {
        File dir = Files.createTempDirectory("llm-config-test").toFile();
        File f = new File(dir, "lxb-llm-config.json");
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return f;
    }
}
