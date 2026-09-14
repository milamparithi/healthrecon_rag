package com.healthrecon.rag.service.search;

import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.config.SearchProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriteServiceTest {

    private static final RagProperties.QueryRewrite DISABLED =
            new RagProperties.QueryRewrite(false, 200, "Rewrite the user's question into a search query.");
    private static final RagProperties.QueryRewrite ENABLED =
            new RagProperties.QueryRewrite(true, 200, "Rewrite the user's question into a search query.");

    private final ChatModel chatModel = mock(ChatModel.class);
    private final List<ChatMessage> history = List.of(
            new UserMessage("What is the first step?"),
            new AiMessage("Take paracetamol 500 mg."));

    private QueryRewriteService service(RagProperties.QueryRewrite rewrite) {
        RagProperties properties = new RagProperties(5, 10, "system", null, null,
                new SearchProperties(true, 30, "rrf", new SearchProperties.Rerank(false, "none")),
                rewrite, new RagProperties.Chunking("adaptive", 2000, 100, 2000));
        return new QueryRewriteService(chatModel, properties);
    }

    @Test
    void enabledReflectsConfiguration() {
        assertThat(service(DISABLED).enabled()).isFalse();
        assertThat(service(ENABLED).enabled()).isTrue();
    }

    @Test
    void disabledReturnsOriginalWithoutCallingTheModel() {
        QueryRewriteService service = service(DISABLED);

        String result = service.rewrite(history, "what now?");

        assertThat(result).isEqualTo("what now?");
        verify(chatModel, never()).chat(anyList());
    }

    @Test
    void enabledRewritesToStandaloneQuery() {
        QueryRewriteService service = service(ENABLED);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("tramadol for severe pain")).build());

        String result = service.rewrite(history, "what now?");

        assertThat(result).isEqualTo("tramadol for severe pain");
    }

    @Test
    void blankQuestionSkipsRewrite() {
        QueryRewriteService service = service(ENABLED);

        String result = service.rewrite(history, "   ");

        assertThat(result).isEqualTo("   ");
        verify(chatModel, never()).chat(anyList());
    }

    @Test
    void modelErrorFallsBackToOriginalQuery() {
        QueryRewriteService service = service(ENABLED);
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("LLM down"));

        String result = service.rewrite(history, "what now?");

        assertThat(result).isEqualTo("what now?");
    }

    @Test
    void blankModelResponseFallsBackToOriginalQuery() {
        QueryRewriteService service = service(ENABLED);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("   ")).build());

        String result = service.rewrite(history, "what now?");

        assertThat(result).isEqualTo("what now?");
    }

    @Test
    void rewrittenQueryIsCappedToConfiguredLength() {
        QueryRewriteService service = service(new RagProperties.QueryRewrite(true, 10, "system"));
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("a very long standalone search query here")).build());

        String result = service.rewrite(history, "what now?");

        assertThat(result.length()).isLessThanOrEqualTo(10);
    }

    @Test
    void promptContainsSystemInstructionsHistoryAndQuestion() {
        QueryRewriteService service = service(ENABLED);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage("query")).build());

        service.rewrite(history, "what now?");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(captor.capture());
        List<ChatMessage> prompt = captor.getValue();
        assertThat(prompt.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) prompt.get(0)).text()).contains("search query");
        assertThat(SystemMessage.class.isInstance(prompt.get(0))).isTrue();
        ChatMessage second = prompt.get(1);
        assertThat(second).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) second).singleText()).isEqualTo("What is the first step?");
        assertThat(prompt.get(prompt.size() - 1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) prompt.get(prompt.size() - 1)).singleText()).isEqualTo("what now?");
    }
}