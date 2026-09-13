package com.healthrecon.rag.service.extraction;

import org.springframework.stereotype.Service;

/**
 * Routes structure extraction based on the file name. Markdown and HTML
 * preserve headings in raw content; every other format falls back to the plain
 * text produced by the Tika extraction stage.
 */
@Service
public class StructureExtractionService {

    private final MarkdownStructureExtractor markdown = new MarkdownStructureExtractor();
    private final HtmlStructureExtractor html = new HtmlStructureExtractor();

    public StructuralDocument extract(String filename, byte[] content, String fallbackText) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return markdown.extract(content);
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return html.extract(content);
        }
        return StructureExtractor.fromPlainText(fallbackText);
    }
}