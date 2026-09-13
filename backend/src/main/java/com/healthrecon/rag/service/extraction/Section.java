package com.healthrecon.rag.service.extraction;

/**
 * A structural piece of a document: either a heading (heading = trimmed text,
 * level 1-6) or a text run (text != null, heading empty).
 */
public record Section(Integer level, String heading, String text) {

    public boolean isHeading() {
        return heading != null;
    }
}