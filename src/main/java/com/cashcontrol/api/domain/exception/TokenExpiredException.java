package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class TokenExpiredException extends AuthException {

    public TokenExpiredException(String message) {
        super(message);
    }

    public TokenExpiredException(String message, UUID correlationId) {
        super(message, correlationId);
    }
}