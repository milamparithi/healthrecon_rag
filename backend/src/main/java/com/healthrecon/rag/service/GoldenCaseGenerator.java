package com.healthrecon.rag.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.ExpectedSource;
import com.healthrecon.rag.config.GoldenProperties;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.repository.DocumentSetRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates draft golden Q&A pairs for an indexed document via the chat model.
 * The document's golden lifecycle is driven by {@link GoldenGenerationJob}; this
 * class only performs a single generation attempt and records the outcome on the
 * document (DONE / FAILED). Generation never touches the upload or chat request
 * path, so users see no latency.
 */
@Service
public class GoldenCaseGenerator {

    private static final Logger log = LoggerFactory.getLogger(GoldenCaseGenerator.class);
    private static final Pattern FENCED_JSON = Pattern.compile("```(?:json)?\\s*(\\[.*?\\])\\s*```", Pattern.DOTALL);
    private static final TypeReference<List<GeneratedQuestion>> QUESTIONS_TYPE = new TypeReference<>() {
    };

    private final GoldenProperties properties;
    private final ChatModel chatModel;
    private final DocumentRepository documentRepository;
    private final DocumentSetRepository documentSetRepository;
    private final GoldenCaseService goldenCaseService;
    private final ObjectMapper objectMapper;

    public GoldenCaseGenerator(GoldenProperties properties,
                               ChatModel chatModel,
                               DocumentRepository documentRepository,
                               DocumentSetRepository documentSetRepository,
                               GoldenCaseService goldenCaseService,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.chatModel = chatModel;
        this.documentRepository = documentRepository;
        this.documentSetRepository = documentSetRepository;
        this.goldenCaseService = goldenCaseService;
        this.objectMapper = objectMapper;
    }

    public void generate(StoredDocument doc) {
        doc.markGoldenGenerating();
        documentRepository.save(doc);
        try {
            String text = doc.getExtractedText();
            if (text == null || text.isBlank()) {
                doc.markGoldenDone();
                documentRepository.save(doc);
                return;
            }

            String bounded = truncate(text, properties.maxCharsPerDoc());
            log.info("Generating golden cases for document {} ({})", doc.getId(), doc.getFilename());
            String raw = chatModel.chat(List.of(
                    new SystemMessage(systemPrompt(properties.questionsPerDoc())),
                    new UserMessage(bounded))).aiMessage().text();

            List<GeneratedQuestion> pairs = parseQuestions(raw);
            if (pairs.isEmpty()) {
                throw new IllegalStateException("LLM returned no usable question/answer pairs");
            }

            DocumentSet set = documentSetRepository.findById(doc.getDocSetId()).orElseThrow();
            String sourcesJson = goldenCaseService.toJson(List.of(
                    new ExpectedSource(doc.getFilename(), doc.getId(), null)));
            for (GeneratedQuestion pair : pairs) {
                goldenCaseService.saveGenerated(doc.getDocSetId(), set.getOwnerId(), doc.getId(),
                        pair.question().strip(), pair.answer().strip(), sourcesJson);
            }
            doc.markGoldenDone();
        } catch (Exception e) {
            log.warn("Golden generation failed for document {} ({})", doc.getId(), doc.getFilename(), e);
            doc.markGoldenFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        documentRepository.save(doc);
    }

    List<GeneratedQuestion> parseQuestions(String raw) throws Exception {
        String cleaned = raw;
        Matcher matcher = FENCED_JSON.matcher(raw);
        if (matcher.find()) {
            cleaned = matcher.group(1);
        }
        List<GeneratedQuestion> parsed = objectMapper.readValue(cleaned, QUESTIONS_TYPE);
        return parsed.stream()
                .filter(pair -> pair.question() != null && !pair.question().isBlank()
                        && pair.answer() != null && !pair.answer().isBlank())
                .toList();
    }

    private static String systemPrompt(int count) {
        return """
                You are building a golden evaluation dataset for a document question-answering system.
                Based ONLY on the provided document text, create exactly %d question-and-answer pairs a user
                might ask about the content.

                Requirements:
                - Every question must be answerable solely from the provided document text.
                - Every answer must be accurate, concise, and grounded in the text.
                - Vary the question style (factual, explanatory, comparative, procedure).
                - Do not use any outside knowledge.
                - Reply with ONLY a raw JSON array (no markdown, no code fences, no commentary):
                [{"question": "...", "answer": "..."}]
                """.formatted(count);
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max).stripTrailing() + "\n…[truncated]";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GeneratedQuestion(String question, String answer) {
    }
}