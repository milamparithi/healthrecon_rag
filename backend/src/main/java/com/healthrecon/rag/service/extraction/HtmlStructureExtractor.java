package com.healthrecon.rag.service.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the heading hierarchy from an HTML document. Script/style content is
 * ignored so heading tags keep their meaning. Sections without heading tags
 * yield a single text run.
 */
public class HtmlStructureExtractor {

    private static final Pattern SLOT_SCRIPT_STYLE = Pattern.compile(
            "<(script|style|head)\\b[^>]*>.*?</\\1\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_TAG = Pattern.compile(
            "<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public StructuralDocument extract(byte[] content) {
        String raw = StructureExtractor.utf8(content);
        String normalized = SLOT_SCRIPT_STYLE.matcher(raw).replaceAll(" ");

        List<Section> sections = new ArrayList<>();
        Matcher heading = HEADING_TAG.matcher(normalized);
        int cursor = 0;
        while (heading.find()) {
            String body = stripTags(normalized.substring(cursor, heading.start()));
            emitText(sections, body);
            String headingText = stripTags(heading.group(2));
            if (!headingText.isEmpty()) {
                sections.add(new Section(Integer.parseInt(heading.group(1)), headingText, null));
            }
            cursor = heading.end();
        }
        emitText(sections, stripTags(normalized.substring(cursor)));
        if (sections.isEmpty()) {
            sections.add(new Section(null, null, ""));
        }
        return new StructuralDocument(sections);
    }

    private static void emitText(List<Section> sections, String text) {
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").strip();
        if (!collapsed.isEmpty()) {
            sections.add(new Section(null, null, collapsed));
        }
    }

    static String stripTags(String html) {
        String withoutTags = TAG.matcher(html).replaceAll(" ");
        return WHITESPACE.matcher(withoutTags).replaceAll(" ").strip();
    }
}