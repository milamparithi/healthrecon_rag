package com.healthrecon.rag.service;

import com.healthrecon.rag.config.QuotaProperties;
import com.healthrecon.rag.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class QuotaService {

    private final DocumentRepository documentRepository;
    private final QuotaProperties properties;

    public QuotaService(DocumentRepository documentRepository, QuotaProperties properties) {
        this.documentRepository = documentRepository;
        this.properties = properties;
    }

    public long getUsedBytes(UUID ownerId) {
        return documentRepository.sumContentLengthByOwner(ownerId);
    }

    public long getLimitBytes() {
        return properties.maxUploadBytesPerUser();
    }

    public QuotaSnapshot snapshot(UUID ownerId) {
        return new QuotaSnapshot(getUsedBytes(ownerId), getLimitBytes());
    }

    public record QuotaSnapshot(long usedBytes, long limitBytes) {
        public long remainingBytes() {
            return limitBytes - usedBytes;
        }
    }
}