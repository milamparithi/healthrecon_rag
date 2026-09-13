package com.healthrecon.rag.service;

import com.healthrecon.rag.TestSecurity;
import com.healthrecon.rag.api.dto.UploadResult;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.DocumentSetStatus;
import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.repository.DocumentSetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    @Mock
    private DocumentSetRepository documentSetRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private QuotaService quotaService;

    @InjectMocks
    private DocumentUploadService uploadService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID docSetId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void uploadStoresDocumentAsPending() {
        TestSecurity.authenticate(ownerId);
        ownedSet();
        givenQuota(0L, 50L);
        when(documentRepository.existsByDocSetIdAndSha256(any(), any())).thenReturn(false);
        when(documentRepository.save(any(StoredDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        MultipartFile file = new MockMultipartFile("files", "notes.txt", "text/plain",
                "Hello world".getBytes());

        List<UploadResult> results = uploadService.upload(docSetId, new MultipartFile[]{file});

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("UPLOADED");
        verify(documentRepository).save(any(StoredDocument.class));
    }

    @Test
    void uploadSkipsDuplicateByContentHash() {
        TestSecurity.authenticate(ownerId);
        ownedSet();
        when(documentRepository.existsByDocSetIdAndSha256(any(), any())).thenReturn(true);

        MultipartFile file = new MockMultipartFile("files", "notes.txt", "text/plain",
                "Hello world".getBytes());

        List<UploadResult> results = uploadService.upload(docSetId, new MultipartFile[]{file});

        assertThat(results.get(0).status()).isEqualTo("DUPLICATE");
        verify(documentRepository, never()).save(any(StoredDocument.class));
    }

    @Test
    void uploadRejectsEmptyFiles() {
        TestSecurity.authenticate(ownerId);
        ownedSet();
        givenQuota(0L, 50L);

        MultipartFile file = new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0]);

        List<UploadResult> results = uploadService.upload(docSetId, new MultipartFile[]{file});

        assertThat(results.get(0).status()).isEqualTo("FAILED");
        verify(documentRepository, never()).save(any(StoredDocument.class));
    }

    @Test
    void uploadRejectsForeignDocumentSet() {
        TestSecurity.authenticate(UUID.randomUUID());
        when(documentSetRepository.findByIdAndOwnerId(any(), any())).thenReturn(Optional.empty());

        MultipartFile file = new MockMultipartFile("files", "notes.txt", "text/plain",
                "Hello world".getBytes());

        assertThatThrownBy(() -> uploadService.upload(docSetId, new MultipartFile[]{file}))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void uploadRejectsFileThatExceedsQuota() {
        TestSecurity.authenticate(ownerId);
        ownedSet();
        givenQuota(90L, 100L);
        when(documentRepository.existsByDocSetIdAndSha256(any(), any())).thenReturn(false);

        MultipartFile file = new MockMultipartFile("files", "big.txt", "text/plain", new byte[20]);

        List<UploadResult> results = uploadService.upload(docSetId, new MultipartFile[]{file});

        assertThat(results.get(0).status()).isEqualTo("FAILED");
        assertThat(results.get(0).message()).contains("quota");
        verify(documentRepository, never()).save(any(StoredDocument.class));
    }

    @Test
    void uploadAcceptsFilesThatFitInsideQuota() {
        TestSecurity.authenticate(ownerId);
        ownedSet();
        givenQuota(90L, 100L);
        when(documentRepository.existsByDocSetIdAndSha256(any(), any())).thenReturn(false);
        when(documentRepository.save(any(StoredDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        MultipartFile file = new MockMultipartFile("files", "small.txt", "text/plain", new byte[10]);

        List<UploadResult> results = uploadService.upload(docSetId, new MultipartFile[]{file});

        assertThat(results.get(0).status()).isEqualTo("UPLOADED");
        verify(documentRepository).save(any(StoredDocument.class));
    }

    @Test
    void uploadCountsQuotaAcrossFilesInBatch() {
        TestSecurity.authenticate(ownerId);
        ownedSet();
        givenQuota(0L, 30L);
        when(documentRepository.existsByDocSetIdAndSha256(any(), any())).thenReturn(false);
        when(documentRepository.save(any(StoredDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        MultipartFile a = new MockMultipartFile("files", "a.txt", "text/plain", new byte[20]);
        MultipartFile b = new MockMultipartFile("files", "b.txt", "text/plain", new byte[20]);

        List<UploadResult> results = uploadService.upload(docSetId, new MultipartFile[]{a, b});

        assertThat(results.get(0).status()).isEqualTo("UPLOADED");
        assertThat(results.get(1).status()).isEqualTo("FAILED");
        assertThat(results.get(1).message()).contains("quota");
        verify(documentRepository, times(1)).save(any(StoredDocument.class));
    }

    private void givenQuota(long used, long limit) {
        when(quotaService.getUsedBytes(ownerId)).thenReturn(used);
        when(quotaService.getLimitBytes()).thenReturn(limit);
    }

    private void ownedSet() {
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId))
                .thenReturn(Optional.of(new DocumentSet(docSetId, ownerId, "Docs", null,
                        DocumentSetStatus.UPLOADING, Instant.now(), Instant.now())));
    }
}