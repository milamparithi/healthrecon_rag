package com.healthrecon.rag.service.guardrails;

import com.healthrecon.rag.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRateLimiterTest {

    private final ChatRateLimiter limiter = new ChatRateLimiter();
    private final UUID userId = UUID.randomUUID();

    @Test
    void permitsRequestsWithinLimit() {
        assertThatCode(() -> {
            limiter.check(userId, 3, 60);
            limiter.check(userId, 3, 60);
            limiter.check(userId, 3, 60);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsRequestsOverLimit() {
        limiter.check(userId, 2, 60);
        limiter.check(userId, 2, 60);

        assertThatThrownBy(() -> limiter.check(userId, 2, 60))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void otherUsersAreNotAffected() {
        limiter.check(userId, 1, 60);

        assertThatCode(() -> limiter.check(UUID.randomUUID(), 1, 60)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check(userId, 1, 60)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void allowsAgainAfterWindowElapses() throws InterruptedException {
        UUID isolatedUser = UUID.randomUUID();
        limiter.check(isolatedUser, 1, 1);
        assertThatThrownBy(() -> limiter.check(isolatedUser, 1, 1)).isInstanceOf(TooManyRequestsException.class);

        Thread.sleep(1100);

        assertThatCode(() -> limiter.check(isolatedUser, 1, 1)).doesNotThrowAnyException();
    }

    @Test
    void neverRejectsWhenLimitDisabled() {
        assertThatCode(() -> {
            limiter.check(userId, 0, 60);
            limiter.check(userId, -1, 60);
        }).doesNotThrowAnyException();
    }
}