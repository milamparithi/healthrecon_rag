package com.healthrecon.rag.service.search;

import com.healthrecon.rag.config.RagProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-formulates the user's question into a standalone, retrieval-focused query
 * while leaving conversation history, titling and eval capture untouched.
 * Gated by {@code app.rag.query-rewrite.enabled} (off by default).
 *
 * <p>Fail-open: disabled, blank input, model errors and blank responses all
 * return the original question unchanged, so retrieval never breaks on a flaky LLM.
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private final ChatModel chatModel;
    private final RagProperties ragProperties;

    public QueryRewriteService(ChatModel chatModel, RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.ragProperties = ragProperties;
    }

    public boolean enabled() {
        RagProperties.QueryRewrite config = ragProperties.queryRewrite();
        return config != null && config.enabled();
    }

    public String rewrite(List<ChatMessage> history, String question) {
        RagProperties.QueryRewrite config = ragProperties.queryRewrite();
        if (config == null || !config.enabled() || question == null || question.isBlank()) {
            return question;
        }
        try {
            List<ChatMessage> prompt = new ArrayList<>();
            prompt.add(new SystemMessage(config.systemPrompt()));
            prompt.addAll(history);
            prompt.add(new UserMessage(question));
            String rewritten = chatModel.chat(prompt).aiMessage().text();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String cleaned = rewritten.strip();
            if (cleaned.length() > config.maxChars()) {
                cleaned = cleaned.substring(0, config.maxChars()).stripTrailing();
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("QueryRewriteService: rewrite failed, falling back to the original query (fail-open)", e);
            return question;
        }
    }
}