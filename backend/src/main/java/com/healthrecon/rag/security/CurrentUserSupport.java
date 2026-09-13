package com.healthrecon.rag.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Convenience accessor for the authenticated user.
 */
public final class CurrentUserSupport {

    private CurrentUserSupport() {
    }

    /**
     * @return the current user; safe to call only inside authenticated endpoints
     * @throws IllegalStateException when no authenticated user is present
     */
    public static CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser) {
            return currentUser;
        }
        throw new IllegalStateException("No authenticated user in context");
    }
}