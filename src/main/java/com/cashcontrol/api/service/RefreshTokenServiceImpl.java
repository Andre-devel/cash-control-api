package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.RefreshToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import com.cashcontrol.api.repository.RefreshTokenRepository;
import com.cashcontrol.api.util.DataMasker;
import com.cashcontrol.api.util.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final DataMasker dataMasker;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    @Override
    @Transactional
    public String issue(User user, String ipAddress, String userAgent) {
        return issueInFamily(user, UUID.randomUUID(), ipAddress, userAgent);
    }

    @Override
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public RotationResult rotate(String rawToken, String ipAddress, String userAgent) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new InvalidCredentialsException("Refresh token is invalid."));

        if (token.getRevokedAt() != null) {
            // Only a leaked cookie can present a token that was already consumed.
            refreshTokenRepository.revokeFamily(token.getFamilyId());
            auditService.record(AuditEventSlug.REFRESH_TOKEN_REUSE_DETECTED, AuditOutcomeSlug.FAILURE,
                    null, token.getUser().getId(),
                    Map.of("familyId", token.getFamilyId().toString()));
            log.warn("Refresh token reuse detected for user {} — family {} revoked",
                    token.getUser().getId(), token.getFamilyId());
            throw new InvalidCredentialsException("Refresh token is invalid.");
        }

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidCredentialsException("Refresh token has expired.");
        }

        User user = token.getUser();
        if (user.getDeletedAt() != null
                || !UserSlugConstants.STATUS_ACTIVE.equals(user.getAccountStatus().getSlug())) {
            throw new InvalidCredentialsException("Refresh token is invalid.");
        }

        // Same invalidation rule the JWT filter applies to access tokens, so every path that
        // bumps credentials_updated_at (password change, reset, lockout, admin force-reauth,
        // disable, soft delete) kills refresh tokens too without having to opt in.
        if (token.getCreatedAt().isBefore(user.getCredentialsUpdatedAt())) {
            throw new InvalidCredentialsException("Refresh token is invalid.");
        }

        token.setRevokedAt(Instant.now());
        refreshTokenRepository.save(token);

        String successor = issueInFamily(user, token.getFamilyId(), ipAddress, userAgent);

        auditService.record(AuditEventSlug.TOKEN_REFRESHED, AuditOutcomeSlug.SUCCESS,
                user.getId(), user.getId(),
                Map.of("familyId", token.getFamilyId().toString()));

        return new RotationResult(user, successor);
    }

    @Override
    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });
    }

    @Override
    @Transactional
    public void revokeAllActiveForUser(UUID userId) {
        refreshTokenRepository.revokeAllActiveForUser(userId);
    }

    private String issueInFamily(User user, UUID familyId, String ipAddress, String userAgent) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = encoder.encodeToString(bytes);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(TokenHasher.sha256(rawToken));
        token.setFamilyId(familyId);
        token.setExpiresAt(Instant.now().plus(
                appProperties.getJwt().getRefreshExpirationDays(), ChronoUnit.DAYS));
        token.setIpAddressMasked(dataMasker.maskIp(ipAddress));
        token.setUserAgentTruncated(dataMasker.truncateUserAgent(userAgent, 512));
        refreshTokenRepository.save(token);

        return rawToken;
    }
}
