package com.healthrecon.rag.service.search;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SparseVectorizerTest {

    private final SparseVectorizer vectorizer = new SparseVectorizer();

    @Test
    void dropsStopwordsAndSingleCharacters() {
        SparseVectorizer.SparseVector vector = vectorizer.vectorize("The cat and the hat sat on a mat.");

        assertThat(indices(vector)).hasSize(4); // cat, hat, sat, mat (the/and/on/a are stopwords)
    }

    @Test
    void tokenSetIgnoresStopwords() {
        Set<String> tokens = vectorizer.tokenSet("What is the dosage of the medication?");

        assertThat(tokens).contains("dosage", "medication")
                .doesNotContain("the", "is", "what");
    }

    @Test
    void stopwordOnlyTextYieldsEmptyVector() {
        SparseVectorizer.SparseVector vector = vectorizer.vectorize("the and of a");

        assertThat(vector.isEmpty()).isTrue();
    }

    @Test
    void blankAndNullTextYieldEmptyVector() {
        assertThat(vectorizer.vectorize("   ").isEmpty()).isTrue();
        assertThat(vectorizer.vectorize(null).isEmpty()).isTrue();
    }

    @Test
    void isDeterministicAcrossCalls() {
        SparseVectorizer.SparseVector a = vectorizer.vectorize("Beta-blocker protocol appendix");
        SparseVectorizer.SparseVector b = vectorizer.vectorize("Beta-blocker protocol appendix");

        assertThat(a.indices()).isEqualTo(b.indices());
        assertThat(a.values()).isEqualTo(b.values());
    }

    @Test
    void termFrequencyIsReflectedInValues() {
        SparseVectorizer.SparseVector vector = vectorizer.vectorize("hydrate hydrate hydrate");

        assertThat(vector.indices()).hasSize(1);
        assertThat(vector.values()[0]).isEqualTo(3f);
    }

    @Test
    void indicesAreSortedAndPositive() {
        SparseVectorizer.SparseVector vector = vectorizer.vectorize("zebra apple monkey");

        assertThat(vector.indices()).isSorted();
        assertThat(vector.indices()[0]).isPositive();
    }

    private static int[] indices(SparseVectorizer.SparseVector vector) {
        return vector.indices();
    }
}