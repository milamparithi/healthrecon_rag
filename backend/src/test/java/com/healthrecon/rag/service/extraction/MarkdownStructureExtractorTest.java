package com.healthrecon.rag.service.extraction;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownStructureExtractorTest {

    private final MarkdownStructureExtractor extractor = new MarkdownStructureExtractor();

    @Test
    void capturesHeadingHierarchyAndBodyBetweenHeadings() {
        String md = """
                # Intro

                Welcome text.

                ## Symptoms

                Fever and cough.

                ### Severe

                Hospitalisation.

                ## Treatment
                Rest.
                """;

        StructuralDocument doc = extractor.extract(md.getBytes(StandardCharsets.UTF_8));

        assertThat(doc.sections())
                .extracting(Section::isHeading)
                .containsExactly(true, false, true, false, true, false, true, false);
        assertThat(doc.sections())
                .filteredOn(Section::isHeading)
                .extracting(s -> s.level() + ":" + s.heading())
                .containsExactly("1:Intro", "2:Symptoms", "3:Severe", "2:Treatment");
        assertThat(doc.sections())
                .filteredOn(s -> !s.isHeading())
                .extracting(Section::text)
                .anyMatch(t -> t.contains("Fever and cough."))
                .anyMatch(t -> t.contains("Hospitalisation."));
    }

    @Test
    void noHeadingsBecomesSingleTextRun() {
        StructuralDocument doc = extractor.extract("just\nplain\ntext".getBytes(StandardCharsets.UTF_8));

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).isHeading()).isFalse();
        assertThat(doc.sections().get(0).text()).isEqualTo("just plain text");
    }

    @Test
    void codeFencesAreKeptAsBodyText() {
        String md = """
                ## Setup
                ```md
                # Not a heading
                ```
                done
                """;

        StructuralDocument doc = extractor.extract(md.getBytes(StandardCharsets.UTF_8));

        assertThat(doc.sections())
                .filteredOn(Section::isHeading)
                .extracting(Section::heading)
                .containsExactly("Setup");
        assertThat(doc.sections())
                .filteredOn(s -> !s.isHeading())
                .singleElement()
                .extracting(Section::text)
                .asString()
                .contains("# Not a heading")
                .contains("done");
    }
}