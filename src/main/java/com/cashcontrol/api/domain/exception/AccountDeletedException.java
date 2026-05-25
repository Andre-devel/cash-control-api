package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class AccountDeletedException extends AuthException {

    private static final String DEFAULT_MESSAGE = "Invalid credentials.";

    public AccountDeletedException() {
        super(DEFAULT_MESSAGE);
    }

    public AccountDeletedException(UUID correlationId) {
        super(DEFAULT_MESSAGE, correlationId);
    }
}