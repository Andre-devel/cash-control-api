package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class ForbiddenAccessException extends AuthException {

    private static final String DEFAULT_MESSAGE = "Acesso negado.";

    public ForbiddenAccessException() {
        super(DEFAULT_MESSAGE);
    }

    public ForbiddenAccessException(String message) {
        super(message);
    }

    public ForbiddenAccessException(UUID correlationId) {
        super(DEFAULT_MESSAGE, correlationId);
    }

    public ForbiddenAccessException(String message, UUID correlationId) {
        super(message, correlationId);
    }
}
