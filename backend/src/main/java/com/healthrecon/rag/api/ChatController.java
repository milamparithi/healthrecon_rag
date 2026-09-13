package com.healthrecon.rag.api;

import com.healthrecon.rag.api.dto.ChatRequest;
import com.healthrecon.rag.api.dto.ChatResponse;
import com.healthrecon.rag.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/documentsets/{docSetId}/conversations/{conversationId}/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@PathVariable UUID docSetId,
                             @PathVariable UUID conversationId,
                             @Valid @RequestBody ChatRequest request) {
        return chatService.chat(docSetId, conversationId, request.message());
    }
}