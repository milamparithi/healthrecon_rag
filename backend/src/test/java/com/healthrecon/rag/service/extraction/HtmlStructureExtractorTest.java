package com.healthrecon.rag.service.extraction;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlStructureExtractorTest {

    private final HtmlStructureExtractor extractor = new HtmlStructureExtractor();

    @Test
    void capturesHeadingsAndIgnoresScripts() {
        String html = """
                <html><head><title>t</title><style>h1{color:red}</style></head>
                <body>
                <h1>Clinical Study</h1>
                <p>Population of 120.</p>
                <script>document.title = '<h2>fake</h2>';</script>
                <h2>Efficacy</h2>
                <p>90% response.</p>
                </body></html>
                """;

        StructuralDocument doc = extractor.extract(html.getBytes(StandardCharsets.UTF_8));

        String[] headings = doc.sections().stream()
                .filter(Section::isHeading)
                .map(Section::heading)
                .toArray(String[]::new);
        assertThat(headings).containsExactly("Clinical Study", "Efficacy");
        assertThat(doc.sections().stream()
                .filter(s -> !s.isHeading())
                .map(Section::text)
                .toList()).anyMatch(t -> t.contains("Population of 120."));
    }

    @Test
    void noHeadingTagsYieldsSingleTextRun() {
        StructuralDocument doc = extractor.extract("<p>a</p><p>b</p>".getBytes(StandardCharsets.UTF_8));

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).text()).isEqualTo("a b");
    }
}