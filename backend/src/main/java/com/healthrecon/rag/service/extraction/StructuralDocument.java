package com.healthrecon.rag.service.extraction;

import java.util.List;

public record StructuralDocument(List<Section> sections) {

    public int totalCharacters() {
        return sections.stream()
                .filter(s -> !s.isHeading())
                .mapToInt(s -> s.text().length())
                .sum();
    }
}