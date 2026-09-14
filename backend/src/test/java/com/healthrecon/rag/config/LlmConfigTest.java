package com.healthrecon.rag.config;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigTest {

    private final LlmConfig config = new LlmConfig();

    private final ObjectProvider<ChatModelListener> noChatListeners = emptyProvider();
    private final ObjectProvider<EmbeddingModelListener> noEmbeddingListeners = emptyProvider();

    private LlmProperties props(String baseUrl, String apiKey) {
        return new LlmProperties(baseUrl, apiKey, "gpt-4o-mini", "text-embedding-3-small", 1536);
    }

    @Test
    void refusesToStartWithoutBaseUrl() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> config.chatModel(props("", "sk-test"), noChatListeners));

        assertTrue(ex.getMessage().contains("LLM_BASE_URL"));
    }

    @Test
    void refusesToStartWithoutApiKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> config.embeddingModel(props("https://api.openai.com/v1", null), noEmbeddingListeners));

        assertTrue(ex.getMessage().contains("LLM_API_KEY"));
    }

    @Test
    void buildsModelsWhenFullyConfigured() {
        LlmProperties props = props("https://api.openai.com/v1", "sk-test");

        assertNotNull(config.chatModel(props, noChatListeners));
        assertNotNull(config.embeddingModel(props, noEmbeddingListeners));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException("No beans expected in unit tests");
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }
        };
    }
}