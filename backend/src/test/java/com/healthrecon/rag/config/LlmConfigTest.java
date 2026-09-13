package com.healthrecon.rag.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigTest {

    private final LlmConfig config = new LlmConfig();

    private LlmProperties props(String baseUrl, String apiKey) {
        return new LlmProperties(baseUrl, apiKey, "gpt-4o-mini", "text-embedding-3-small", 1536);
    }

    @Test
    void refusesToStartWithoutBaseUrl() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> config.chatModel(props("", "sk-test")));

        assertTrue(ex.getMessage().contains("LLM_BASE_URL"));
    }

    @Test
    void refusesToStartWithoutApiKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> config.embeddingModel(props("https://api.openai.com/v1", null)));

        assertTrue(ex.getMessage().contains("LLM_API_KEY"));
    }

    @Test
    void buildsModelsWhenFullyConfigured() {
        LlmProperties props = props("https://api.openai.com/v1", "sk-test");

        assertNotNull(config.chatModel(props));
        assertNotNull(config.embeddingModel(props));
    }
}