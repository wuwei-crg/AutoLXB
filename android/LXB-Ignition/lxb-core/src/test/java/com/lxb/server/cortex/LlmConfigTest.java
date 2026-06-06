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

    private static File writeConfig(String json) throws Exception {
        File dir = Files.createTempDirectory("llm-config-test").toFile();
        File f = new File(dir, "lxb-llm-config.json");
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return f;
    }
}
