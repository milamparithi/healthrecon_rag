package com.healthrecon.rag.service.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.Map;

/**
 * Parenthesizes one OpenTelemetry span: makes it current on open and ends it
 * on close. The root span of a trace also clears the thread-local sampling
 * state when it closes. Every method is a no-op on a no-op span, so callers
 * never need null checks.
 */
public final class TracedSpan implements AutoCloseable {

    private static final TracedSpan NOOP = new TracedSpan(false);

    private final boolean active;
    private final Span span;
    private final Scope scope;
    private final boolean root;

    private TracedSpan(boolean active, Span span, Scope scope, boolean root) {
        this.active = active;
        this.span = span;
        this.scope = scope;
        this.root = root;
    }

    private TracedSpan(boolean active) {
        this(active, null, null, false);
    }

    static TracedSpan active(Span span, Scope scope, boolean root) {
        return new TracedSpan(true, span, scope, root);
    }

    public static TracedSpan noop() {
        return NOOP;
    }

    public boolean isActive() {
        return active;
    }

    /** Records the trace outcome as {@code langfuse.observation.metadata.*} attributes. */
    public TracedSpan setMetadata(Map<String, ?> metadata) {
        if (!active) {
            return this;
        }
        metadata.forEach((key, value) -> {
            String attribute = LangfuseAttributes.LANGFUSE_OBSERVATION_METADATA_PREFIX + key;
            setAttribute(attribute, value);
        });
        return this;
    }

    public TracedSpan fail(Throwable error) {
        if (!active) {
            return this;
        }
        span.recordException(error);
        span.setStatus(StatusCode.ERROR);
        return this;
    }

    @Override
    public void close() {
        if (!active) {
            return;
        }
        scope.close();
        span.end();
        if (root) {
            LangfuseTracingContext.end();
        }
    }

    private void setAttribute(String key, Object value) {
        if (value == null) {
            span.setAttribute(key, "");
        } else if (value instanceof Boolean bool) {
            span.setAttribute(key, bool);
        } else if (value instanceof Long l) {
            span.setAttribute(key, l.longValue());
        } else if (value instanceof Integer i) {
            span.setAttribute(key, i.longValue());
        } else if (value instanceof Double d) {
            span.setAttribute(key, d.doubleValue());
        } else if (value instanceof Float f) {
            span.setAttribute(key, f.doubleValue());
        } else {
            span.setAttribute(key, value.toString());
        }
    }
}