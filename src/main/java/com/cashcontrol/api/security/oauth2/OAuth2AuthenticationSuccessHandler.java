package com.cashcontrol.api.security.oauth2;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.LoginAttempt;
import com.cashcontrol.api.domain.entity.OauthAccount;
import com.cashcontrol.api.domain.entity.OauthProvider;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.OAuthProviderException;
import com.cashcontrol.api.repository.LoginAttemptRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.OauthAccountRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.CorrelationIdHolder;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.security.PermissionResolver;
import com.cashcontrol.api.security.RefreshTokenCookie;
import com.cashcontrol.api.service.AccountStatusChecker;
import com.cashcontrol.api.service.RefreshTokenService;
import com.cashcontrol.api.util.DataMasker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final OauthAccountRepository oauthAccountRepository;
    private final LookupCache lookupCache;
    private final JwtService jwtService;
    private final PermissionResolver permissionResolver;
    private final AuditService auditService;
    private final AccountStatusChecker accountStatusChecker;
    private final AppProperties appProperties;
    private final DataMasker dataMasker;
    private final LoginAttemptRepository loginAttemptRepository;
    private final CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;
    private final OAuth2UserInfoExtractor oAuth2UserInfoExtractor;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookie refreshTokenCookie;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        cookieOAuth2AuthorizationRequestRepository.clearCookie(request, response);

        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        OAuth2UserInfo userInfo;
        try {
            userInfo = oAuth2UserInfoExtractor.extract(oAuth2User);
        } catch (OAuthProviderException ex) {
            log.warn("OAuth2 user info extraction failed: {}", ex.getMessage());
            response.sendRedirect(appProperties.getOauth2FailureRedirectUrl() + "?error=oauth_failed");
            return;
        }

        OauthProvider provider = lookupCache.requireOauthProvider("GOOGLE");
        UUID correlationId = CorrelationIdHolder.get();

        User user = resolveUser(userInfo, provider);

        accountStatusChecker.checkAuthenticationEligibility(user);

        if (accountStatusChecker.hasExpiredAutoLockout(user)) {
            user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
            user.setLockoutType(null);
            user.setLockoutExpiresAt(null);
            user.setLockoutReason(null);
            userRepository.save(user);
        }

        List<String> authorities = permissionResolver.resolveEffectivePermissions(user.getId());
        String jwt = jwtService.generateToken(user.getId(), authorities, user.getCredentialsUpdatedAt());

        auditService.record(AuditEventSlug.AUTH_SUCCESS, AuditOutcomeSlug.SUCCESS, user.getId(), user.getId());

        LoginAttempt attempt = new LoginAttempt();
        attempt.setUserId(user.getId());
        attempt.setAuthMethod(lookupCache.requireAuthenticationMethod("GOOGLE_OAUTH2"));
        attempt.setIpAddressMasked(dataMasker.maskIp(request.getRemoteAddr()) != null
                ? dataMasker.maskIp(request.getRemoteAddr()) : "");
        attempt.setUserAgentTruncated(
                dataMasker.truncateUserAgent(request.getHeader("User-Agent"), 512));
        attempt.setWasSuccessful(true);
        attempt.setCorrelationId(correlationId);
        loginAttemptRepository.save(attempt);

        String refreshToken = refreshTokenService.issue(
                user, request.getRemoteAddr(), request.getHeader("User-Agent"));
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.build(refreshToken).toString());

        response.sendRedirect(appProperties.getOauth2SuccessRedirectUrl() + "?token=" + jwt);
    }

    private User resolveUser(OAuth2UserInfo userInfo, OauthProvider provider) {
        return oauthAccountRepository
                .findByProviderIdAndProviderUserIdAndUnlinkedAtIsNull(provider.getId(), userInfo.providerUserId())
                .map(oauthAccount -> {
                    oauthAccount.setLastUsedAt(Instant.now());
                    oauthAccountRepository.save(oauthAccount);
                    return oauthAccount.getUser();
                })
                .orElseGet(() -> resolveNewOAuthUser(userInfo, provider));
    }

    private User resolveNewOAuthUser(OAuth2UserInfo userInfo, OauthProvider provider) {
        return userRepository.findByEmailAndDeletedAtIsNull(userInfo.email())
                .map(existingUser -> linkExistingUser(existingUser, userInfo, provider))
                .orElseGet(() -> createNewUser(userInfo, provider));
    }

    private User linkExistingUser(User existingUser, OAuth2UserInfo userInfo, OauthProvider provider) {
        existingUser.setAuthOrigin(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_MIXED));
        userRepository.save(existingUser);

        OauthAccount oauthAccount = buildOauthAccount(existingUser, provider, userInfo);
        oauthAccountRepository.save(oauthAccount);

        auditService.record(AuditEventSlug.ACCOUNT_LINKED_GOOGLE, AuditOutcomeSlug.SUCCESS,
                existingUser.getId(), existingUser.getId(),
                Map.of("provider", "GOOGLE",
                        "email", dataMasker.maskEmail(userInfo.email())));

        return existingUser;
    }

    private User createNewUser(OAuth2UserInfo userInfo, OauthProvider provider) {
        User newUser = new User();
        newUser.setEmail(userInfo.email());
        newUser.setDisplayName(userInfo.displayName());
        newUser.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        newUser.setAuthOrigin(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_GOOGLE));
        newUser.setEmailVerifiedAt(Instant.now());
        newUser.setCredentialsUpdatedAt(Instant.now());
        User savedUser = userRepository.save(newUser);

        OauthAccount oauthAccount = buildOauthAccount(savedUser, provider, userInfo);
        oauthAccountRepository.save(oauthAccount);

        auditService.record(AuditEventSlug.USER_REGISTERED_GOOGLE, AuditOutcomeSlug.SUCCESS,
                null, savedUser.getId(),
                Map.of("provider", "GOOGLE",
                        "email", dataMasker.maskEmail(userInfo.email())));

        return savedUser;
    }

    private OauthAccount buildOauthAccount(User user, OauthProvider provider, OAuth2UserInfo userInfo) {
        OauthAccount account = new OauthAccount();
        account.setUser(user);
        account.setProvider(provider);
        account.setProviderUserId(userInfo.providerUserId());
        account.setProviderEmail(userInfo.email());
        account.setDisplayName(userInfo.displayName());
        account.setLinkedAt(Instant.now());
        account.setLastUsedAt(Instant.now());
        return account;
    }
}
