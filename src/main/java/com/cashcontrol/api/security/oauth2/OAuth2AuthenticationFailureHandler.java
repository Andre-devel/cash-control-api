package com.cashcontrol.api.security.oauth2;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final AuditService auditService;
    private final AppProperties appProperties;
    private final CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.warn("OAuth2 authentication failure: {}", exception.getMessage());

        cookieOAuth2AuthorizationRequestRepository.clearCookie(request, response);

        auditService.record(AuditEventSlug.AUTH_FAILURE, AuditOutcomeSlug.FAILURE, null, null,
                Map.of("provider", "google"));

        response.sendRedirect(appProperties.getOauth2FailureRedirectUrl() + "?error=oauth_failed");
    }
}
