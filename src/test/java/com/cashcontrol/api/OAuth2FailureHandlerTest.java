package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.repository.LoginAttemptRepository;
import com.cashcontrol.api.repository.OauthAccountRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.cashcontrol.api.security.oauth2.OAuth2AuthenticationFailureHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.AuthenticationException;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OAuth2FailureHandlerTest {

    @InjectMocks
    private OAuth2AuthenticationFailureHandler handler;

    @Mock private AuditService auditService;
    @Mock private AppProperties appProperties;
    @Mock private CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private OauthAccountRepository oauthAccountRepository;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private AuthenticationException authenticationException;

    @BeforeEach
    void setUp() {
        when(appProperties.getOauth2FailureRedirectUrl()).thenReturn("http://localhost:3000/auth/oauth2/error");
        when(authenticationException.getMessage()).thenReturn("OAuth2 error");
    }

    @Test
    void onAuthenticationFailure_recordsAuditEvent_redirectsToFailureUrl() throws Exception {
        handler.onAuthenticationFailure(request, response, authenticationException);

        verify(auditService).record(
                eq(AuditEventSlug.AUTH_FAILURE),
                eq(AuditOutcomeSlug.FAILURE),
                eq(null),
                eq(null),
                eq(Map.of("provider", "google")));
        verify(response).sendRedirect(contains("?error=oauth_failed"));
    }

    @Test
    void onAuthenticationFailure_noDbWrites() throws Exception {
        handler.onAuthenticationFailure(request, response, authenticationException);

        verify(userRepository, never()).save(any());
        verify(oauthAccountRepository, never()).save(any());
    }

    @Test
    void onAuthenticationFailure_clearsCookie() throws Exception {
        handler.onAuthenticationFailure(request, response, authenticationException);

        verify(cookieOAuth2AuthorizationRequestRepository).clearCookie(request, response);
    }
}
