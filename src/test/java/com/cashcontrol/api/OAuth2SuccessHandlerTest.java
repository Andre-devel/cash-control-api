package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.OauthAccount;
import com.cashcontrol.api.domain.entity.OauthProvider;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.OAuthProviderException;
import com.cashcontrol.api.repository.LoginAttemptRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.OauthAccountRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.security.PermissionResolver;
import com.cashcontrol.api.security.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.cashcontrol.api.security.oauth2.OAuth2AuthenticationSuccessHandler;
import com.cashcontrol.api.security.oauth2.OAuth2UserInfo;
import com.cashcontrol.api.security.oauth2.OAuth2UserInfoExtractor;
import com.cashcontrol.api.service.AccountStatusChecker;
import com.cashcontrol.api.util.DataMasker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuth2SuccessHandlerTest {

    @InjectMocks
    private OAuth2AuthenticationSuccessHandler handler;

    @Mock private UserRepository userRepository;
    @Mock private OauthAccountRepository oauthAccountRepository;
    @Mock private LookupCache lookupCache;
    @Mock private JwtService jwtService;
    @Mock private PermissionResolver permissionResolver;
    @Mock private AuditService auditService;
    @Mock private AccountStatusChecker accountStatusChecker;
    @Mock private AppProperties appProperties;
    @Mock private DataMasker dataMasker;
    @Mock private LoginAttemptRepository loginAttemptRepository;
    @Mock private CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;
    @Mock private OAuth2UserInfoExtractor oAuth2UserInfoExtractor;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private Authentication authentication;
    @Mock private OAuth2User oAuth2User;

    private OauthProvider googleProvider;
    private AppProperties.Security securityProps;

    @BeforeEach
    void setUp() {
        googleProvider = new OauthProvider();
        ReflectionTestUtils.setField(googleProvider, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(googleProvider, "slug", "GOOGLE");

        securityProps = new AppProperties.Security();
        when(appProperties.getSecurity()).thenReturn(securityProps);
        when(appProperties.getOauth2SuccessRedirectUrl()).thenReturn("http://localhost:3000/auth/oauth2/callback");
        when(appProperties.getOauth2FailureRedirectUrl()).thenReturn("http://localhost:3000/auth/oauth2/error");

        when(lookupCache.requireOauthProvider("GOOGLE")).thenReturn(googleProvider);
        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        when(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_GOOGLE))
                .thenReturn(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_GOOGLE));
        when(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_MIXED))
                .thenReturn(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_MIXED));
        when(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_LOCAL))
                .thenReturn(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        when(lookupCache.requireAuthenticationMethod("GOOGLE_OAUTH2"))
                .thenReturn(new com.cashcontrol.api.domain.entity.AuthenticationMethod());

        when(authentication.getPrincipal()).thenReturn(oAuth2User);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("TestAgent/1.0");
        when(dataMasker.maskIp(anyString())).thenReturn("127.0.0.0");
        when(dataMasker.truncateUserAgent(anyString(), anyInt())).thenReturn("TestAgent/1.0");
        when(dataMasker.maskEmail(anyString())).thenReturn("t***@example.com");
        when(jwtService.generateToken(any(), any(), any())).thenReturn("jwt.token.here");
        when(permissionResolver.resolveEffectivePermissions(any())).thenReturn(List.of("user:read"));
        when(accountStatusChecker.hasExpiredAutoLockout(any())).thenReturn(false);
        doNothing().when(accountStatusChecker).checkAuthenticationEligibility(any());
    }

    @Test
    void newGoogleUser_createsUserAndOauthAccount_redirectsWithToken() throws Exception {
        OAuth2UserInfo userInfo = new OAuth2UserInfo("new@example.com", "google-sub-123", "New User");
        when(oAuth2UserInfoExtractor.extract(any())).thenReturn(userInfo);

        when(oauthAccountRepository.findByProviderIdAndProviderUserIdAndUnlinkedAtIsNull(any(), anyString()))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmailAndDeletedAtIsNull("new@example.com"))
                .thenReturn(Optional.empty());

        User savedUser = buildActiveUser(UserSlugConstants.ORIGIN_GOOGLE);
        when(userRepository.save(any())).thenReturn(savedUser);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(userRepository).save(any(User.class));
        verify(oauthAccountRepository, times(1)).save(any(OauthAccount.class));
        verify(auditService).record(eq(AuditEventSlug.USER_REGISTERED_GOOGLE), eq(AuditOutcomeSlug.SUCCESS),
                eq(null), any(UUID.class), any());
        verify(auditService).record(eq(AuditEventSlug.AUTH_SUCCESS), eq(AuditOutcomeSlug.SUCCESS),
                any(UUID.class), any(UUID.class));
        verify(response).sendRedirect(contains("?token=jwt.token.here"));
    }

    @Test
    void existingLocalUser_linksOauthAccount_setsOriginToMixed() throws Exception {
        OAuth2UserInfo userInfo = new OAuth2UserInfo("local@example.com", "google-sub-456", "Local User");
        when(oAuth2UserInfoExtractor.extract(any())).thenReturn(userInfo);

        when(oauthAccountRepository.findByProviderIdAndProviderUserIdAndUnlinkedAtIsNull(any(), anyString()))
                .thenReturn(Optional.empty());

        User localUser = buildActiveUser(UserSlugConstants.ORIGIN_LOCAL);
        when(userRepository.findByEmailAndDeletedAtIsNull("local@example.com"))
                .thenReturn(Optional.of(localUser));
        when(userRepository.save(any())).thenReturn(localUser);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(localUser.getAuthOrigin().getSlug()).isEqualTo(UserSlugConstants.ORIGIN_MIXED);
        verify(oauthAccountRepository).save(any(OauthAccount.class));
        verify(auditService).record(eq(AuditEventSlug.ACCOUNT_LINKED_GOOGLE), eq(AuditOutcomeSlug.SUCCESS),
                any(UUID.class), any(UUID.class), any());
        verify(response).sendRedirect(contains("?token=jwt.token.here"));
    }

    @Test
    void returningGoogleUser_updatesLastUsedAt_redirectsWithToken() throws Exception {
        OAuth2UserInfo userInfo = new OAuth2UserInfo("returning@example.com", "google-sub-789", "Returning User");
        when(oAuth2UserInfoExtractor.extract(any())).thenReturn(userInfo);

        User existingUser = buildActiveUser(UserSlugConstants.ORIGIN_GOOGLE);
        OauthAccount existingAccount = buildOauthAccount(existingUser);
        when(oauthAccountRepository.findByProviderIdAndProviderUserIdAndUnlinkedAtIsNull(any(), anyString()))
                .thenReturn(Optional.of(existingAccount));
        when(oauthAccountRepository.save(any())).thenReturn(existingAccount);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(existingAccount.getLastUsedAt()).isNotNull();
        verify(oauthAccountRepository).save(existingAccount);
        verify(auditService).record(eq(AuditEventSlug.AUTH_SUCCESS), eq(AuditOutcomeSlug.SUCCESS),
                any(UUID.class), any(UUID.class));
        verify(response).sendRedirect(contains("?token=jwt.token.here"));
    }

    @Test
    void missingEmailInGoogleProfile_redirectsToFailureUrl() throws Exception {
        when(oAuth2UserInfoExtractor.extract(any()))
                .thenThrow(new OAuthProviderException("Email not provided by OAuth2 provider."));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(userRepository, never()).save(any());
        verify(oauthAccountRepository, never()).save(any());
        verify(response).sendRedirect(contains("?error=oauth_failed"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User buildActiveUser(String originSlug) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(originSlug));
        user.setEmailVerifiedAt(Instant.now());
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }

    private OauthAccount buildOauthAccount(User user) {
        OauthAccount account = new OauthAccount();
        account.setUser(user);
        account.setProvider(googleProvider);
        account.setProviderUserId("google-sub-789");
        account.setProviderEmail("returning@example.com");
        account.setLinkedAt(Instant.now());
        return account;
    }
}
