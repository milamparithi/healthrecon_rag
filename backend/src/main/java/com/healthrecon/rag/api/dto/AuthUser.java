package com.healthrecon.rag.api.dto;

import com.healthrecon.rag.domain.User;

import java.time.Instant;
import java.util.UUID;

public record AuthUser(
        UUID id,
        String email,
        String displayName,
        Instant createdAt) {

    public static AuthUser from(User user) {
        return new AuthUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}