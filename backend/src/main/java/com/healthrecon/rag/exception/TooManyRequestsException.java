package com.healthrecon.rag.exception;

/**
 * Thrown when a user exceeds a chat abuse limit (rate limit or conversation cap).
 * Mapped to HTTP 429 by the global exception handler.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}