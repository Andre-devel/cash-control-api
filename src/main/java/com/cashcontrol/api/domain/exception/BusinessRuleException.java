package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class BusinessRuleException extends AuthException {

    public BusinessRuleException(String message) {
        super(message);
    }

    public BusinessRuleException(String message, UUID correlationId) {
        super(message, correlationId);
    }
}
