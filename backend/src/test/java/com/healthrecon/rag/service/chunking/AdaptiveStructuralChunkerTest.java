package com.healthrecon.rag.service.chunking;

import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.config.SearchProperties;
import com.healthrecon.rag.service.extraction.MarkdownStructureExtractor;
import com.healthrecon.rag.service.extraction.StructuralDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveStructuralChunkerTest {

    private final UUID docId = UUID.randomUUID();

    private AdaptiveStructuralChunker chunker(int chunkSize, int overlap, int maxChunks) {
        RagProperties properties = new RagProperties(5, 10, "system", null, null,
                new SearchProperties(true, 30, "rrf", new SearchProperties.Rerank(false, "none")),
                null, new RagProperties.Chunking("adaptive", chunkSize, overlap, maxChunks));
        return new AdaptiveStructuralChunker(properties, new FixedCharChunker(properties));
    }

    @Test
    void smallDocumentBecomesSingleChunkWithHeadingChain() {
        MarkdownStructureExtractor extractor = new MarkdownStructureExtractor();
        String md = "# Title\nIntro on title.\n## Section\nBody text.";
        StructuralDocument doc = extractor.extract(md.getBytes(StandardCharsets.UTF_8));

        List<TextChunk> chunks = chunker(10_000, 100, 2000).chunk(docId, doc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Title > Section")
                .contains("Body text.");
    }

    @Test
    void textIsGroupedAroundSections() {
        MarkdownStructureExtractor extractor = new MarkdownStructureExtractor();
        String md = "# Lead\n%s\n## A\n%s\n## B\n%s";
        String body = "paragraph here ".repeat(120);
        StructuralDocument doc = extractor.extract(md.formatted(body, body, body).getBytes(StandardCharsets.UTF_8));

        List<TextChunk> chunks = chunker(2000, 100, 2000).chunk(docId, doc);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().map(TextChunk::text).flatMap(t -> List.of(t).stream()).mapToInt(String::length).max().orElse(0))
                .isLessThanOrEqualTo(2000 + 250);
        assertThat(chunks.stream().map(TextChunk::text).anyMatch(t -> t.contains("Lead > A")))
                .isTrue();
    }

    @Test
    void oversizedSectionIsCharSplit() {
        MarkdownStructureExtractor extractor = new MarkdownStructureExtractor();
        String md = "# Big\n" + "x".repeat(5000);
        StructuralDocument doc = extractor.extract(md.getBytes(StandardCharsets.UTF_8));

        List<TextChunk> chunks = chunker(1000, 100, 2000).chunk(docId, doc);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.stream().allMatch(c -> c.text().length() <= 1000)).isTrue();
    }

    @Test
    void respectsMaxChunksPerDocument() {
        MarkdownStructureExtractor extractor = new MarkdownStructureExtractor();
        String md = "# Big\n" + "y".repeat(10_000);
        StructuralDocument doc = extractor.extract(md.getBytes(StandardCharsets.UTF_8));

        List<TextChunk> chunks = chunker(100, 0, 5).chunk(docId, doc);

        assertThat(chunks.size()).isLessThanOrEqualTo(5);
    }

    @Test
    void headingChainIsCapped() {
        MarkdownStructureExtractor extractor = new MarkdownStructureExtractor();
        String longHeading = "# " + "abc ".repeat(100);
        String md = longHeading + "\nBody";
        StructuralDocument doc = extractor.extract(md.getBytes(StandardCharsets.UTF_8));

        List<TextChunk> chunks = chunker(10_000, 100, 2000).chunk(docId, doc);

        assertThat(chunks.get(0).section().length()).isLessThanOrEqualTo(AdaptiveStructuralChunker.MAX_CHAIN_CHARS + 1);
    }
}