package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.response.AuthResponse;

/**
 * Carries the pair produced by a login or a refresh. The refresh token is deliberately
 * kept out of {@link AuthResponse} so it can only ever leave through the httpOnly cookie.
 */
public record AuthTokens(AuthResponse response, String refreshToken) {}
