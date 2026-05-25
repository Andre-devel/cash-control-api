package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.security.JwtServiceImpl;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPayloadSecurityTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-key-for-testing-purposes-only-must-be-long-enough-for-hs512";

    private JwtServiceImpl jwtService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(TEST_SECRET);
        props.getJwt().setExpirationMinutes(15);
        jwtService = new JwtServiceImpl(props);
        jwtService.init();
    }

    @Test
    void jwtSub_isUuidFormat() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, List.of("user:read"), Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);

        String subject = claims.getSubject();
        assertThat(subject).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void jwtSub_doesNotContainAtSign() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, List.of("user:read"), Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);

        assertThat(claims.getSubject()).doesNotContain("@");
    }

    @Test
    void jwtAuthorities_containNoPasswordOrHashFields() {
        List<String> authorities = List.of("user:read", "user:create", "role:update");
        String token = jwtService.generateToken(UUID.randomUUID(), authorities, Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);

        List<String> extracted = jwtService.extractAuthorities(claims);
        assertThat(extracted).noneMatch(a -> a.toLowerCase().contains("password"));
        assertThat(extracted).noneMatch(a -> a.toLowerCase().contains("hash"));
        assertThat(extracted).noneMatch(a -> a.toLowerCase().contains("secret"));
        assertThat(extracted).noneMatch(a -> a.contains("@"));
    }

    @Test
    void jwtToken_doesNotContainPasswordOrHashClaim() {
        String token = jwtService.generateToken(UUID.randomUUID(), List.of("user:read"), Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);

        assertThat(claims.containsKey("password")).isFalse();
        assertThat(claims.containsKey("passwordHash")).isFalse();
        assertThat(claims.containsKey("hash")).isFalse();
        assertThat(claims.containsKey("email")).isFalse();
        assertThat(claims.containsKey("name")).isFalse();
        assertThat(claims.containsKey("displayName")).isFalse();
    }

    @Test
    void jwtToken_sizeIsUnder4096BytesForTypicalPermissionSet() {
        List<String> permissions = List.of(
                "user:create", "user:read", "user:update", "user:delete",
                "role:create", "role:update", "role:delete",
                "permission:grant", "permission:revoke",
                "audit:view", "auth:manage"
        );
        String token = jwtService.generateToken(UUID.randomUUID(), permissions, Instant.now());

        assertThat(token.length()).isLessThan(4096);
    }

    @Test
    void jwtToken_rawBytes_doNotContainEmailString() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, List.of("user:read"), Instant.now());

        // The raw token (all 3 parts) should not contain anything resembling an email
        assertThat(token).doesNotContain("@example.com");
        assertThat(token).doesNotContain("email");
    }
}
