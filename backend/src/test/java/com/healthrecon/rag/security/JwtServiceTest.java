package com.healthrecon.rag.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-that-is-long-enough-0123456789";

    private final JwtService jwtService = new JwtService(SECRET, 3600);

    @Test
    void createsAndParsesTokenRoundTrip() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.createToken(userId);

        assertThat(jwtService.parseToken(token)).isEqualTo(userId);
    }

    @Test
    void rejectsGarbageToken() {
        assertThatThrownBy(() -> jwtService.parseToken("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService(SECRET, -1);
        String token = shortLived.createToken(UUID.randomUUID());

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOf(JwtException.class);
    }
}