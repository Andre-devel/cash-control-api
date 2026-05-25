package com.cashcontrol.api.security;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.exception.AuthException;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private static final String AUTHORITIES_CLAIM = "authorities";
    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final int MIN_SECRET_LENGTH = 64;
    private static final int MAX_TOKEN_SIZE_BYTES = 4096;

    private final AppProperties appProperties;
    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        String secret = appProperties.getJwt().getSecret();
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret must be at least " + MIN_SECRET_LENGTH + " characters. " +
                    "Generate with: openssl rand -hex 64");
        }
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String generateToken(UUID userId, List<String> authorities, Instant credentialsUpdatedAt) {
        Instant now = Instant.now();
        Instant expiration = now.plus(appProperties.getJwt().getExpirationMinutes(), ChronoUnit.MINUTES);

        String token = Jwts.builder()
                .subject(userId.toString())
                .claim(AUTHORITIES_CLAIM, authorities)
                .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(signingKey)
                .compact();

        if (token.length() > MAX_TOKEN_SIZE_BYTES) {
            log.warn("Generated JWT exceeds {} bytes — authority set may be too large", MAX_TOKEN_SIZE_BYTES);
        }

        return token;
    }

    @Override
    public Claims validateAndParseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("JWT token has expired.");
        } catch (JwtException e) {
            throw new AuthException("JWT token is invalid.");
        }
    }

    @Override
    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> extractAuthorities(Claims claims) {
        Object raw = claims.get(AUTHORITIES_CLAIM);
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @Override
    public Instant extractIssuedAt(Claims claims) {
        return claims.getIssuedAt().toInstant();
    }
}