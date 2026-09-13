package com.healthrecon.rag.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the language and embedding models from configuration. The client is
 * OpenAI-compatible (OpenAI, LM Studio, etc.). Both an API key and a base URL
 * are required — the application refuses to start without them so the operator
 * is told about the missing configuration up front instead of silently
 * falling back to a local default. Models are created lazily and only perform
 * network I/O when invoked; tests replace them with mocks.
 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, QdrantProperties.class, RagProperties.class, QuotaProperties.class})
public class LlmConfig {

    @Bean
    public ChatModel chatModel(LlmProperties properties) {
        requireConfigured(properties);
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.chatModel())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(LlmProperties properties) {
        requireConfigured(properties);
        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.embeddingModel())
                .dimensions(properties.embeddingDimension())
                .build();
    }

    private static void requireConfigured(LlmProperties properties) {
        if (isBlank(properties.baseUrl())) {
            throw new IllegalStateException(
                    "LLM_BASE_URL is not configured: set it to an OpenAI-compatible endpoint "
                            + "(e.g. https://api.openai.com/v1) and restart the backend.");
        }
        if (isBlank(properties.apiKey())) {
            throw new IllegalStateException(
                    "LLM_API_KEY is not configured: set it to an OpenAI-compatible API key "
                            + "(together with LLM_BASE_URL) and restart the backend.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}