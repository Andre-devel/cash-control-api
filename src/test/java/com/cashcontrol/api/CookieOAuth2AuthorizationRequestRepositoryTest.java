package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.security.oauth2.CookieOAuth2AuthorizationRequestRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.util.SerializationUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CookieOAuth2AuthorizationRequestRepositoryTest {

    private static final String COOKIE_NAME = "OAUTH2_AUTH_REQUEST";

    private CookieOAuth2AuthorizationRequestRepository repository;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.getJwt().setSecret("a".repeat(64));
        repository = new CookieOAuth2AuthorizationRequestRepository(appProperties);
    }

    @Test
    void roundTripsASignedAuthorizationRequest() {
        OAuth2AuthorizationRequest original = authorizationRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), response);

        Cookie saved = response.getCookie(COOKIE_NAME);
        assertThat(saved).isNotNull();
        assertThat(saved.getSecure()).isTrue();
        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(requestWith(saved.getValue()));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getState()).isEqualTo(original.getState());
    }

    @Test
    void rejectsUnsignedSerializedCookie() {
        String unsigned = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(SerializationUtils.serialize(authorizationRequest()));

        assertThat(repository.loadAuthorizationRequest(requestWith(unsigned))).isNull();
    }

    @Test
    void rejectsCookieWithTamperedPayload() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(authorizationRequest(), new MockHttpServletRequest(), response);
        String signed = response.getCookie(COOKIE_NAME).getValue();
        String mac = signed.substring(signed.indexOf('.'));
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(SerializationUtils.serialize(new java.util.HashMap<String, String>()));

        assertThat(repository.loadAuthorizationRequest(requestWith(forgedPayload + mac))).isNull();
    }

    private static MockHttpServletRequest requestWith(String cookieValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, cookieValue));
        return request;
    }

    private static OAuth2AuthorizationRequest authorizationRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .state("state-123")
                .build();
    }
}
