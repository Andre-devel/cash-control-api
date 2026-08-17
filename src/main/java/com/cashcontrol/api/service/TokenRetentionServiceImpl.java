package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.PasswordResetTokenRepository;
import com.cashcontrol.api.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRetentionServiceImpl implements TokenRetentionService {

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;
    private final AppProperties appProperties;

    @Override
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredPasswordResetTokens() {
        int retentionDays = appProperties.getRetention().getPasswordResetDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        int deleted = passwordResetTokenRepository.deleteConsumedBefore(cutoff);
        log.info("Token retention purge: deleted {} consumed password reset tokens older than {} days",
                deleted, retentionDays);

        auditService.record(AuditEventSlug.TOKEN_RETENTION_PURGE, AuditOutcomeSlug.SUCCESS, null, null,
                Map.of(
                        "type", "PASSWORD_RESET",
                        "deletedCount", deleted,
                        "retentionDays", retentionDays,
                        "cutoff", cutoff.toString()
                ));
    }

    @Override
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredVerificationTokens() {
        int retentionDays = appProperties.getRetention().getVerificationTokenDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        int deleted = emailVerificationTokenRepository.deleteConsumedOrInvalidatedBefore(cutoff);
        log.info("Token retention purge: deleted {} consumed/invalidated verification tokens older than {} days",
                deleted, retentionDays);

        auditService.record(AuditEventSlug.TOKEN_RETENTION_PURGE, AuditOutcomeSlug.SUCCESS, null, null,
                Map.of(
                        "type", "EMAIL_VERIFICATION",
                        "deletedCount", deleted,
                        "retentionDays", retentionDays,
                        "cutoff", cutoff.toString()
                ));
    }

    @Override
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeExpiredRefreshTokens() {
        int retentionDays = appProperties.getRetention().getRefreshTokenDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
        log.info("Token retention purge: deleted {} refresh tokens that expired more than {} days ago",
                deleted, retentionDays);

        auditService.record(AuditEventSlug.TOKEN_RETENTION_PURGE, AuditOutcomeSlug.SUCCESS, null, null,
                Map.of(
                        "type", "REFRESH_TOKEN",
                        "deletedCount", deleted,
                        "retentionDays", retentionDays,
                        "cutoff", cutoff.toString()
                ));
    }
}
