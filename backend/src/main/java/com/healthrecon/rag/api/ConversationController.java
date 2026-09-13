package com.healthrecon.rag.api;

import com.healthrecon.rag.api.dto.ConversationRequest;
import com.healthrecon.rag.api.dto.ConversationResponse;
import com.healthrecon.rag.api.dto.ChatMessageResponse;
import com.healthrecon.rag.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documentsets/{docSetId}/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<ConversationResponse> list(@PathVariable UUID docSetId) {
        return conversationService.list(docSetId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@PathVariable UUID docSetId,
                                       @Valid @RequestBody(required = false) ConversationRequest request) {
        return conversationService.create(docSetId, request == null ? null : request.title());
    }

    @GetMapping("/{conversationId}/messages")
    public List<ChatMessageResponse> messages(@PathVariable UUID docSetId, @PathVariable UUID conversationId) {
        return conversationService.messages(docSetId, conversationId);
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID docSetId, @PathVariable UUID conversationId) {
        conversationService.delete(docSetId, conversationId);
    }
}