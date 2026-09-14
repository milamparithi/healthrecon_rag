package com.healthrecon.rag.service.observability;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.listener.EmbeddingModelErrorContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.listener.EmbeddingModelRequestContext;
import dev.langchain4j.model.embedding.listener.EmbeddingModelResponseContext;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.output.Response;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Records every embedding-model call as a Langfuse "generation" span
 * ({@code generation-embedding}). The raw vectors are never exported — only the
 * input character count and the output cardinality — so the payload stays
 * small. Tracing failures are logged at debug and never break the embedding
 * call.
 */
@Component
public class LangfuseEmbeddingModelListener implements EmbeddingModelListener {

    private static final Logger log = LoggerFactory.getLogger(LangfuseEmbeddingModelListener.class);
    private static final String SPAN_KEY = "com.healthrecon.rag.langfuse.embedding-span";

    private final LangfuseSpanHelper helper;

    public LangfuseEmbeddingModelListener(LangfuseSpanHelper helper) {
        this.helper = helper;
    }

    @Override
    public void onRequest(EmbeddingModelRequestContext context) {
        if (!helper.shouldEmit()) {
            return;
        }
        try {
            Span span = helper.getTracer().spanBuilder("generation-embedding").startSpan();
            span.setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j");
            EmbeddingRequest request = context.embeddingRequest();
            if (request != null && request.modelName() != null) {
                span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, request.modelName());
            }
            span.setAttribute(LangfuseAttributes.RAG_EMBEDDING_INPUTS, (long) inputChars(context.textSegments()));
            Scope scope = span.makeCurrent();
            context.attributes().put(SPAN_KEY, new ActiveModelSpan(span, scope));
        } catch (RuntimeException failure) {
            log.debug("Langfuse embedding listener ignored a request event: {}", failure.getMessage());
        }
    }

    @Override
    public void onResponse(EmbeddingModelResponseContext context) {
        try {
            if (!(context.attributes().get(SPAN_KEY) instanceof ActiveModelSpan active)) {
                return;
            }
            int outputs = embeddingCount(context);
            if (outputs >= 0) {
                active.span().setAttribute(LangfuseAttributes.RAG_EMBEDDING_OUTPUTS, (long) outputs);
            }
            active.span().setAttribute(LangfuseAttributes.LANGFUSE_OBSERVATION_LEVEL, "INFO");
            active.close();
        } catch (RuntimeException failure) {
            log.debug("Langfuse embedding listener ignored a response event: {}", failure.getMessage());
        }
    }

    @Override
    public void onError(EmbeddingModelErrorContext context) {
        try {
            if (!(context.attributes().get(SPAN_KEY) instanceof ActiveModelSpan active)) {
                return;
            }
            active.span().setAttribute(LangfuseAttributes.LANGFUSE_OBSERVATION_LEVEL, "ERROR");
            active.span().recordException(context.error());
            active.span().setStatus(StatusCode.ERROR);
            active.close();
        } catch (RuntimeException failure) {
            log.debug("Langfuse embedding listener ignored an error event: {}", failure.getMessage());
        }
    }

    private static int inputChars(List<TextSegment> segments) {
        if (segments == null) {
            return 0;
        }
        return segments.stream()
                .mapToInt(segment -> segment.text() == null ? 0 : segment.text().length())
                .sum();
    }

    private static int embeddingCount(EmbeddingModelResponseContext context) {
        if (context.embeddingResponse() != null && context.embeddingResponse().embeddings() != null) {
            return context.embeddingResponse().embeddings().size();
        }
        Response<List<Embedding>> response = context.response();
        if (response != null && response.content() != null) {
            return response.content().size();
        }
        return -1;
    }

    private record ActiveModelSpan(Span span, Scope scope) {
        void close() {
            scope.close();
            span.end();
        }
    }
}