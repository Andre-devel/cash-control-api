package com.cashcontrol.api.audit;

public final class RequestContext {

    public static final ScopedValue<String> IP = ScopedValue.newInstance();
    public static final ScopedValue<String> USER_AGENT = ScopedValue.newInstance();

    private RequestContext() {}

    public static String getIp() {
        return IP.isBound() ? IP.get() : null;
    }

    public static String getUserAgent() {
        return USER_AGENT.isBound() ? USER_AGENT.get() : null;
    }
}