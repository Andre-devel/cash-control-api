package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class TokenAlreadyConsumedException extends AuthException {

    public TokenAlreadyConsumedException(String message) {
        super(message);
    }

    public TokenAlreadyConsumedException(String message, UUID correlationId) {
        super(message, correlationId);
    }
}