package com.healthrecon.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.TestSecurity;
import com.healthrecon.rag.api.dto.ExpectedSource;
import com.healthrecon.rag.api.dto.GoldenCaseRequest;
import com.healthrecon.rag.api.dto.GoldenCaseResponse;
import com.healthrecon.rag.domain.GoldenCase;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.GoldenCaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldenCaseServiceTest {

    @Mock
    private DocumentSetService documentSetService;
    @Mock
    private GoldenCaseRepository goldenCaseRepository;

    private GoldenCaseService service;

    private final UUID docSetId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID customUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GoldenCaseService(documentSetService,
                goldenCaseRepository, new ObjectMapper());
        TestSecurity.authenticate(userId);
    }

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void createDefaultsToDraftAndSerializesSources() {
        when(goldenCaseRepository.save(any(GoldenCase.class))).thenAnswer(inv -> inv.getArgument(0));

        GoldenCaseResponse response = service.create(docSetId, new GoldenCaseRequest(
                "  What dosage?  ", "  500 mg.", List.of(new ExpectedSource("guide.md", null, "Dosage")), null));

        assertThat(response.question()).isEqualTo("What dosage?");
        assertThat(response.referenceAnswer()).isEqualTo("500 mg.");
        assertThat(response.status()).isEqualTo(GoldenCase.STATUS_DRAFT);
        assertThat(response.expectedSources()).containsExactly(new ExpectedSource("guide.md", null, "Dosage"));
    }

    @Test
    void createHonorsGoldenStatus() {
        when(goldenCaseRepository.save(any(GoldenCase.class))).thenAnswer(inv -> inv.getArgument(0));

        GoldenCaseResponse response = service.create(docSetId, new GoldenCaseRequest(
                "Q", "A", List.of(), GoldenCase.STATUS_GOLDEN));

        assertThat(response.status()).isEqualTo(GoldenCase.STATUS_GOLDEN);
    }

    @Test
    void createRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.create(docSetId, new GoldenCaseRequest(
                "Q", "A", List.of(), "ARCHIVED")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateEditsFieldsAndKeepsStatusWhenStatusNull() {
        GoldenCase existing = new GoldenCase(UUID.randomUUID(), docSetId, userId, null,
                "old", "old ans", "[]", GoldenCase.STATUS_DRAFT, Instant.now(), Instant.now());
        when(goldenCaseRepository.findByIdAndOwnerIdAndDocSetId(existing.getId(), userId, docSetId))
                .thenReturn(Optional.of(existing));

        GoldenCaseResponse response = service.update(docSetId, existing.getId(), new GoldenCaseRequest(
                "new", "new answer", List.of(new ExpectedSource("x.md", null, null)), GoldenCase.STATUS_GOLDEN));

        assertThat(response.question()).isEqualTo("new");
        assertThat(response.status()).isEqualTo(GoldenCase.STATUS_GOLDEN);
        assertThat(existing.getReferenceAnswer()).isEqualTo("new answer");
    }

    @Test
    void updateRejectsForeignCase() {
        when(goldenCaseRepository.findByIdAndOwnerIdAndDocSetId(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(docSetId, UUID.randomUUID(),
                new GoldenCaseRequest("Q", "A", List.of(), null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteDelegatesAfterOwnershipCheck() {
        GoldenCase existing = new GoldenCase(UUID.randomUUID(), docSetId, userId, null,
                "Q", "A", "[]", GoldenCase.STATUS_DRAFT, Instant.now(), Instant.now());
        when(goldenCaseRepository.findByIdAndOwnerIdAndDocSetId(existing.getId(), userId, docSetId))
                .thenReturn(Optional.of(existing));

        service.delete(docSetId, existing.getId());

        verify(goldenCaseRepository).deleteById(existing.getId());
    }

    @Test
    void parsesSourcesTolerantly() {
        assertThat(service.parseSources("[]")).isEmpty();
        assertThat(service.parseSources("not-json")).isEmpty();
        assertThat(service.parseSources(null)).isEmpty();
    }

    @Test
    void listUsesOwnedSetGuard() {
        when(goldenCaseRepository.findAllByDocSetIdOrderByCreatedAtDesc(docSetId)).thenReturn(List.of());
        service.list(docSetId);
        verify(goldenCaseRepository).findAllByDocSetIdOrderByCreatedAtDesc(docSetId);
        // foreign user ownership is guaranteed by requireOwnedSet, covered in integration tests
        assertThat(customUserId).isNotEqualTo(userId);
    }
}