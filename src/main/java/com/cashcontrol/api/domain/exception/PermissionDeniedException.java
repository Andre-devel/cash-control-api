package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class PermissionDeniedException extends AuthException {

    private static final String DEFAULT_MESSAGE = "Access denied.";

    public PermissionDeniedException() {
        super(DEFAULT_MESSAGE);
    }

    public PermissionDeniedException(UUID correlationId) {
        super(DEFAULT_MESSAGE, correlationId);
    }
}