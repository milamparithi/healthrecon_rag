package com.healthrecon.rag.service.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Records every chat-model call as a Langfuse "generation" span
 * ({@code generation-chat}) so the trace UI groups it as a model call. The
 * request text, response text and token usage are attached as
 * {@code input.value}/{@code output.value} and {@code gen_ai.*} attributes.
 *
 * <p>The span state travels in the LangChain4j context ({@code attributes()}),
 * which is handed to all three callbacks around a single model invocation. This
 * listener must never throw: tracing failures are logged at debug so they can
 * never break the LLM call.
 */
@Component
public class LangfuseChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LangfuseChatModelListener.class);
    private static final String SPAN_KEY = "com.healthrecon.rag.langfuse.chat-span";

    private final LangfuseSpanHelper helper;

    public LangfuseChatModelListener(LangfuseSpanHelper helper) {
        this.helper = helper;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        if (!helper.shouldEmit()) {
            return;
        }
        try {
            Span span = helper.getTracer().spanBuilder("generation-chat").startSpan();
            span.setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j");
            String modelName = chatModelName(context);
            if (modelName != null) {
                span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, modelName);
            }
            String input = requestText(context);
            if (!input.isBlank()) {
                span.setAttribute(LangfuseAttributes.INPUT_VALUE, input);
            }
            Scope scope = span.makeCurrent();
            context.attributes().put(SPAN_KEY, new ActiveModelSpan(span, scope));
        } catch (RuntimeException failure) {
            log.debug("Langfuse chat listener ignored a request event: {}", failure.getMessage());
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            if (!(context.attributes().get(SPAN_KEY) instanceof ActiveModelSpan active)) {
                return;
            }
            ChatResponse response = context.chatResponse();
            if (response != null && response.aiMessage() != null && response.aiMessage().text() != null) {
                active.span().setAttribute(LangfuseAttributes.OUTPUT_VALUE, response.aiMessage().text());
            }
            if (response != null && response.tokenUsage() != null) {
                TokenUsage usage = response.tokenUsage();
                if (usage.inputTokenCount() != null) {
                    active.span().setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS,
                            usage.inputTokenCount().longValue());
                }
                if (usage.outputTokenCount() != null) {
                    active.span().setAttribute(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS,
                            usage.outputTokenCount().longValue());
                }
            }
            active.span().setAttribute(LangfuseAttributes.LANGFUSE_OBSERVATION_LEVEL, "INFO");
            active.close();
        } catch (RuntimeException failure) {
            log.debug("Langfuse chat listener ignored a response event: {}", failure.getMessage());
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        try {
            if (!(context.attributes().get(SPAN_KEY) instanceof ActiveModelSpan active)) {
                return;
            }
            active.span().setAttribute(LangfuseAttributes.LANGFUSE_OBSERVATION_LEVEL, "ERROR");
            active.span().recordException(context.error());
            active.span().setStatus(StatusCode.ERROR);
            active.close();
        } catch (RuntimeException failure) {
            log.debug("Langfuse chat listener ignored an error event: {}", failure.getMessage());
        }
    }

    private static String chatModelName(ChatModelRequestContext context) {
        ChatRequest request = context.chatRequest();
        if (request == null) {
            return null;
        }
        if (request.modelName() != null && !request.modelName().isBlank()) {
            return request.modelName();
        }
        ChatRequestParameters parameters = request.parameters();
        return parameters == null || parameters.modelName() == null ? null : parameters.modelName();
    }

    private static String requestText(ChatModelRequestContext context) {
        if (context.chatRequest() == null) {
            return "";
        }
        List<ChatMessage> messages = context.chatRequest().messages();
        if (messages == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage) {
                text.append(userMessage.singleText());
            } else if (message instanceof AiMessage aiMessage) {
                text.append(aiMessage.text());
            } else if (message instanceof SystemMessage systemMessage) {
                text.append(systemMessage.text());
            }
            text.append('\n');
        }
        return text.toString().trim();
    }

    private record ActiveModelSpan(Span span, Scope scope) {
        void close() {
            scope.close();
            span.end();
        }
    }
}