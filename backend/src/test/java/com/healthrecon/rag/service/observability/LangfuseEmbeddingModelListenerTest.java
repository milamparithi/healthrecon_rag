package com.healthrecon.rag.service.observability;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelErrorContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelRequestContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelResponseContext;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.model.output.Response;
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

class LangfuseEmbeddingModelListenerTest {

    private InMemorySpanExporter exporter;
    private LangfuseSpanHelper helper;
    private LangfuseEmbeddingModelListener listener;
    private EmbeddingModel mockEmbeddingModel;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter))
                .build();
        helper = new LangfuseSpanHelper(provider.get("test"), 1.0, true);
        listener = new LangfuseEmbeddingModelListener(helper);
        mockEmbeddingModel = org.mockito.Mockito.mock(EmbeddingModel.class);
    }

    @AfterEach
    void tearDown() {
        exporter.reset();
    }

    @Test
    void recordsRequestAndResponseAsGenerationSpan() {
        EmbeddingRequest request = EmbeddingRequest.builder()
                .modelName("text-embedding-3-small")
                .textSegments(List.of(TextSegment.from("portion evaluation")))
                .build();
        Map<Object, Object> attributes = new HashMap<>();

        try (TracedSpan ignored = helper.beginTrace("chat", Map.of())) {
            listener.onRequest(EmbeddingModelRequestContext.builder()
                    .embeddingRequest(request)
                    .embeddingModel(mockEmbeddingModel)
                    .textSegments(List.of(
                            TextSegment.from("portion"),
                            TextSegment.from("evaluation")))
                    .attributes(attributes)
                    .build());
            listener.onResponse(EmbeddingModelResponseContext.builder()
                    .embeddingRequest(request)
                    .embeddingModel(mockEmbeddingModel)
                    .response(Response.from(List.of(
                            new Embedding(new float[]{0.1f, 0.2f}))))
                    .textSegments(List.of(TextSegment.from("portion"), TextSegment.from("evaluation")))
                    .attributes(attributes)
                    .build());
        }

        SpanData span = generationEmbeddingSpan();

        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")))
                .isEqualTo("langchain4j");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")))
                .isEqualTo("text-embedding-3-small");
        assertThat(span.getAttributes().get(AttributeKey.longKey("rag.embedding.inputs")))
                .isEqualTo((long) "portion".length() + "evaluation".length());
        assertThat(span.getAttributes().get(AttributeKey.longKey("rag.embedding.outputs")))
                .isEqualTo(1L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("INFO");
    }

    @Test
    void errorEventsRecordExceptionAndSetStatus() {
        EmbeddingRequest request = EmbeddingRequest.builder().textSegments(List.of(TextSegment.from("hi"))).build();
        Map<Object, Object> attributes = new HashMap<>();

        try (TracedSpan ignored = helper.beginTrace("chat", Map.of())) {
            listener.onRequest(EmbeddingModelRequestContext.builder()
                    .embeddingRequest(request)
                    .embeddingModel(mockEmbeddingModel)
                    .textSegments(List.of(TextSegment.from("hi")))
                    .attributes(attributes)
                    .build());
            listener.onError(EmbeddingModelErrorContext.builder()
                    .embeddingRequest(request)
                    .embeddingModel(mockEmbeddingModel)
                    .error(new IllegalStateException("model overloaded"))
                    .textSegments(List.of(TextSegment.from("hi")))
                    .attributes(attributes)
                    .build());
        }

        SpanData span = generationEmbeddingSpan();

        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.level")))
                .isEqualTo("ERROR");
        assertThat(span.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    }

    @Test
    void skipsWorkWhenTracingIsOff() {
        LangfuseSpanHelper off = LangfuseSpanHelper.disabled();
        LangfuseEmbeddingModelListener quiet = new LangfuseEmbeddingModelListener(off);
        EmbeddingRequest request = EmbeddingRequest.builder().textSegments(List.of(TextSegment.from("hi"))).build();
        Map<Object, Object> attributes = new HashMap<>();

        quiet.onRequest(EmbeddingModelRequestContext.builder()
                .embeddingRequest(request)
                .embeddingModel(mockEmbeddingModel)
                .textSegments(List.of(TextSegment.from("hi")))
                .attributes(attributes)
                .build());
        quiet.onResponse(EmbeddingModelResponseContext.builder()
                .embeddingRequest(request)
                .embeddingModel(mockEmbeddingModel)
                .response(Response.from(List.of(new Embedding(new float[]{1f}))))
                .textSegments(List.of(TextSegment.from("hi")))
                .attributes(attributes)
                .build());

        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    private SpanData generationEmbeddingSpan() {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("generation-embedding"))
                .findFirst().orElseThrow();
    }
}