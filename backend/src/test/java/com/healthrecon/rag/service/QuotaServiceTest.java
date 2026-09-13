package com.healthrecon.rag.service;

import com.healthrecon.rag.config.QuotaProperties;
import com.healthrecon.rag.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private QuotaProperties properties;

    @Test
    void snapshotReportsUsedAndLimitAndRemaining() {
        UUID ownerId = UUID.randomUUID();
        when(documentRepository.sumContentLengthByOwner(ownerId)).thenReturn(1_500L);
        when(properties.maxUploadBytesPerUser()).thenReturn(2_000L);

        QuotaService service = new QuotaService(documentRepository, properties);
        QuotaService.QuotaSnapshot snapshot = service.snapshot(ownerId);

        assertThat(snapshot.usedBytes()).isEqualTo(1_500L);
        assertThat(snapshot.limitBytes()).isEqualTo(2_000L);
        assertThat(snapshot.remainingBytes()).isEqualTo(500L);
    }
}