package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class AccountDisabledException extends AuthException {

    private static final String DEFAULT_MESSAGE = "Credenciais inválidas.";

    public AccountDisabledException() {
        super(DEFAULT_MESSAGE);
    }

    public AccountDisabledException(UUID correlationId) {
        super(DEFAULT_MESSAGE, correlationId);
    }
}