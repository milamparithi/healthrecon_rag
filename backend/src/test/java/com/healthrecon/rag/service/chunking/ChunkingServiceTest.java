package com.healthrecon.rag.service.chunking;

import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.config.SearchProperties;
import com.healthrecon.rag.service.extraction.Section;
import com.healthrecon.rag.service.extraction.StructuralDocument;
import com.healthrecon.rag.service.extraction.StructureExtractionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkingServiceTest {

    private final UUID docId = UUID.randomUUID();

    @Test
    void fixedModeSplitsTheExtractedText() {
        ChunkingService service = service("fixed", null);

        List<TextChunk> chunks = service.chunk(docId, "notes.txt",
                "# heading\nignored".getBytes(), "plain extracted text ".repeat(40));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().map(TextChunk::text).allMatch(t -> t.contains("plain extracted text"))).isTrue();
    }

    @Test
    void adaptiveModeUsesStructureForTextFormats() {
        StructureExtractionService extractionService = mock(StructureExtractionService.class);
        when(extractionService.extract(any(), any(), any())).thenReturn(new StructuralDocument(List.of(
                new Section(1, "Introduction", null),
                new Section(null, null, "The animals are pets."))));
        ChunkingService service = service("adaptive", extractionService);

        List<TextChunk> chunks = service.chunk(docId, "study.md", "# Introduction\n".getBytes(), "ignored");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Introduction").contains("The animals are pets.");
        verify(extractionService).extract(any(), any(), any());
    }

    @Test
    void binaryFormatFallsBackToPlainTextInAdaptiveMode() {
        StructureExtractionService extractionService = mock(StructureExtractionService.class);
        when(extractionService.extract(any(), any(), any()))
                .thenReturn(new StructuralDocument(List.of(new Section(null, null, "binary text"))));
        ChunkingService service = service("adaptive", extractionService);

        List<TextChunk> chunks = service.chunk(docId, "report.docx", new byte[]{1, 2, 3}, "binary text");

        assertThat(chunks.get(0).text()).contains("binary text");
    }

    @Test
    void emptyContentYieldsNoChunks() {
        StructureExtractionService extractionService = mock(StructureExtractionService.class);
        when(extractionService.extract(any(), any(), any()))
                .thenReturn(new StructuralDocument(List.of(new Section(null, null, "  \n "))));

        assertThat(service("adaptive", extractionService).chunk(docId, "empty.txt", new byte[0], "  ")).isEmpty();
    }

    private ChunkingService service(String mode, StructureExtractionService extractionService) {
        RagProperties properties = new RagProperties(
                5, 10, "system", null, null,
                new SearchProperties(true, 30, "rrf", new SearchProperties.Rerank(false, "none")),
                null, new RagProperties.Chunking(mode, 2000, 100, 2000));
        FixedCharChunker fixed = new FixedCharChunker(properties);
        return new ChunkingService(properties, extractionService,
                new AdaptiveStructuralChunker(properties, fixed), fixed);
    }
}