package com.cashcontrol.api.security;

import java.util.UUID;

public final class CorrelationIdHolder {

    public static final ScopedValue<UUID> CORRELATION_ID = ScopedValue.newInstance();

    private CorrelationIdHolder() {}

    public static UUID get() {
        return CORRELATION_ID.isBound() ? CORRELATION_ID.get() : UUID.randomUUID();
    }
}