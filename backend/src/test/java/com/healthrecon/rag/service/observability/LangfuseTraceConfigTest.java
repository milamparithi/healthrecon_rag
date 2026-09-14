package com.healthrecon.rag.service.observability;

import com.healthrecon.rag.config.LangfuseProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseTraceConfigTest {

    private final LangfuseTraceConfig config = new LangfuseTraceConfig();

    @Test
    void disabledWhenFeatureFlagIsOff() {
        LangfuseSpanHelper helper = config.langfuseSpanHelper(
                new LangfuseProperties(false, null, "pk", "sk", null, "dev", 1.0));

        assertThat(helper.isEnabled()).isFalse();
        assertThat(helper.getTracer().spanBuilder("test").startSpan().isRecording()).isFalse();
    }

    @Test
    void enabledWhenFeatureIsOnAndKeysAreConfigured() {
        LangfuseSpanHelper helper = config.langfuseSpanHelper(
                new LangfuseProperties(true, "https://example.invalid", "pk", "sk", "v1", "prod", 1.0));

        assertThat(helper.isEnabled()).isTrue();
        assertThat(helper.getTracer().spanBuilder("test").startSpan().isRecording()).isTrue();
    }

    @Test
    void droppedWhenKeysAreMissing() {
        LangfuseSpanHelper helper = config.langfuseSpanHelper(
                new LangfuseProperties(true, null, null, null, null, "dev", 1.0));

        assertThat(helper.isEnabled()).isFalse();
    }

    @Test
    void droppedWhenFeatureFlagIsOffEvenWithKeys() {
        LangfuseSpanHelper helper = config.langfuseSpanHelper(
                new LangfuseProperties(false, null, "pk", "sk", null, "dev", 1.0));

        assertThat(helper.isEnabled()).isFalse();
    }
}