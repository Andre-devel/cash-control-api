package com.cashcontrol.api.security.oauth2;

import com.cashcontrol.api.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Keeps the OAuth2 authorization request in a cookie (the API is stateless). The cookie is
 * Java-serialized, so it carries an HMAC and is only deserialized after the MAC matches:
 * the client must never be able to feed arbitrary bytes into an ObjectInputStream.
 */
@Component
public class CookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "OAUTH2_AUTH_REQUEST";
    private static final int COOKIE_MAX_AGE_SECONDS = 180;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    // Derives a dedicated key from the JWT secret so this MAC can never be confused with a JWT signature
    private static final String KEY_DERIVATION_LABEL = "cash-control:oauth2-authorization-request";

    private final AppProperties appProperties;
    private final SecretKeySpec macKey;

    public CookieOAuth2AuthorizationRequestRepository(AppProperties appProperties) {
        this.appProperties = appProperties;
        byte[] secret = appProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        byte[] derived = hmac(new SecretKeySpec(secret, HMAC_ALGORITHM),
                KEY_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
        macKey = new SecretKeySpec(derived, HMAC_ALGORITHM);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookieValue(request)
                .map(this::deserialize)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (authorizationRequest == null) {
            deleteCookie(request, response);
            return;
        }

        String serialized = serialize(authorizationRequest);
        Cookie cookie = new Cookie(COOKIE_NAME, serialized);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(appProperties.getJwt().isRefreshCookieSecure());
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {

        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        deleteCookie(request, response);
        return authorizationRequest;
    }

    public void clearCookie(HttpServletRequest request, HttpServletResponse response) {
        deleteCookie(request, response);
    }

    private void deleteCookie(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) {
            Arrays.stream(request.getCookies())
                    .filter(c -> COOKIE_NAME.equals(c.getName()))
                    .findFirst()
                    .ifPresent(c -> {
                        Cookie expired = new Cookie(COOKIE_NAME, "");
                        expired.setPath("/");
                        expired.setHttpOnly(true);
                        expired.setSecure(appProperties.getJwt().isRefreshCookieSecure());
                        expired.setMaxAge(0);
                        expired.setAttribute("SameSite", "Lax");
                        response.addCookie(expired);
                    });
        }
    }

    private java.util.Optional<String> getCookieValue(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return java.util.Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        byte[] bytes = SerializationUtils.serialize(authorizationRequest);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes) + "." + encoder.encodeToString(hmac(macKey, bytes));
    }

    @SuppressWarnings("deprecation") // safe: only bytes this server signed reach deserialize
    private OAuth2AuthorizationRequest deserialize(String value) {
        try {
            int dot = value.indexOf('.');
            if (dot < 0) {
                return null;
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] bytes = decoder.decode(value.substring(0, dot));
            byte[] mac = decoder.decode(value.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(macKey, bytes), mac)) {
                return null;
            }
            return (OAuth2AuthorizationRequest) SerializationUtils.deserialize(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
