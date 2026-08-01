package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class AccountLockedException extends AuthException {

    private static final String DEFAULT_MESSAGE = "Credenciais inválidas.";

    public AccountLockedException() {
        super(DEFAULT_MESSAGE);
    }

    public AccountLockedException(UUID correlationId) {
        super(DEFAULT_MESSAGE, correlationId);
    }
}