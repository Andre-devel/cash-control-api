package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class ResourceNotFoundException extends AuthException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, UUID correlationId) {
        super(message, correlationId);
    }
}