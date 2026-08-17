package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.User;

import java.util.UUID;

public interface RefreshTokenService {

    /** Issues a new refresh token in a fresh family and returns the raw value, which is never persisted. */
    String issue(User user, String ipAddress, String userAgent);

    /**
     * Consumes a refresh token and issues its successor in the same family.
     * Re-presenting an already revoked token revokes the whole family.
     */
    RotationResult rotate(String rawToken, String ipAddress, String userAgent);

    void revoke(String rawToken);

    void revokeAllActiveForUser(UUID userId);

    record RotationResult(User user, String rawToken) {}
}
