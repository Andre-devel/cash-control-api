package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class AccountNotVerifiedException extends AuthException {

    private static final String DEFAULT_MESSAGE = "Invalid credentials.";

    public AccountNotVerifiedException() {
        super(DEFAULT_MESSAGE);
    }

    public AccountNotVerifiedException(UUID correlationId) {
        super(DEFAULT_MESSAGE, correlationId);
    }
}