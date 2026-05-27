package com.cashcontrol.api;

import com.cashcontrol.api.util.LogSanitizationGuard;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizationGuardTest {

    @Test
    void isAllowedMdcKey_returnsTrue_forApprovedKeys() {
        assertThat(LogSanitizationGuard.isAllowedMdcKey("correlationId")).isTrue();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("userId")).isTrue();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("resourceId")).isTrue();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("eventType")).isTrue();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("httpMethod")).isTrue();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("path")).isTrue();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("statusCode")).isTrue();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("duration")).isTrue();
    }

    @Test
    void isAllowedMdcKey_returnsFalse_forFinancialAndSensitiveKeys() {
        assertThat(LogSanitizationGuard.isAllowedMdcKey("amount")).isFalse();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("description")).isFalse();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("balance")).isFalse();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("password")).isFalse();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("accountName")).isFalse();
        assertThat(LogSanitizationGuard.isAllowedMdcKey("categoryName")).isFalse();
    }

    @Test
    void isAllowedMdcKey_returnsFalse_forNull() {
        assertThat(LogSanitizationGuard.isAllowedMdcKey(null)).isFalse();
    }

    @Test
    void isProhibitedField_returnsTrue_forFinancialFields() {
        assertThat(LogSanitizationGuard.isProhibitedField("amount")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("balance")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("description")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("notes")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("accountName")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("categoryName")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("tagValue")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("location")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("cardName")).isTrue();
        assertThat(LogSanitizationGuard.isProhibitedField("issuer")).isTrue();
    }

    @Test
    void isProhibitedField_returnsFalse_forAllowedIdentifiers() {
        assertThat(LogSanitizationGuard.isProhibitedField("correlationId")).isFalse();
        assertThat(LogSanitizationGuard.isProhibitedField("userId")).isFalse();
        assertThat(LogSanitizationGuard.isProhibitedField("resourceId")).isFalse();
        assertThat(LogSanitizationGuard.isProhibitedField("eventType")).isFalse();
    }

    @Test
    void isProhibitedField_returnsFalse_forNull() {
        assertThat(LogSanitizationGuard.isProhibitedField(null)).isFalse();
    }

    @Test
    void buildLogContext_containsOnlyIdentifiers() {
        UUID userId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        String context = LogSanitizationGuard.buildLogContext(userId, resourceId, "TRANSACTION_CREATED");

        assertThat(context).contains("userId=" + userId);
        assertThat(context).contains("resourceId=" + resourceId);
        assertThat(context).contains("eventType=TRANSACTION_CREATED");
    }

    @Test
    void buildLogContext_handlesNullUserId() {
        UUID resourceId = UUID.randomUUID();
        String context = LogSanitizationGuard.buildLogContext(null, resourceId, "ACCOUNT_ARCHIVED");

        assertThat(context).contains("userId=anonymous");
        assertThat(context).contains("resourceId=" + resourceId);
        assertThat(context).contains("eventType=ACCOUNT_ARCHIVED");
    }

    @Test
    void buildLogContext_handlesNullResourceId() {
        UUID userId = UUID.randomUUID();
        String context = LogSanitizationGuard.buildLogContext(userId, null, "LIST_ACCOUNTS");

        assertThat(context).contains("userId=" + userId);
        assertThat(context).contains("resourceId=none");
        assertThat(context).contains("eventType=LIST_ACCOUNTS");
    }

    @Test
    void buildLogContext_handlesAllNulls() {
        String context = LogSanitizationGuard.buildLogContext(null, null, null);

        assertThat(context).contains("userId=anonymous");
        assertThat(context).contains("resourceId=none");
        assertThat(context).contains("eventType=unknown");
    }

    @Test
    void buildHttpLogContext_containsAllStructuralFields() {
        String context = LogSanitizationGuard.buildHttpLogContext("GET", "/api/v1/accounts", 200, 42L);

        assertThat(context).contains("method=GET");
        assertThat(context).contains("path=/api/v1/accounts");
        assertThat(context).contains("status=200");
        assertThat(context).contains("durationMs=42");
    }

    @Test
    void buildHttpLogContext_doesNotContainFinancialContent() {
        String context = LogSanitizationGuard.buildHttpLogContext("POST", "/api/v1/transactions", 201, 100L);

        assertThat(context).doesNotContainIgnoringCase("amount");
        assertThat(context).doesNotContainIgnoringCase("description");
        assertThat(context).doesNotContainIgnoringCase("balance");
    }

    @Test
    void allowedMdcKeysSet_containsAllRequiredKeys() {
        assertThat(LogSanitizationGuard.ALLOWED_MDC_KEYS).containsExactlyInAnyOrder(
                "correlationId", "userId", "resourceId", "eventType",
                "httpMethod", "path", "statusCode", "duration"
        );
    }

    @Test
    void prohibitedFieldsSet_containsAllFinancialFields() {
        assertThat(LogSanitizationGuard.PROHIBITED_FIELDS).contains(
                "amount", "balance", "description", "notes",
                "accountName", "categoryName", "tagValue", "location"
        );
    }
}
