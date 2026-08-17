package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.audit.RequestContext;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.PasswordResetToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.TokenAlreadyConsumedException;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.repository.PasswordResetTokenRepository;
import com.cashcontrol.api.repository.UserRepository;
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
public class PasswordResetServiceImpl implements PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AuditService auditService;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final DataMasker dataMasker;

    @Override
    @Transactional
    public void initiateReset(String email) {
        userRepository.findByEmailAndDeletedAtIsNull(email).ifPresent(user -> {
            String statusSlug = user.getAccountStatus().getSlug();
            if ("INACTIVE".equals(statusSlug) || user.getDeletedAt() != null) {
                return;
            }

            passwordResetTokenRepository.invalidateActiveTokensForUser(user.getId());

            String rawToken = UUID.randomUUID().toString();
            PasswordResetToken token = new PasswordResetToken();
            token.setUser(user);
            token.setTokenHash(TokenHasher.sha256(rawToken));
            token.setExpiresAt(Instant.now().plus(
                    appProperties.getSecurity().getPasswordResetExpiryMinutes(), ChronoUnit.MINUTES));
            token.setIpAddressMasked(dataMasker.maskIp(RequestContext.getIp()));
            passwordResetTokenRepository.save(token);

            emailService.sendPasswordResetEmail(user.getEmail(), rawToken, user.getDisplayName());

            auditService.record(AuditEventSlug.PASSWORD_RESET_REQUESTED, AuditOutcomeSlug.SUCCESS,
                    null, user.getId(),
                    Map.of("email", dataMasker.maskEmail(user.getEmail())));
        });
        // Always void return — anti-enumeration
    }

    @Override
    @Transactional
    public void completeReset(String rawToken, String newPassword) {
        String hash = TokenHasher.sha256(rawToken);

        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHashAndConsumedAtIsNullAndInvalidatedAtIsNull(hash)
                .orElseThrow(() -> new TokenExpiredException("Reset token is invalid or has expired."));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenExpiredException("Reset token has expired.");
        }

        token.setConsumedAt(Instant.now());
        passwordResetTokenRepository.save(token);

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);

        refreshTokenService.revokeAllActiveForUser(user.getId());

        auditService.record(AuditEventSlug.PASSWORD_RESET_COMPLETED, AuditOutcomeSlug.SUCCESS,
                null, user.getId());
        auditService.record(AuditEventSlug.CREDENTIALS_INVALIDATED, AuditOutcomeSlug.SUCCESS,
                null, user.getId(), Map.of("reason", "PASSWORD_RESET"));
    }
}