package com.healthrecon.rag;

import com.healthrecon.rag.security.CurrentUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

public final class TestSecurity {

    private TestSecurity() {
    }

    public static void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new CurrentUser(userId, "USER"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}