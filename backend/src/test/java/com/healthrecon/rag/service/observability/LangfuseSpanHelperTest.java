package com.healthrecon.rag.service.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
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

class LangfuseSpanHelperTest {

    private InMemorySpanExporter exporter;
    private Tracer tracer;
    private LangfuseSpanHelper helper;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter))
                .build();
        tracer = provider.get("test");
        helper = new LangfuseSpanHelper(tracer, 1.0, true);
    }

    @AfterEach
    void tearDown() {
        exporter.reset();
    }

    @Test
    void nestedSpansAreParentedUnderTheRoot() {
        try (TracedSpan root = helper.beginTrace("chat",
                Map.of("input.value", "hello world", "rag.document_set_id", "d1"))) {
            assertThat(root.isActive()).isTrue();
            assertThat(helper.shouldEmit()).isTrue();
            try (TracedSpan child = helper.startSpan("retriever")) {
                assertThat(child.isActive()).isTrue();
            }
        }
        assertThat(helper.shouldEmit()).isFalse();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData child = spans.stream()
                .filter(s -> s.getName().equals("retriever"))
                .findFirst().orElseThrow();
        SpanData root = spans.stream()
                .filter(s -> s.getName().equals("chat"))
                .findFirst().orElseThrow();

        assertThat(child.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(root.getAttributes().get(AttributeKey.stringKey("input.value"))).isEqualTo("hello world");
        assertThat(root.getAttributes().get(AttributeKey.stringKey("rag.document_set_id"))).isEqualTo("d1");
    }

    @Test
    void disabledHelperRecordsNothing() {
        LangfuseSpanHelper off = LangfuseSpanHelper.disabled();
        try (TracedSpan root = off.beginTrace("chat", Map.of("input.value", "hi"))) {
            assertThat(root.isActive()).isFalse();
            assertThat(off.shouldEmit()).isFalse();
        }
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void unsampledTraceRecordsNothing() {
        LangfuseSpanHelper sampledOff = new LangfuseSpanHelper(tracer, 0.0, true);
        try (TracedSpan root = sampledOff.beginTrace("chat", Map.of())) {
            assertThat(root.isActive()).isFalse();
            assertThat(sampledOff.shouldEmit()).isFalse();
        }
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void failMarksSpanAsErrorAndAttachesMetadata() {
        try (TracedSpan root = helper.beginTrace("chat", Map.of())) {
            root.setMetadata(Map.of(
                    "eval.verdict", "grounded",
                    "eval.coverage", 0.8,
                    "eval.refused", false,
                    "note", "ok"));
            root.fail(new IllegalStateException("boom"));
        }

        SpanData root = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("chat"))
                .findFirst().orElseThrow();

        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(root.getEvents()).anyMatch(event -> event.getName().equals("exception"));
        assertThat(root.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.metadata.eval.verdict")))
                .isEqualTo("grounded");
        assertThat(root.getAttributes().get(
                AttributeKey.doubleKey("langfuse.observation.metadata.eval.coverage")))
                .isEqualTo(0.8);
        assertThat(root.getAttributes().get(
                AttributeKey.booleanKey("langfuse.observation.metadata.eval.refused")))
                .isFalse();
        assertThat(root.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.metadata.note")))
                .isEqualTo("ok");
    }

    @Test
    void clearingTheTraceEndsSamplingContext() {
        try (TracedSpan ignored = helper.beginTrace("chat", Map.of())) {
            assertThat(helper.shouldEmit()).isTrue();
        }
        assertThat(helper.shouldEmit()).isFalse();
    }

    @Test
    void attributesWithNullValuesAreSkipped() {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("input.value", "q");
        attrs.put("release", null);
        try (TracedSpan root = helper.beginTrace("chat", attrs)) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("empty", null);
            metadata.put("keep", "yes");
            root.setMetadata(metadata);
        }

        SpanData root = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("chat"))
                .findFirst().orElseThrow();

        assertThat(root.getAttributes().get(AttributeKey.stringKey("input.value"))).isEqualTo("q");
        assertThat(root.getAttributes().get(AttributeKey.stringKey("release"))).isNull();
        assertThat(root.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.metadata.empty"))).isEqualTo("");
        assertThat(root.getAttributes().get(
                AttributeKey.stringKey("langfuse.observation.metadata.keep"))).isEqualTo("yes");
    }
}