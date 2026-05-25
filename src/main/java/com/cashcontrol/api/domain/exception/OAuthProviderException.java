package com.cashcontrol.api.domain.exception;

import java.util.UUID;

public class OAuthProviderException extends AuthException {

    public OAuthProviderException(String message) {
        super(message);
    }

    public OAuthProviderException(String message, UUID correlationId) {
        super(message, correlationId);
    }

    public OAuthProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}