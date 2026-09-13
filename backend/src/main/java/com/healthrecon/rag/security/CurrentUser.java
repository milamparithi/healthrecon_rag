package com.healthrecon.rag.security;

import java.util.UUID;

/**
 * Authenticated principal stored in the SecurityContext.
 */
public record CurrentUser(UUID id, String role) {
}