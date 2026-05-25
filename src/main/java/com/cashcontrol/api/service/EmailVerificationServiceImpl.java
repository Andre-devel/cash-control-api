package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.EmailVerificationToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.util.DataMasker;
import com.cashcontrol.api.util.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final LookupCache lookupCache;
    private final AuditService auditService;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final DataMasker dataMasker;

    @Override
    @Transactional
    public void verifyEmail(String rawToken) {
        String hash = TokenHasher.sha256(rawToken);

        EmailVerificationToken token = emailVerificationTokenRepository
                .findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(hash)
                .orElseThrow(() -> new TokenExpiredException("Verification token is invalid or has expired."));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenExpiredException("Verification token has expired.");
        }

        token.setConsumedAt(Instant.now());
        emailVerificationTokenRepository.save(token);

        User user = token.getUser();

        if (token.getNewEmail() != null) {
            // Email-change verification flow
            user.setEmail(token.getNewEmail());
            user.setCredentialsUpdatedAt(Instant.now());
        } else {
            // Initial registration verification
            user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
            user.setEmailVerifiedAt(Instant.now());
        }

        userRepository.save(user);

        auditService.record(AuditEventSlug.EMAIL_VERIFIED, AuditOutcomeSlug.SUCCESS,
                null, user.getId(),
                Map.of("email", dataMasker.maskEmail(user.getEmail())));
    }

    @Override
    @Transactional
    public void resendVerification(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElse(null);

        // Anti-enumeration: silent if not found or already verified
        if (user == null || user.getEmailVerifiedAt() != null) {
            return;
        }

        if (!UserSlugConstants.STATUS_PENDING_VERIFICATION.equals(user.getAccountStatus().getSlug())) {
            return;
        }

        emailVerificationTokenRepository.invalidateActiveTokensForUser(user.getId());

        String rawToken = UUID.randomUUID().toString();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(TokenHasher.sha256(rawToken));
        token.setExpiresAt(Instant.now().plus(
                appProperties.getSecurity().getEmailVerificationExpiryHours(), ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(token);

        emailService.sendEmailVerification(user.getEmail(), rawToken, user.getDisplayName());

        auditService.record(AuditEventSlug.USER_REGISTERED, AuditOutcomeSlug.SUCCESS,
                null, user.getId(),
                Map.of("action", "RESEND_VERIFICATION"));
    }

    @Override
    @Transactional
    public void initiateEmailChange(UUID userId, String newEmail) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        // Anti-enumeration: silent if target email already taken
        if (userRepository.existsByEmailAndDeletedAtIsNull(newEmail)) {
            return;
        }

        emailVerificationTokenRepository.invalidateActiveTokensForUser(userId);

        String rawToken = UUID.randomUUID().toString();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(user);
        token.setTokenHash(TokenHasher.sha256(rawToken));
        token.setNewEmail(newEmail);
        token.setExpiresAt(Instant.now().plus(
                appProperties.getSecurity().getEmailVerificationExpiryHours(), ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(token);

        emailService.sendEmailChangeVerification(newEmail, rawToken);
    }
}