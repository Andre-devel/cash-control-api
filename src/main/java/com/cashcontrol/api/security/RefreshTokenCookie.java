package com.cashcontrol.api.security;

import com.cashcontrol.api.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The refresh token lives in a cookie the browser cannot read, so a XSS payload
 * cannot exfiltrate it the way it could a token kept in localStorage. Frontend and
 * API share a host in every environment, so SameSite=Lax is enough.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookie {

    public static final String NAME = "cash_control_refresh";

    /** Narrow path: the cookie never travels to the financial endpoints that do not need it. */
    private static final String PATH = "/api/v1/auth";

    private final AppProperties appProperties;

    public ResponseCookie build(String rawToken) {
        return baseBuilder(rawToken)
                .maxAge(Duration.ofDays(appProperties.getJwt().getRefreshExpirationDays()))
                .build();
    }

    public ResponseCookie clear() {
        return baseBuilder("").maxAge(0).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(appProperties.getJwt().isRefreshCookieSecure())
                .sameSite("Lax")
                .path(PATH);
    }
}
