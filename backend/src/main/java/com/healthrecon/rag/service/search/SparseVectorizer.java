package com.healthrecon.rag.service.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Converts raw text into a sparse vector of term weights for BM25-style
 * matching in Qdrant (paired with {@code modifier: idf} on the collection).
 * Deterministic: a fixed set of stopwords is dropped, remaining terms are
 * lowercased and hashed to a stable non-negative index, and the value is the
 * raw term frequency in the text. Document-side and query-side vectors both
 * go through this class so the index/dimension space always agrees, which is
 * what makes the search self-contained (no external embedding service).
 */
@Component
public class SparseVectorizer {

    public static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can",
            "could", "did", "do", "does", "for", "from", "had", "has", "have",
            "he", "her", "his", "how", "i", "if", "in", "into", "is", "it",
            "its", "may", "me", "my", "of", "on", "or", "our", "she", "so",
            "such", "than", "that", "the", "their", "them", "then", "there",
            "these", "they", "this", "to", "us", "was", "we", "were", "what",
            "when", "where", "which", "who", "why", "will", "with", "you",
            "your");

    private static final float FNV_OFFSET = 2166136261f;
    private static final int FNV_PRIME = 16777619;

    /**
     * Sparse vector as (index, weight) pairs, sorted by index and without
     * duplicates. Consumers must pair this with the same tokenizer on both the
     * index and query side.
     */
    public record SparseVector(int[] indices, float[] values) {
        public boolean isEmpty() {
            return indices.length == 0;
        }
    }

    public SparseVector vectorize(String text) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (String token : tokenize(text)) {
            counts.merge(hashIndex(token), 1, Integer::sum);
        }
        int[] indices = new int[counts.size()];
        float[] values = new float[counts.size()];
        List<Integer> keys = new ArrayList<>(counts.keySet());
        keys.sort(Integer::compare);
        for (int i = 0; i < keys.size(); i++) {
            indices[i] = keys.get(i);
            values[i] = counts.get(keys.get(i));
        }
        return new SparseVector(indices, values);
    }

    /**
     * Shared set of tokens for the given text, or an empty set when only
     * stopwords/empty tokens are present.
     */
    public Set<String> tokenSet(String text) {
        return new LinkedHashSet<>(tokenize(text));
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.isEmpty() || STOPWORDS.contains(raw) || raw.length() < 2) {
                continue;
            }
            tokens.add(raw);
        }
        return tokens;
    }

    private static int hashIndex(String token) {
        int hash = (int) FNV_OFFSET;
        for (int i = 0; i < token.length(); i++) {
            hash ^= token.charAt(i);
            hash *= FNV_PRIME;
        }
        return Math.floorMod(hash, 1_000_000_000) + 1;
    }
}