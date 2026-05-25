package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class ConflictException extends AuthException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, UUID correlationId) {
        super(message, correlationId);
    }
}