package com.healthrecon.rag.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfusePropertiesTest {

    @Test
    void bindsDefaultsToLangfuseCloud() {
        LangfuseProperties props = new LangfuseProperties(false, null, null, null, null, null, 2.0);

        assertThat(props.enabled()).isFalse();
        assertThat(props.configured()).isFalse();
        assertThat(props.hostOrDefault()).isEqualTo("https://cloud.langfuse.com");
        assertThat(props.environmentOrDefault()).isEqualTo("dev");
        assertThat(props.sampleRatio()).isEqualTo(1.0);
    }

    @Test
    void clampsSampleRatioToUnitRange() {
        assertThat(new LangfuseProperties(true, null, "k", "s", null, "prod", -1).sampleRatio()).isZero();
        assertThat(new LangfuseProperties(true, null, "k", "s", null, "prod", 7).sampleRatio()).isEqualTo(1.0);
        assertThat(new LangfuseProperties(true, null, "k", "s", null, "prod", 0.25).sampleRatio()).isEqualTo(0.25);
    }

    @Test
    void recognizesConfiguredKeysAndOverrideHost() {
        LangfuseProperties props = new LangfuseProperties(true,
                "https://us.cloud.langfuse.com", "pk", "sk", "v1.2", "prod", 1.0);

        assertThat(props.configured()).isTrue();
        assertThat(props.hostOrDefault()).isEqualTo("https://us.cloud.langfuse.com");
        assertThat(props.environmentOrDefault()).isEqualTo("prod");
        assertThat(props.release()).isEqualTo("v1.2");
    }

    @Test
    void reportsUnconfiguredWhenOnlyPublicKeyIsSet() {
        LangfuseProperties props = new LangfuseProperties(true, null, "pk", "", null, "prod", 1.0);

        assertThat(props.configured()).isFalse();
    }

    @Test
    void disabledStaticFactoryKeepsTracingOff() {
        LangfuseProperties props = LangfuseProperties.disabled();

        assertThat(props.enabled()).isFalse();
    }
}