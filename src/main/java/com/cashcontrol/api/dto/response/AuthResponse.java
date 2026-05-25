package com.cashcontrol.api.dto.response;

public record AuthResponse(
        String accessToken,
        String tokenType,
        int expiresInSeconds
) {
    public static AuthResponse of(String accessToken, int expiresInSeconds) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds);
    }
}