package com.healthrecon.rag.service.observability;

/**
 * Thread-local sampling state for the current chat turn. Set when a sampled
 * root span opens and cleared when it closes; the model listeners consult it so
 * they never create spans for traces that were sampled out. Everything is
 * single-threaded per request, so a plain {@link ThreadLocal} is exact.
 */
final class LangfuseTracingContext {

    private static final ThreadLocal<Boolean> SAMPLED = new ThreadLocal<>();

    private LangfuseTracingContext() {
    }

    static void begin() {
        SAMPLED.set(true);
    }

    static boolean sampled() {
        return Boolean.TRUE.equals(SAMPLED.get());
    }

    static void end() {
        SAMPLED.remove();
    }
}