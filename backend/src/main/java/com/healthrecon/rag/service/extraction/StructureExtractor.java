package com.healthrecon.rag.service.extraction;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Extracts a heading hierarchy from a document's raw content without re-parsing
 * it through Tika. Text-based formats (Markdown, HTML) preserve headings;
 * binary formats fall back to the already-extracted plain text.
 */
public interface StructureExtractor {

    /** Creates a single-section document from already-extracted plain text. */
    static StructuralDocument fromPlainText(String text) {
        return new StructuralDocument(List.of(new Section(null, null, text == null ? "" : text)));
    }

    /** Returns the document body as a UTF-8 string, tolerant of binary input. */
    static String utf8(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }
}