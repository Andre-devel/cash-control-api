package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.exception.AuthException;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.security.JwtServiceImpl;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtServiceImpl jwtService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(
            "test-jwt-secret-key-for-testing-purposes-only-must-be-long-enough-for-hs512");
        props.getJwt().setExpirationMinutes(15);

        jwtService = new JwtServiceImpl(props);
        jwtService.init();
    }

    @Test
    void generatedTokenHasCorrectSubject() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, List.of("user:read"), Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);
        assertThat(jwtService.extractUserId(claims)).isEqualTo(userId);
    }

    @Test
    void subjectIsUuidNotEmail() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, List.of(), Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);
        assertThat(claims.getSubject()).doesNotContain("@");
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
    }

    @Test
    void authoritiesClaimContainsExpectedPermissions() {
        List<String> perms = List.of("user:read", "role:create");
        String token = jwtService.generateToken(UUID.randomUUID(), perms, Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);
        List<String> extracted = jwtService.extractAuthorities(claims);
        assertThat(extracted).containsExactlyInAnyOrderElementsOf(perms);
    }

    @Test
    void authoritiesClaimContainsNoPii() {
        String token = jwtService.generateToken(UUID.randomUUID(), List.of("user:read"), Instant.now());
        assertThat(token).doesNotContain("@");
        Claims claims = jwtService.validateAndParseClaims(token);
        assertThat(claims.getSubject()).doesNotContain("email");
        assertThat(claims.getSubject()).doesNotContain("name");
    }

    @Test
    void expiredTokenThrowsTokenExpiredException() {
        AppProperties shortProps = new AppProperties();
        shortProps.getJwt().setSecret(
            "test-jwt-secret-key-for-testing-purposes-only-must-be-long-enough-for-hs512");
        shortProps.getJwt().setExpirationMinutes(-1);
        JwtServiceImpl shortLivedService = new JwtServiceImpl(shortProps);
        shortLivedService.init();

        String token = shortLivedService.generateToken(UUID.randomUUID(), List.of(), Instant.now());

        assertThatThrownBy(() -> jwtService.validateAndParseClaims(token))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void tamperedSignatureThrowsAuthException() {
        String token = jwtService.generateToken(UUID.randomUUID(), List.of(), Instant.now());
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThatThrownBy(() -> jwtService.validateAndParseClaims(tampered))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void issuedAtIsExtractedCorrectly() {
        Instant before = Instant.now().minusSeconds(1);
        String token = jwtService.generateToken(UUID.randomUUID(), List.of(), Instant.now());
        Claims claims = jwtService.validateAndParseClaims(token);
        Instant issuedAt = jwtService.extractIssuedAt(claims);
        assertThat(issuedAt).isAfter(before);
    }

    @Test
    void shortSecretCausesStartupFailure() {
        AppProperties shortSecretProps = new AppProperties();
        shortSecretProps.getJwt().setSecret("tooshort");
        JwtServiceImpl service = new JwtServiceImpl(shortSecretProps);
        assertThatThrownBy(service::init).isInstanceOf(IllegalStateException.class);
    }
}