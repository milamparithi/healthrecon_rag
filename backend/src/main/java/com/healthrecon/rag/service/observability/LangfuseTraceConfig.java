package com.healthrecon.rag.service.observability;

import com.healthrecon.rag.config.LangfuseProperties;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Langfuse Cloud integration. Traces are exported over OTLP/HTTP to
 * {@code host + "/api/public/otel"} (Langfuse does not accept gRPC) using the
 * signed {@code public-key:secret-key} pair as HTTP Basic credentials. The
 * exporter is created only when {@code app.langfuse.enabled} is true and both
 * keys are set; in every other case the helper is a no-op, so the chat pipeline
 * pays nothing when observability is off. All failures degrade to no-op tracing
 * instead of failing the application.
 */
@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseTraceConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTraceConfig.class);

    private static final String INGESTION_VERSION = "4";
    private static final String TRACER_NAME = "com.healthrecon.rag.observability";

    @Bean
    public LangfuseSpanHelper langfuseSpanHelper(LangfuseProperties properties) {
        if (!properties.enabled()) {
            return LangfuseSpanHelper.disabled();
        }
        if (!properties.configured()) {
            log.warn("app.langfuse.enabled is true but LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY are not set; "
                    + "chat traces will be dropped. Configure both keys (optionally LANGFUSE_HOST) to enable Langfuse.");
            return LangfuseSpanHelper.disabled();
        }
        try {
            String credentials = Base64.getEncoder().encodeToString(
                    (properties.publicKey() + ":" + properties.secretKey()).getBytes(StandardCharsets.UTF_8));
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(properties.hostOrDefault() + "/api/public/otel")
                    .addHeader("Authorization", "Basic " + credentials)
                    .addHeader("x-langfuse-ingestion-version", INGESTION_VERSION)
                    .build();
            Attributes resourceAttributes = Attributes.builder()
                    .put("service.name", "healthrecon-rag-backend")
                    .put("deployment.environment", properties.environmentOrDefault())
                    .build();
            if (properties.release() != null && !properties.release().isBlank()) {
                resourceAttributes = resourceAttributes.toBuilder()
                        .put("deployment.release", properties.release())
                        .build();
            }
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(Resource.create(resourceAttributes))
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();
            Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close, "langfuse-trace-shutdown"));
            return new LangfuseSpanHelper(tracerProvider.get(TRACER_NAME), properties.sampleRatio(), true);
        } catch (RuntimeException failure) {
            log.warn("Failed to initialize the Langfuse OTLP exporter; chat traces will be dropped", failure);
            return LangfuseSpanHelper.disabled();
        }
    }
}