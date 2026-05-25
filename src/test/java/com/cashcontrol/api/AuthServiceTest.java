package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.ChangePasswordRequest;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.dto.response.AuthResponse;
import com.cashcontrol.api.dto.response.MessageResponse;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserConsentRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.security.PermissionResolver;
import com.cashcontrol.api.service.AccountStatusChecker;
import com.cashcontrol.api.service.AuthServiceImpl;
import com.cashcontrol.api.service.BruteForceProtectionService;
import com.cashcontrol.api.service.EmailService;
import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @InjectMocks private AuthServiceImpl authService;

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private UserConsentRepository userConsentRepository;
    @Mock private LookupCache lookupCache;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private PermissionResolver permissionResolver;
    @Mock private AuditService auditService;
    @Mock private AccountStatusChecker accountStatusChecker;
    @Mock private BruteForceProtectionService bruteForceService;
    @Mock private EmailService emailService;
    @Mock private AppProperties appProperties;
    @Mock private DataMasker dataMasker;

    private AppProperties.Security securityProps;
    private AppProperties.Jwt jwtProps;

    @BeforeEach
    void setUp() {
        securityProps = new AppProperties.Security();
        jwtProps = new AppProperties.Jwt();
        jwtProps.setSecret("x".repeat(64));
        when(appProperties.getSecurity()).thenReturn(securityProps);
        when(appProperties.getJwt()).thenReturn(jwtProps);

        var pendingStatus = TestEntityFactory.accountStatus(UserSlugConstants.STATUS_PENDING_VERIFICATION);
        var activeStatus = TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE);
        var localOrigin = TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL);

        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_PENDING_VERIFICATION)).thenReturn(pendingStatus);
        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE)).thenReturn(activeStatus);
        when(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_LOCAL)).thenReturn(localOrigin);

        when(dataMasker.maskEmail(anyString())).thenAnswer(inv -> "***@masked");
        when(dataMasker.maskIp(any())).thenAnswer(inv -> inv.getArgument(0, String.class));
        when(dataMasker.truncateUserAgent(any(), anyInt())).thenAnswer(inv -> inv.getArgument(0, String.class));
    }

    @Test
    void register_newEmail_createsUserInPendingVerification() {
        when(userRepository.existsByEmailAndDeletedAtIsNull(anyString())).thenReturn(false);
        User saved = buildUser(UserSlugConstants.STATUS_PENDING_VERIFICATION);
        when(userRepository.save(any())).thenReturn(saved);
        when(passwordEncoder.encode(anyString())).thenReturn("$argon2id$hash");

        MessageResponse response = authService.register(new RegisterRequest("user@example.com", "Str0ng!Pass123", true));

        assertThat(response.message()).contains("verification");
        verify(emailService).sendEmailVerification(anyString(), anyString(), any());
    }

    @Test
    void register_existingEmail_sendsAlreadyExistsEmailAndReturnsSameMessage() {
        when(userRepository.existsByEmailAndDeletedAtIsNull(anyString())).thenReturn(true);

        MessageResponse response = authService.register(new RegisterRequest("user@example.com", "Str0ng!Pass123", true));

        assertThat(response.message()).contains("verification");
        verify(emailService).sendAccountAlreadyExistsEmail("user@example.com");
        verify(userRepository, never()).save(any());
    }

    @Test
    void login_userNotFound_throwsInvalidCredentials() {
        when(userRepository.findByEmailAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("missing@x.com", "pass"), "1.1.1.1", "UA"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(bruteForceService).recordAttempt(isNull(), any(), any(), eq("PASSWORD"), eq(false), anyString());
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentialsAndDelegatesIncrement() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        when(userRepository.findByEmailAndDeletedAtIsNull("u@x.com")).thenReturn(Optional.of(user));
        when(bruteForceService.isAccountLocked(user)).thenReturn(false);
        doNothing().when(accountStatusChecker).checkAuthenticationEligibility(user);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("u@x.com", "wrong"), "1.1.1.1", "UA"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(bruteForceService).incrementFailedAttempts(user);
        verify(auditService).record(eq(AuditEventSlug.AUTH_FAILURE), eq(AuditOutcomeSlug.FAILURE), any(), any());
    }

    @Test
    void login_lockedAccount_throwsInvalidCredentials() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        when(userRepository.findByEmailAndDeletedAtIsNull("u@x.com")).thenReturn(Optional.of(user));
        when(bruteForceService.isAccountLocked(user)).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("u@x.com", "any"), null, null))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(bruteForceService, never()).incrementFailedAttempts(any());
    }

    @Test
    void login_correctPassword_resetsAttemptsAndReturnsToken() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setCredentialsUpdatedAt(Instant.now());
        when(userRepository.findByEmailAndDeletedAtIsNull("u@x.com")).thenReturn(Optional.of(user));
        when(bruteForceService.isAccountLocked(user)).thenReturn(false);
        doNothing().when(accountStatusChecker).checkAuthenticationEligibility(user);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(permissionResolver.resolveEffectivePermissions(any())).thenReturn(List.of("user:read"));
        when(jwtService.generateToken(any(), any(), any())).thenReturn("jwt.token.here");

        AuthResponse response = authService.login(new LoginRequest("u@x.com", "correct"), null, null);

        assertThat(response.accessToken()).isEqualTo("jwt.token.here");
        verify(bruteForceService).resetFailedAttempts(user);
        verify(auditService).record(eq(AuditEventSlug.AUTH_SUCCESS), eq(AuditOutcomeSlug.SUCCESS), any(), any());
    }

    @Test
    void logout_recordsAuditEvent() {
        UUID userId = UUID.randomUUID();
        authService.logout(userId);
        verify(auditService).record(eq(AuditEventSlug.AUTH_LOGOUT), eq(AuditOutcomeSlug.SUCCESS), eq(userId), eq(userId));
    }

    @Test
    void changePassword_correctCurrentPassword_updatesCredentialsUpdatedAt() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current", user.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.matches("New!Pass123", user.getPasswordHash())).thenReturn(false);
        when(passwordEncoder.encode("New!Pass123")).thenReturn("$new$hash");

        Instant before = Instant.now();
        authService.changePassword(userId, new ChangePasswordRequest("current", "New!Pass123"));

        assertThat(user.getCredentialsUpdatedAt()).isAfterOrEqualTo(before);
        verify(auditService).record(eq(AuditEventSlug.PASSWORD_CHANGED), any(), any(), any());
        verify(auditService).record(eq(AuditEventSlug.CREDENTIALS_INVALIDATED), any(), any(), any(), any());
    }

    @Test
    void changePassword_wrongCurrentPassword_throws() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(userId, new ChangePasswordRequest("wrong", "New!Pass123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void changePassword_userNotFound_throws() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changePassword(userId, new ChangePasswordRequest("x", "y")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User buildUser(String statusSlug) {
        User user = new User();
        user.setEmail("u@x.com");
        user.setPasswordHash("$argon2id$hash");
        user.setAccountStatus(TestEntityFactory.accountStatus(statusSlug));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }
}
