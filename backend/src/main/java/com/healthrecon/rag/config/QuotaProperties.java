package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.quota")
public record QuotaProperties(long maxUploadBytesPerUser) {
}