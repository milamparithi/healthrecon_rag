package com.healthrecon.rag.service.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts ATX headings (# ...) and the body text between them from Markdown. */
public class MarkdownStructureExtractor {

    private static final Logger log = LoggerFactory.getLogger(MarkdownStructureExtractor.class);

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    public StructuralDocument extract(byte[] content) {
        String raw = StructureExtractor.utf8(content);
        List<Section> sections = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        boolean inFence = false;

        for (String line : raw.split("\r?\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                body.append(line).append("\n");
                continue;
            }
            if (inFence) {
                body.append(line).append("\n");
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                emitText(sections, body);
                sections.add(new Section(m.group(1).length(), m.group(2).strip(), null));
            } else {
                body.append(line).append("\n");
            }
        }
        emitText(sections, body);
        if (sections.isEmpty()) {
            sections.add(new Section(null, null, ""));
        }
        return new StructuralDocument(sections);
    }

    private static void emitText(List<Section> sections, StringBuilder body) {
        String text = untangle(body);
        if (!text.isEmpty()) {
            sections.add(new Section(null, null, text));
        }
        body.setLength(0);
    }

    static String untangle(StringBuilder body) {
        String collapsed = body.toString().replace("\u200b", "").replaceAll("[\\t\\s]+", " ");
        return collapsed.strip();
    }
}