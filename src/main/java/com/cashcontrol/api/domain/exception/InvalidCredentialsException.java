package com.cashcontrol.api.domain.exception;

import java.util.UUID;

// Generic authentication failure — never reveals whether email, password, or account state caused the failure
public class InvalidCredentialsException extends AuthException {

    private static final String DEFAULT_MESSAGE = "Invalid credentials.";

    public InvalidCredentialsException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidCredentialsException(UUID correlationId) {
        super(DEFAULT_MESSAGE, correlationId);
    }

    public InvalidCredentialsException(String internalMessage) {
        super(internalMessage);
    }
}