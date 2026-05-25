package com.cashcontrol.api.security;

import io.jsonwebtoken.Claims;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JwtService {

    String generateToken(UUID userId, List<String> authorities, Instant credentialsUpdatedAt);

    Claims validateAndParseClaims(String token);

    UUID extractUserId(Claims claims);

    List<String> extractAuthorities(Claims claims);

    Instant extractIssuedAt(Claims claims);
}