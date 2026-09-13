package com.healthrecon.rag.service;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@Service
public class TextExtractionService {

    /**
     * Extracts plain text from supported formats (PDF, DOCX, TXT, ...).
     *
     * @throws TikaException when the content cannot be parsed
     */
    public String extract(byte[] content) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(50 * 1024 * 1024);
        try {
            parser.parse(new ByteArrayInputStream(content), handler, new Metadata());
        } catch (IOException | SAXException e) {
            throw new IllegalArgumentException("Unsupported or unreadable document: " + e.getMessage(), e);
        } catch (TikaException e) {
            throw new IllegalArgumentException("Could not extract text from document: " + e.getMessage(), e);
        }
        return handler.toString().trim();
    }
}