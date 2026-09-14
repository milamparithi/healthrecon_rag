package com.healthrecon.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.ChatResponse;
import com.healthrecon.rag.api.dto.SourceResponse;
import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.domain.ChatMessage;
import com.healthrecon.rag.domain.Conversation;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.DocumentSetStatus;
import com.healthrecon.rag.exception.ConflictException;
import com.healthrecon.rag.exception.TooManyRequestsException;
import com.healthrecon.rag.repository.ChatMessageRepository;
import com.healthrecon.rag.security.CurrentUser;
import com.healthrecon.rag.security.CurrentUserSupport;
import com.healthrecon.rag.service.observability.LangfuseAttributes;
import com.healthrecon.rag.service.observability.LangfuseSpanHelper;
import com.healthrecon.rag.service.observability.TracedSpan;
import com.healthrecon.rag.service.guardrails.ChatGuardrailService;
import com.healthrecon.rag.service.guardrails.ChatRateLimiter;
import com.healthrecon.rag.service.eval.EvalCaptureService;
import com.healthrecon.rag.service.search.QueryRewriteService;
import com.healthrecon.rag.service.semanticcache.CachedAnswer;
import com.healthrecon.rag.service.semanticcache.SemanticCache;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final DocumentSetService documentSetService;
    private final ConversationService conversationService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorIndexer vectorIndexer;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final ChatGuardrailService chatGuardrailService;
    private final ChatRateLimiter chatRateLimiter;
    private final SemanticCache semanticCache;
    private final EvalCaptureService evalCaptureService;
    private final QueryRewriteService queryRewriteService;
    private final LangfuseSpanHelper langfuseSpanHelper;

    public ChatService(DocumentSetService documentSetService,
                       ConversationService conversationService,
                       ChatMessageRepository chatMessageRepository,
                       ChatModel chatModel,
                       EmbeddingModel embeddingModel,
                       VectorIndexer vectorIndexer,
                       RagProperties ragProperties,
                       ObjectMapper objectMapper,
                       ChatGuardrailService chatGuardrailService,
                       ChatRateLimiter chatRateLimiter,
                       SemanticCache semanticCache,
                       EvalCaptureService evalCaptureService,
                       QueryRewriteService queryRewriteService,
                       LangfuseSpanHelper langfuseSpanHelper) {
        this.documentSetService = documentSetService;
        this.conversationService = conversationService;
        this.chatMessageRepository = chatMessageRepository;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.vectorIndexer = vectorIndexer;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.chatGuardrailService = chatGuardrailService;
        this.chatRateLimiter = chatRateLimiter;
        this.semanticCache = semanticCache;
        this.evalCaptureService = evalCaptureService;
        this.queryRewriteService = queryRewriteService;
        this.langfuseSpanHelper = langfuseSpanHelper;
    }

    @Transactional
    public ChatResponse chat(UUID docSetId, UUID conversationId, String message) {
        try (TracedSpan trace = langfuseSpanHelper.beginTrace("chat",
                traceAttributes(docSetId, conversationId, message))) {
            try {
                return doChat(docSetId, conversationId, message, trace);
            } catch (RuntimeException failure) {
                trace.fail(failure);
                throw failure;
            }
        }
    }

    private ChatResponse doChat(UUID docSetId, UUID conversationId, String message, TracedSpan trace) {
        DocumentSet set = documentSetService.requireOwnedSet(docSetId);
        if (set.getStatus() != DocumentSetStatus.READY) {
            throw new ConflictException("Chat is only available once the document set is ready");
        }
        Conversation conversation = conversationService.requireOwned(docSetId, conversationId);
        if (chatGuardrailService.enabled()) {
            chatRateLimiter.check(CurrentUserSupport.require().id(),
                    ragProperties.guardrails().rateLimitRequests(),
                    ragProperties.guardrails().rateLimitWindowSeconds());
        }
        Instant now = Instant.now();

        Optional<String> blocked;
        try (TracedSpan span = langfuseSpanHelper.startSpan("guardrails.input")) {
            blocked = chatGuardrailService.blocked(message);
        }
        if (blocked.isPresent()) {
            trace.setMetadata(Map.of(
                    LangfuseAttributes.EVAL_VERDICT, "refused",
                    LangfuseAttributes.EVAL_REFUSED, true));
            ChatMessage blockedUser = new ChatMessage(UUID.randomUUID(), conversationId,
                    ChatMessage.Role.USER, message, null, now);
            chatMessageRepository.save(blockedUser);
            conversation.touch(now);
            return persistBlocked(conversation, conversationId, blocked.get(), now);
        }

        List<dev.langchain4j.data.message.ChatMessage> history =
                toLangchainHistory(chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId));

        if (history.size() >= ragProperties.guardrails().maxMessagesPerConversation()) {
            throw new TooManyRequestsException("This conversation has reached its message limit");
        }

        ChatMessage userMessage = new ChatMessage(UUID.randomUUID(), conversationId,
                ChatMessage.Role.USER, message, null, now);
        chatMessageRepository.save(userMessage);
        conversation.touch(now);

        boolean cacheEnabled = ragProperties.cache() != null && ragProperties.cache().enabled();
        Embedding query = null;
        if (cacheEnabled) {
            try (TracedSpan span = langfuseSpanHelper.startSpan("semantic-cache.lookup")) {
                query = embeddingModel.embed(message).content();
                Optional<CachedAnswer> cached = semanticCache.lookup(docSetId, query);
                if (cached.isPresent()) {
                    CachedAnswer hit = cached.get();
                    ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), conversationId,
                            ChatMessage.Role.ASSISTANT, hit.answer(), toJson(hit.sources()), now);
                    chatMessageRepository.save(assistantMessage);
                    trace.setMetadata(Map.of(LangfuseAttributes.EVAL_VERDICT, "cached"));
                    String renamedTitle = renameConversationIfUntitled(conversation, message, now);
                    return new ChatResponse(conversationId, assistantMessage.getId(), hit.answer(),
                            hit.sources(), renamedTitle);
                }
            }
        }

        if (query == null) {
            query = embeddingModel.embed(message).content();
        }
        String searchQuery = message;
        Embedding searchEmbedding = query;
        if (queryRewriteService.enabled()) {
            String rewritten = null;
            try (TracedSpan span = langfuseSpanHelper.startSpan("query-rewrite")) {
                try {
                    rewritten = queryRewriteService.rewrite(history, message);
                } catch (Exception e) {
                    log.warn("Query rewrite failed unexpectedly; using the original query", e);
                }
            }
            if (rewritten != null && !rewritten.equals(message)) {
                searchQuery = rewritten;
                searchEmbedding = embeddingModel.embed(rewritten).content();
            }
        }
        List<ChunkSearchHit> hits;
        try (TracedSpan span = langfuseSpanHelper.startSpan("retriever")) {
            hits = vectorIndexer.search(docSetId, searchQuery, searchEmbedding, ragProperties.topK());
        }
        List<SourceResponse> sources = hits.stream()
                .map(hit -> new SourceResponse(hit.docId(), hit.filename(), hit.section(), truncate(hit.snippet(), 600)))
                .toList();

        List<dev.langchain4j.data.message.ChatMessage> prompt = new ArrayList<>();
        prompt.add(new SystemMessage(buildSystemPrompt(sources.isEmpty() ? List.of() : sources, ragProperties)));
        prompt.addAll(history);
        prompt.add(new UserMessage(message));

        String answer = chatModel.chat(prompt).aiMessage().text();
        String contextText = hits.stream().map(ChunkSearchHit::snippet).reduce("", (a, b) -> a + " " + b);

        Optional<String> refused;
        try (TracedSpan span = langfuseSpanHelper.startSpan("guardrails.output")) {
            refused = chatGuardrailService.refusalFor(answer, sources, contextText);
        }
        String sourcesJson;
        ChatMessage assistantMessage;
        if (refused.isPresent()) {
            sourcesJson = "[]";
            assistantMessage = new ChatMessage(UUID.randomUUID(), conversationId,
                    ChatMessage.Role.ASSISTANT, refused.get(), sourcesJson, Instant.now());
            chatMessageRepository.save(assistantMessage);
            try (TracedSpan span = langfuseSpanHelper.startSpan("eval.capture")) {
                evalCaptureService.capture(docSetId, assistantMessage.getId(), conversationId, message,
                        refused.get(), List.of(), contextText, true);
            }
            trace.setMetadata(Map.of(
                    LangfuseAttributes.EVAL_VERDICT, "refused",
                    LangfuseAttributes.EVAL_COVERAGE, 0.0,
                    LangfuseAttributes.EVAL_REFUSED, true));
            String renamedTitle = renameConversationIfUntitled(conversation, message, now);
            return new ChatResponse(conversationId, assistantMessage.getId(), refused.get(), List.of(), renamedTitle);
        }

        sourcesJson = toJson(sources);
        assistantMessage = new ChatMessage(UUID.randomUUID(), conversationId,
                ChatMessage.Role.ASSISTANT, answer, sourcesJson, Instant.now());
        chatMessageRepository.save(assistantMessage);
        try (TracedSpan span = langfuseSpanHelper.startSpan("eval.capture")) {
            evalCaptureService.capture(docSetId, assistantMessage.getId(), conversationId, message,
                    answer, sources, contextText, false);
        }
        if (trace.isActive()) {
            double coverage = chatGuardrailService.coverageFor(answer, contextText);
            String verdict = coverage < ragProperties.guardrails().hallucinationMinCoverage()
                    ? "low-coverage" : "grounded";
            trace.setMetadata(Map.of(
                    LangfuseAttributes.EVAL_VERDICT, verdict,
                    LangfuseAttributes.EVAL_COVERAGE, coverage,
                    LangfuseAttributes.EVAL_REFUSED, false));
        }

        if (cacheEnabled) {
            try (TracedSpan span = langfuseSpanHelper.startSpan("semantic-cache.store")) {
                semanticCache.store(docSetId, query, message, answer, sources);
            }
        }

        String renamedTitle = renameConversationIfUntitled(conversation, message, now);

        return new ChatResponse(conversationId, assistantMessage.getId(), answer, sources, renamedTitle);
    }

    private Map<String, String> traceAttributes(UUID docSetId, UUID conversationId, String message) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(LangfuseAttributes.INPUT_VALUE, message);
        attributes.put(LangfuseAttributes.RAG_DOCUMENT_SET_ID, docSetId.toString());
        attributes.put(LangfuseAttributes.LANGFUSE_SESSION_ID, conversationId.toString());
        attributes.put(LangfuseAttributes.LANGFUSE_TRACE_NAME, "chat");
        if (langfuseSpanHelper.isEnabled()) {
            try {
                CurrentUser user = CurrentUserSupport.require();
                if (user.id() != null) {
                    attributes.put(LangfuseAttributes.LANGFUSE_USER_ID, user.id().toString());
                }
            } catch (RuntimeException ignored) {
                // tracing attributes are best-effort; an unauthenticated caller still gets a trace
            }
        }
        return attributes;
    }

    private ChatResponse persistBlocked(Conversation conversation, UUID conversationId, String refusal, Instant now) {
        ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), conversationId,
                ChatMessage.Role.ASSISTANT, refusal, "[]", now);
        chatMessageRepository.save(assistantMessage);
        return new ChatResponse(conversationId, assistantMessage.getId(), refusal, List.of(), null);
    }

    String renameConversationIfUntitled(Conversation conversation, String message, Instant now) {
        if (!Conversation.DEFAULT_TITLE.equals(conversation.getTitle())) {
            return null;
        }
        String derived = ConversationTitler.derive(message);
        if (derived == null) {
            return null;
        }
        conversation.rename(derived, now);
        return derived;
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangchainHistory(List<ChatMessage> stored) {
        int window = Math.max(1, ragProperties.maxHistoryMessages());
        List<ChatMessage> recent = stored.size() <= window ? stored : stored.subList(stored.size() - window, stored.size());
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        for (ChatMessage message : recent) {
            if (message.getRole() == ChatMessage.Role.USER) {
                messages.add(new UserMessage(message.getContent()));
            } else {
                messages.add(new AiMessage(message.getContent()));
            }
        }
        return messages;
    }

    private String buildSystemPrompt(List<SourceResponse> sources, RagProperties props) {
        StringBuilder prompt = new StringBuilder(props.systemPrompt());
        if (chatGuardrailService.enabled()) {
            prompt.append("\n\n").append(chatGuardrailService.systemRules());
        }
        if (sources.isEmpty()) {
            return prompt.toString();
        }
        prompt.append("\n\nUse the following context to answer:\n");
        int i = 1;
        for (SourceResponse source : sources) {
            prompt.append("\n[").append(i++).append("] ")
                    .append(source.filename())
                    .append(source.section() == null || source.section().isBlank() ? "" : " > " + source.section())
                    .append("\n")
                    .append(source.snippet())
                    .append("\n");
        }
        return prompt.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize chat sources", e);
            return "[]";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max).stripTrailing() + "…";
    }
}