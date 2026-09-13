package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qdrant")
public record QdrantProperties(String host, int grpcPort, String apiKey) {
}