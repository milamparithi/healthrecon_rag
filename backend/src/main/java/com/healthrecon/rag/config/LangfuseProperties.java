package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse Cloud observability settings. Traces are exported over OpenTelemetry
 * OTLP/HTTP and mirrored into Langfuse; everything is off by default so the
 * chat pipeline is untouched unless explicitly enabled.
 *
 * <p>The values are normally provided by docker-compose from the .env file
 * ({@code LANGFUSE_*}). Running the backend outside docker-compose means
 * exporting the same variables in the process environment.
 */
@ConfigurationProperties(prefix = "app.langfuse")
public record LangfuseProperties(
        boolean enabled,
        String host,
        String publicKey,
        String secretKey,
        String release,
        String environment,
        double sampleRatio) {

    /** Langfuse EU cloud; the default for most self-managed setups. */
    private static final String DEFAULT_HOST = "https://cloud.langfuse.com";

    @Override
    public double sampleRatio() {
        return Math.max(0.0, Math.min(1.0, sampleRatio));
    }

    public String hostOrDefault() {
        return blank(host) ? DEFAULT_HOST : host;
    }

    public String environmentOrDefault() {
        return blank(environment) ? "dev" : environment;
    }

    /** True when both signing keys are present so a real exporter can be built. */
    public boolean configured() {
        return !blank(publicKey) && !blank(secretKey);
    }

    /** Properties that keep tracing disabled; handy for tests. */
    public static LangfuseProperties disabled() {
        return new LangfuseProperties(false, DEFAULT_HOST, null, null, null, "dev", 1.0);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}