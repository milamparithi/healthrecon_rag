package com.healthrecon.rag.service.guardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Optional second-pass LLM judge that checks whether an answer is factually consistent
 * with the retrieved context. Gated by {@code app.rag.guardrails.llm-check-enabled} (off
 * by default).
 *
 * <p>Fail-open on model errors or unparseable responses so that a flaky LLM never blocks
 * genuine document questions.
 */
@Service
public class FactualConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(FactualConsistencyChecker.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ChatModel chatModel;

    public FactualConsistencyChecker(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Returns {@code true} when the answer is supported by the context (or when the
     * judge cannot determine consistency — fail-open).
     */
    public boolean isConsistent(String answer, String contextText) {
        if (answer == null || answer.isBlank() || contextText == null || contextText.isBlank()) {
            return true;
        }
        try {
            String responseText = chatModel.chat(List.<ChatMessage>of(
                    new SystemMessage("""
                            You are a factual consistency checker. Determine whether the following ANSWER
                            is fully supported by the provided CONTEXT. The answer must not contain claims
                            that contradict or go beyond what the context states.

                            Respond with ONLY a JSON object, no other text:
                            {"consistent": true, "reason": "..."}"""),
                    new UserMessage("CONTEXT:\n" + contextText + "\n\nANSWER:\n" + answer)
            )).aiMessage().text();
            String json = extractJson(responseText);
            JsonNode node = objectMapper.readTree(json);
            return node.has("consistent") && node.get("consistent").asBoolean(true);
        } catch (Exception e) {
            log.warn("FactualConsistencyChecker: failed to judge, falling through (fail-open)", e);
            return true;
        }
    }

    private static String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}