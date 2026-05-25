package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditMetadataSanitizer;
import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditMetadataSanitizerTest {

    private AuditMetadataSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new AuditMetadataSanitizer(new DataMasker());
    }

    @Test
    void sanitize_nullInput_returnsNull() {
        assertThat(sanitizer.sanitize(null)).isNull();
    }

    @Test
    void sanitize_emptyMap_returnsEmptyMap() {
        assertThat(sanitizer.sanitize(Map.of())).isEmpty();
    }

    @Test
    void sanitize_blocksPasswordKey() {
        Map<String, Object> input = new HashMap<>();
        input.put("password", "secret123");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("password")).isEqualTo("[REDACTED]");
    }

    @Test
    void sanitize_blocksTokenKey() {
        Map<String, Object> input = new HashMap<>();
        input.put("resetToken", "raw-token-value");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("resetToken")).isEqualTo("[REDACTED]");
    }

    @Test
    void sanitize_blocksSecretKey() {
        Map<String, Object> input = new HashMap<>();
        input.put("clientSecret", "abc123");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("clientSecret")).isEqualTo("[REDACTED]");
    }

    @Test
    void sanitize_blocksHashKey() {
        Map<String, Object> input = new HashMap<>();
        input.put("passwordHash", "$argon2id$...");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("passwordHash")).isEqualTo("[REDACTED]");
    }

    @Test
    void sanitize_blocksCredentialKey() {
        Map<String, Object> input = new HashMap<>();
        input.put("credentialValue", "something");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("credentialValue")).isEqualTo("[REDACTED]");
    }

    @Test
    void sanitize_blocksKeysCaseInsensitively() {
        Map<String, Object> input = new HashMap<>();
        input.put("PASSWORD", "upper-case-secret");
        input.put("Token", "mixed-case-token");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("PASSWORD")).isEqualTo("[REDACTED]");
        assertThat(result.get("Token")).isEqualTo("[REDACTED]");
    }

    @Test
    void sanitize_masksEmailValues() {
        Map<String, Object> input = new HashMap<>();
        input.put("userEmail", "user@example.com");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("userEmail")).isEqualTo("u***@example.com");
    }

    @Test
    void sanitize_masksMultipleEmailValues() {
        Map<String, Object> input = new HashMap<>();
        input.put("actorEmail", "admin@company.io");
        input.put("targetEmail", "target@company.io");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("actorEmail")).isEqualTo("a***@company.io");
        assertThat(result.get("targetEmail")).isEqualTo("t***@company.io");
    }

    @Test
    void sanitize_preservesSafeStringValues() {
        Map<String, Object> input = new HashMap<>();
        input.put("roleId", "admin-role-uuid");
        input.put("reason", "Account disabled by admin");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("roleId")).isEqualTo("admin-role-uuid");
        assertThat(result.get("reason")).isEqualTo("Account disabled by admin");
    }

    @Test
    void sanitize_preservesNonStringValues() {
        Map<String, Object> input = new HashMap<>();
        input.put("failedAttempts", 5);
        input.put("success", true);
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("failedAttempts")).isEqualTo(5);
        assertThat(result.get("success")).isEqualTo(true);
    }

    @Test
    void sanitize_mixedSafeAndUnsafeKeys() {
        Map<String, Object> input = new HashMap<>();
        input.put("userId", "some-uuid");
        input.put("password", "secret");
        input.put("email", "user@example.com");
        input.put("action", "LOGIN");
        Map<String, Object> result = sanitizer.sanitize(input);
        assertThat(result.get("userId")).isEqualTo("some-uuid");
        assertThat(result.get("password")).isEqualTo("[REDACTED]");
        assertThat(result.get("email")).isEqualTo("u***@example.com");
        assertThat(result.get("action")).isEqualTo("LOGIN");
    }
}