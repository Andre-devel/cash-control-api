package com.cashcontrol.api.util;

import java.util.Set;
import java.util.UUID;

/**
 * Enforces the financial logging policy for the Cash Control API.
 *
 * ALLOWED in structured log output:
 *   eventType, userId, resourceId, correlationId, httpMethod, path, statusCode, durationMs
 *
 * PROHIBITED in log output at any level:
 *   amounts, descriptions, notes, account names, category names, tag values, locations, card details
 *
 * Enforcement is by convention: never interpolate user-supplied financial content into log messages.
 * Use {@link #buildLogContext} and {@link #buildHttpLogContext} to produce policy-compliant log strings.
 */
public final class LogSanitizationGuard {

    public static final Set<String> ALLOWED_MDC_KEYS = Set.of(
            "correlationId",
            "userId",
            "resourceId",
            "eventType",
            "httpMethod",
            "path",
            "statusCode",
            "duration"
    );

    public static final Set<String> PROHIBITED_FIELDS = Set.of(
            "amount",
            "balance",
            "description",
            "notes",
            "accountName",
            "categoryName",
            "tagValue",
            "location",
            "cardName",
            "issuer"
    );

    private LogSanitizationGuard() {}

    /**
     * Returns {@code true} when the MDC key is on the approved list for structured logging.
     */
    public static boolean isAllowedMdcKey(String key) {
        return key != null && ALLOWED_MDC_KEYS.contains(key);
    }

    /**
     * Returns {@code true} when the field name is explicitly prohibited from log output.
     */
    public static boolean isProhibitedField(String fieldName) {
        return fieldName != null && PROHIBITED_FIELDS.contains(fieldName);
    }

    /**
     * Builds a safe, policy-compliant log context string from identity and structural identifiers only.
     * Never pass financial values (amounts, descriptions, names) to this method.
     */
    public static String buildLogContext(UUID userId, UUID resourceId, String eventType) {
        return "userId=" + (userId != null ? userId : "anonymous")
                + " resourceId=" + (resourceId != null ? resourceId : "none")
                + " eventType=" + (eventType != null ? eventType : "unknown");
    }

    /**
     * Builds a safe HTTP event log context string containing only structural fields.
     */
    public static String buildHttpLogContext(String method, String path, int statusCode, long durationMs) {
        return "method=" + method
                + " path=" + path
                + " status=" + statusCode
                + " durationMs=" + durationMs;
    }
}
