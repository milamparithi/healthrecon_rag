package com.healthrecon.rag.service.guardrails;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactualConsistencyCheckerTest {

    private ChatModel chatModel;
    private FactualConsistencyChecker checker;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        checker = new FactualConsistencyChecker(chatModel);
    }

    @Test
    void consistentAnswerPasses() {
        stubJudge("{\"consistent\": true, \"reason\": \"supported by context\"}");
        assertThat(checker.isConsistent("Take 500 mg daily [1].", "Take 500 mg daily.")).isTrue();
    }

    @Test
    void inconsistentAnswerFails() {
        stubJudge("{\"consistent\": false, \"reason\": \"contradicts the context\"}");
        assertThat(checker.isConsistent("Take 2000 mg daily [1].", "Take 500 mg daily.")).isFalse();
    }

    @Test
    void markdownFencedJsonIsParsed() {
        stubJudge("```json\n{\"consistent\": false, \"reason\": \"not supported\"}\n```");
        assertThat(checker.isConsistent("Take 2000 mg daily [1].", "Take 500 mg daily.")).isFalse();
    }

    @Test
    void proseAroundJsonIsExtracted() {
        stubJudge("Here is my assessment: {\"consistent\": true, \"reason\": \"cited correctly\"} Hope this helps.");
        assertThat(checker.isConsistent("Take 500 mg daily [1].", "Take 500 mg daily.")).isTrue();
    }

    @Test
    void modelErrorFailsOpen() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("LLM down"));
        assertThat(checker.isConsistent("Take 500 mg daily [1].", "Take 500 mg daily.")).isTrue();
    }

    @Test
    void malformedResponseFailsOpen() {
        stubJudge("I cannot determine this.");
        assertThat(checker.isConsistent("Take 500 mg daily [1].", "Take 500 mg daily.")).isTrue();
    }

    @Test
    void blankAnswerSkipsJudge() {
        assertThat(checker.isConsistent("", "context text")).isTrue();
        assertThat(checker.isConsistent(null, "context text")).isTrue();
        verify(chatModel, never()).chat(anyList());
    }

    @Test
    void blankContextSkipsJudge() {
        assertThat(checker.isConsistent("answer", "  ")).isTrue();
        assertThat(checker.isConsistent("answer", null)).isTrue();
        verify(chatModel, never()).chat(anyList());
    }

    private void stubJudge(String responseText) {
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(new AiMessage(responseText)).build());
    }
}