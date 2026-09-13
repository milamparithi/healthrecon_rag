package com.healthrecon.rag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitlerTest {

    @Test
    void derivesShortTitleFromShortMessage() {
        assertThat(ConversationTitler.derive("What dosage?")).isEqualTo("What dosage");
    }

    @Test
    void collapsesWhitespace() {
        assertThat(ConversationTitler.derive("  What   dosage   ?  ")).isEqualTo("What dosage");
    }

    @Test
    void stripsTrailingPunctuationWhenUnderLimit() {
        assertThat(ConversationTitler.derive("What dosage for adults?")).isEqualTo("What dosage for adults");
    }

    @Test
    void truncatesLongMessageAtWordBoundaryWithEllipsis() {
        String longMessage = "What should the patient take for the headache when they wake up";
        String title = ConversationTitler.derive(longMessage);

        assertThat(title).endsWith("…");
        assertThat(title).hasSizeLessThanOrEqualTo(ConversationTitler.MAX_LENGTH + 1);
        assertThat(title).doesNotContain("when they wake up");
    }

    @Test
    void returnsNullForBlankInput() {
        assertThat(ConversationTitler.derive(null)).isNull();
        assertThat(ConversationTitler.derive("   ")).isNull();
    }

    @Test
    void returnsNullForPunctuationOnly() {
        assertThat(ConversationTitler.derive("???!")).isNull();
    }
}