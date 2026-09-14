package com.healthrecon.rag.service.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class LangfuseChatModelListenerTest {

    private InMemorySpanExporter exporter;
    private LangfuseSpanHelper helper;
    private LangfuseChatModelListener listener;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter))
                .build();
        helper = new LangfuseSpanHelper(provider.get("test"), 1.0, true);
        listener = new LangfuseChatModelListener(helper);
    }

    @AfterEach
    void tearDown() {
        exporter.reset();
    }

    @Test
    void recordsRequestAndResponseAsGenerationSpan() {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        new SystemMessage("You are a doctor."),
                        new UserMessage("What dosage?")))
                .modelName("gpt-4o-mini")
                .build();
        Map<Object, Object> attributes = new HashMap<>();

        try (TracedSpan ignored = helper.beginTrace("chat", Map.of())) {
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
            listener.onResponse(new ChatModelResponseContext(
                    ChatResponse.builder()
                            .aiMessage(new AiMessage("Take 500mg [1]."))
                            .tokenUsage(new TokenUsage(15, 8))
                            .build(),
                    request, ModelProvider.OPEN_AI, attributes));
        }

        SpanData span = generationChatSpan();

        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")))
                .isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("gpt-4o-mini");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("input.value")))
                .contains("What dosage?")
                .contains("You are a doctor.");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("output.value")))
                .isEqualTo("Take 500mg [1].");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                .isEqualTo(15L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")))
                .isEqualTo(8L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("INFO");
    }

    @Test
    void errorEventsRecordExceptionAndSetStatus() {
        ChatRequest request = ChatRequest.builder().messages(new UserMessage("hi")).build();
        Map<Object, Object> attributes = new HashMap<>();

        try (TracedSpan ignored = helper.beginTrace("chat", Map.of())) {
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
            listener.onError(new ChatModelErrorContext(
                    new IllegalStateException("LLM down"), request, ModelProvider.OPEN_AI, attributes));
        }

        SpanData span = generationChatSpan();

        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("ERROR");
        assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    }

    @Test
    void skipsWorkWhenTracingIsOff() {
        LangfuseSpanHelper off = LangfuseSpanHelper.disabled();
        LangfuseChatModelListener quiet = new LangfuseChatModelListener(off);
        ChatRequest request = ChatRequest.builder().messages(new UserMessage("hi")).build();
        Map<Object, Object> attributes = new HashMap<>();

        assertThatNoException().isThrownBy(() -> {
            quiet.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
            quiet.onResponse(new ChatModelResponseContext(
                    ChatResponse.builder().aiMessage(new AiMessage("ok")).build(),
                    request, ModelProvider.OPEN_AI, attributes));
        });
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void responseWithNullTokenUsageIsGracefullyHandled() {
        ChatRequest request = ChatRequest.builder().messages(new UserMessage("hi")).build();
        Map<Object, Object> attributes = new HashMap<>();

        try (TracedSpan ignored = helper.beginTrace("chat", Map.of())) {
            listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OPEN_AI, attributes));
            listener.onResponse(new ChatModelResponseContext(
                    ChatResponse.builder().aiMessage(new AiMessage("ok")).build(),
                    request, ModelProvider.OPEN_AI, attributes));
        }

        SpanData span = generationChatSpan();
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("output.value"))).isEqualTo("ok");
    }

    private SpanData generationChatSpan() {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("generation-chat"))
                .findFirst().orElseThrow();
    }
}