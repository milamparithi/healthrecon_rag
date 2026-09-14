package com.healthrecon.rag.service.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Creates OpenTelemetry spans for one chat turn. The root span
 * ({@link #beginTrace}) carries the trace-level identifiers, opens the OTel
 * context and decides sampling; nested {@link #startSpan} calls parent
 * themselves to whatever is currently active. Everything fails closed: when
 * tracing is disabled, unconfigured or the trace is sampled out, all methods
 * return no-op spans and the pipeline is untouched.
 */
public class LangfuseSpanHelper {

    private static final String TRACER_NAME = "com.healthrecon.rag.observability";

    private final Tracer tracer;
    private final double sampleRatio;
    private final boolean enabled;

    public LangfuseSpanHelper(Tracer tracer, double sampleRatio, boolean enabled) {
        this.tracer = tracer;
        this.sampleRatio = sampleRatio;
        this.enabled = enabled;
    }

    /** Helper that never records anything; used when tracing is turned off. */
    public static LangfuseSpanHelper disabled() {
        return new LangfuseSpanHelper(OpenTelemetry.noop().getTracer(TRACER_NAME), 1.0, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Tracer getTracer() {
        return tracer;
    }

    /** True when a sampled trace is currently being recorded on this thread. */
    public boolean shouldEmit() {
        return enabled && LangfuseTracingContext.sampled();
    }

    /**
     * Opens the root span of a chat turn. When the trace is not sampled, the
     * root and every nested span become no-ops.
     */
    public TracedSpan beginTrace(String traceName, Map<String, String> attributes) {
        if (!enabled) {
            return TracedSpan.noop();
        }
        boolean sampled = sampleRatio >= 1.0
                || ThreadLocalRandom.current().nextDouble() < sampleRatio;
        if (!sampled) {
            return TracedSpan.noop();
        }
        LangfuseTracingContext.begin();
        Span span = tracer.spanBuilder(traceName).startSpan();
        applyAttributes(span, attributes);
        Scope scope = span.makeCurrent();
        return TracedSpan.active(span, scope, true);
    }

    /** Opens a child span under the currently active span; no-op outside a sampled trace. */
    public TracedSpan startSpan(String spanName) {
        return startSpan(spanName, Map.of());
    }

    /** Opens a child span under the currently active span; no-op outside a sampled trace. */
    public TracedSpan startSpan(String spanName, Map<String, String> attributes) {
        if (!shouldEmit()) {
            return TracedSpan.noop();
        }
        Span span = tracer.spanBuilder(spanName).startSpan();
        applyAttributes(span, attributes);
        Scope scope = span.makeCurrent();
        return TracedSpan.active(span, scope, false);
    }

    private static void applyAttributes(Span span, Map<String, String> attributes) {
        if (attributes == null) {
            return;
        }
        attributes.forEach((key, value) -> {
            if (value != null) {
                span.setAttribute(key, value);
            }
        });
    }
}