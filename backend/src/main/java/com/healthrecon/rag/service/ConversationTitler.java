package com.healthrecon.rag.service;

/**
 * Derives a short conversation title from the first user message.
 */
public final class ConversationTitler {

    static final int MAX_LENGTH = 45;

    private ConversationTitler() {
    }

    /**
     * @return a human-readable title derived from the user message, or {@code null}
     *         when no usable title can be produced (blank input or punctuation only).
     */
    public static String derive(String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message.replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }

        boolean truncated = cleaned.length() > MAX_LENGTH;
        String cut = truncated ? cleaned.substring(0, MAX_LENGTH) : cleaned;
        if (truncated) {
            int space = cut.lastIndexOf(' ');
            if (space > MAX_LENGTH / 2) {
                cut = cut.substring(0, space);
            }
        }

        String base = stripTrailingPunctuation(cut).strip();
        if (base.isEmpty()) {
            return null;
        }
        return truncated ? base + "…" : base;
    }

    private static String stripTrailingPunctuation(String value) {
        int end = value.length();
        while (end > 0 && "?!.,:;…".indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }
}