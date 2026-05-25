package com.cashcontrol.api.domain.exception;

import java.util.UUID;

// Internal use only — never returned raw to the client (anti-enumeration: caller receives uniform success response)
public class EmailAlreadyExistsException extends AuthException {

    public EmailAlreadyExistsException(String message) {
        super(message);
    }

    public EmailAlreadyExistsException(String message, UUID correlationId) {
        super(message, correlationId);
    }
}