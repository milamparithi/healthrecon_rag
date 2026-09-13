package com.healthrecon.rag.service.chunking;

import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.service.extraction.StructureExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FixedCharChunkerTest {

    private final UUID docId = UUID.randomUUID();

    private FixedCharChunker chunker(int size, int overlap) {
        return new FixedCharChunker(new RagProperties(5, 10, "system",
                new RagProperties.Chunking("fixed", size, overlap, 2000)));
    }

    @Test
    void shortTextYieldsSingleChunk() {
        List<TextChunk> chunks = chunker(100, 10).chunk(docId, StructureExtractor.fromPlainText("hello world"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("hello world");
        assertThat(chunks.get(0).section()).isEmpty();
    }

    @Test
    void longTextIsSplitWithOverlap() {
        String text = "abcdefghij".repeat(30);
        List<TextChunk> chunks = chunker(50, 5).chunk(docId, StructureExtractor.fromPlainText(text));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).text().length()).isLessThanOrEqualTo(50);
        assertThat(chunks.get(1).text()).isEqualTo(text.substring(45, 95).strip());
    }

    @Test
    void blankTextYieldsNoChunks() {
        assertThat(chunker(100, 10).chunk(docId, StructureExtractor.fromPlainText("  \n "))).isEmpty();
    }

    @Test
    void overlapLargerThanSizeIsClamped() {
        String text = "z".repeat(200);
        List<TextChunk> chunks = chunker(50, 500).chunk(docId, StructureExtractor.fromPlainText(text));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.stream().mapToInt(c -> c.text().length()).max().orElse(0)).isLessThanOrEqualTo(50);
    }
}