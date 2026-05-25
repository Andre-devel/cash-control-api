package com.cashcontrol.api.domain.exception;

import com.cashcontrol.api.security.CorrelationIdHolder;

import java.util.UUID;

public class AuthException extends RuntimeException {

    private final UUID correlationId;

    public AuthException(String message) {
        super(message);
        this.correlationId = CorrelationIdHolder.get();
    }

    public AuthException(String message, UUID correlationId) {
        super(message);
        this.correlationId = correlationId != null ? correlationId : CorrelationIdHolder.get();
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
        this.correlationId = CorrelationIdHolder.get();
    }

    public AuthException(String message, UUID correlationId, Throwable cause) {
        super(message, cause);
        this.correlationId = correlationId != null ? correlationId : CorrelationIdHolder.get();
    }

    public UUID getCorrelationId() {
        return correlationId;
    }
}