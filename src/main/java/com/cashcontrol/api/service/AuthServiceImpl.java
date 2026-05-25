package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.audit.RequestContext;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.EmailVerificationToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.entity.UserConsent;
import com.cashcontrol.api.domain.exception.AuthException;
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
import com.cashcontrol.api.util.DataMasker;
import com.cashcontrol.api.util.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String CONSENT_VERSION = "1.0";

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final UserConsentRepository userConsentRepository;
    private final LookupCache lookupCache;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PermissionResolver permissionResolver;
    private final AuditService auditService;
    private final AccountStatusChecker accountStatusChecker;
    private final BruteForceProtectionService bruteForceService;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final DataMasker dataMasker;

    @Override
    @Transactional
    public MessageResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            // Anti-enumeration: dispatch "account exists" email but return same success response
            emailService.sendAccountAlreadyExistsEmail(request.email());
            return MessageResponse.of("Registration successful. Please check your inbox for an email verification link.");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_PENDING_VERIFICATION));
        user.setAuthOrigin(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        user.setConsentAcceptedAt(Instant.now());
        user.setConsentVersion(CONSENT_VERSION);
        User saved = userRepository.save(user);

        UserConsent consent = new UserConsent();
        consent.setUser(saved);
        consent.setConsentVersion(CONSENT_VERSION);
        consent.setAcceptedAt(Instant.now());
        consent.setIpAddressMasked(dataMasker.maskIp(RequestContext.getIp()));
        consent.setUserAgentTruncated(dataMasker.truncateUserAgent(RequestContext.getUserAgent(), 512));
        userConsentRepository.save(consent);

        String rawToken = UUID.randomUUID().toString();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(saved);
        token.setTokenHash(TokenHasher.sha256(rawToken));
        token.setExpiresAt(Instant.now().plus(
                appProperties.getSecurity().getEmailVerificationExpiryHours(), ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(token);

        emailService.sendEmailVerification(saved.getEmail(), rawToken, saved.getDisplayName());

        auditService.record(AuditEventSlug.CONSENT_ACCEPTED, AuditOutcomeSlug.SUCCESS, null, saved.getId(),
                Map.of("consentVersion", CONSENT_VERSION));
        auditService.record(AuditEventSlug.USER_REGISTERED, AuditOutcomeSlug.SUCCESS, null, saved.getId(),
                Map.of("email", dataMasker.maskEmail(saved.getEmail())));

        return MessageResponse.of("Registration successful. Please check your inbox for an email verification link.");
    }

    @Override
    @Transactional(noRollbackFor = AuthException.class)
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String maskedIp = dataMasker.maskIp(ipAddress);
        String truncatedUa = dataMasker.truncateUserAgent(userAgent, 512);

        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email()).orElse(null);

        if (user == null) {
            bruteForceService.recordAttempt(null, maskedIp, truncatedUa, "PASSWORD", false, "INVALID_CREDENTIALS");
            auditService.record(AuditEventSlug.AUTH_FAILURE, AuditOutcomeSlug.FAILURE, null, null);
            throw new InvalidCredentialsException("Authentication failed.");
        }

        if (bruteForceService.isAccountLocked(user)) {
            bruteForceService.recordAttempt(user.getId(), maskedIp, truncatedUa, "PASSWORD", false, "ACCOUNT_LOCKED");
            auditService.record(AuditEventSlug.AUTH_FAILURE, AuditOutcomeSlug.FAILURE, null, user.getId());
            throw new InvalidCredentialsException("Authentication failed.");
        }

        try {
            accountStatusChecker.checkAuthenticationEligibility(user);
        } catch (InvalidCredentialsException ex) {
            bruteForceService.recordAttempt(user.getId(), maskedIp, truncatedUa, "PASSWORD", false, "ACCOUNT_STATUS");
            auditService.record(AuditEventSlug.AUTH_FAILURE, AuditOutcomeSlug.FAILURE, null, user.getId());
            throw ex;
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            bruteForceService.recordAttempt(user.getId(), maskedIp, truncatedUa, "PASSWORD", false, "INVALID_CREDENTIALS");
            bruteForceService.incrementFailedAttempts(user);
            auditService.record(AuditEventSlug.AUTH_FAILURE, AuditOutcomeSlug.FAILURE, null, user.getId());
            throw new InvalidCredentialsException("Authentication failed.");
        }

        // Successful authentication
        bruteForceService.resetFailedAttempts(user);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        bruteForceService.recordAttempt(user.getId(), maskedIp, truncatedUa, "PASSWORD", true, null);

        var authorities = permissionResolver.resolveEffectivePermissions(user.getId());
        String token = jwtService.generateToken(user.getId(), authorities, user.getCredentialsUpdatedAt());

        auditService.record(AuditEventSlug.AUTH_SUCCESS, AuditOutcomeSlug.SUCCESS, user.getId(), user.getId());

        return AuthResponse.of(token, appProperties.getJwt().getExpirationMinutes() * 60);
    }

    @Override
    public void logout(UUID userId) {
        auditService.record(AuditEventSlug.AUTH_LOGOUT, AuditOutcomeSlug.SUCCESS, userId, userId);
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect.");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("New password must differ from the current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);

        auditService.record(AuditEventSlug.PASSWORD_CHANGED, AuditOutcomeSlug.SUCCESS, userId, userId);
        auditService.record(AuditEventSlug.CREDENTIALS_INVALIDATED, AuditOutcomeSlug.SUCCESS, userId, userId,
                Map.of("reason", "PASSWORD_CHANGED"));
    }
}
