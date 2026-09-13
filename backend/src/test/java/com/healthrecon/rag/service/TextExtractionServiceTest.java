package com.healthrecon.rag.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TextExtractionServiceTest {

    private final TextExtractionService service = new TextExtractionService();

    @Test
    void extractsPlainText() {
        assertThat(service.extract("Hello RAG world".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("Hello RAG world");
    }

    @Test
    void extractsTextFromDocx() throws IOException {
        byte[] docx = minimalDocx("Hello from a DOCX");

        String text = service.extract(docx);

        assertThat(text).contains("Hello from a DOCX");
    }

    @Test
    void handlesBinaryGarbageWithoutCrashing() {
        byte[] garbage = new byte[256];
        new java.util.Random(42).nextBytes(garbage);

        assertThat(service.extract(garbage)).isBlank();
    }

    private static byte[] minimalDocx(String text) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            zos.write(("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                    """).formatted(text).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}