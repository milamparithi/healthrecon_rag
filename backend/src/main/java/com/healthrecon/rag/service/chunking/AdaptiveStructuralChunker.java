package com.healthrecon.rag.service.chunking;

import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.service.extraction.Section;
import com.healthrecon.rag.service.extraction.StructuralDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Structure-aware chunker. Headings are tracked into a chain (e.g.
 * "Protocol > Adverse Events") that gets prepended to the text of every chunk
 * owned by that part of the outline. Individual sections are never split unless
 * they exceed the chunk size on their own, and neighbouring text is aggregated
 * until the chunk size is met, so headings stay attached to their content.
 */
@Component
public class AdaptiveStructuralChunker implements ChunkingStrategy {

    static final int MAX_CHAIN_CHARS = 150;

    private final RagProperties.Chunking config;
    private final FixedCharChunker fallback;

    public AdaptiveStructuralChunker(RagProperties properties, FixedCharChunker fallback) {
        this.config = properties.chunking();
        this.fallback = fallback;
    }

    @Override
    public List<TextChunk> chunk(UUID docId, StructuralDocument document) {
        int size = Math.max(1, config.chunkSize());

        List<Unit> units = buildUnits(document);
        if (units.isEmpty()) {
            return List.of();
        }
        long total = units.stream().mapToLong(u -> u.text().length()).sum();
        if (total <= size) {
            String joined = String.join("\n\n", units.stream().map(Unit::text).toList());
            return List.of(new TextChunk(docId, 0, units.get(0).section(), joined));
        }
        if (units.size() == 1 && units.get(0).text().length() > size) {
            return fallback.split(docId, units.get(0).text());
        }

        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSection = units.get(0).section();
        int index = 0;
        for (Unit unit : units) {
            String candidate = unit.text();
            if (!current.isEmpty()) {
                candidate = current + "\n\n" + unit.text();
            }
            if (current.isEmpty() && unit.text().length() > size) {
                chunks.addAll(fallback.split(docId, unit.text()));
                index = chunks.size();
                if (index >= config.maxChunksPerDoc()) {
                    return chunks;
                }
                currentSection = unit.section();
                continue;
            }
            if (current.isEmpty() || candidate.length() <= size) {
                current.setLength(0);
                current.append(candidate);
            } else {
                chunks.add(new TextChunk(docId, index, currentSection, current.toString()));
                index++;
                if (index >= config.maxChunksPerDoc()) {
                    return chunks;
                }
                current.setLength(0);
                current.append(unit.text());
            }
            currentSection = unit.section();
        }
        if (!current.isEmpty() && index < config.maxChunksPerDoc()) {
            chunks.add(new TextChunk(docId, index, currentSection, current.toString()));
        }
        return chunks;
    }

    private List<Unit> buildUnits(StructuralDocument document) {
        Map<Integer, String> chain = new LinkedHashMap<>();
        List<Unit> units = new ArrayList<>();
        for (Section section : document.sections()) {
            if (section.isHeading()) {
                int level = Math.max(1, Math.min(6, section.level()));
                chain.keySet().removeIf(l -> l >= level);
                chain.put(level, clamp(section.heading()));
            } else {
                String text = section.text().strip();
                if (!text.isEmpty()) {
                    String chainText = String.join(" > ", chain.values());
                    String prefixed = chainText.isEmpty() ? text : chainText + "\n" + text;
                    units.add(new Unit(clamp(chainText), prefixed));
                }
            }
        }
        return units;
    }

    private static String clamp(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_CHAIN_CHARS
                ? value
                : value.substring(0, MAX_CHAIN_CHARS).stripTrailing() + "…";
    }

    private record Unit(String section, String text) {
    }
}