package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.PasswordResetTokenRepository;
import com.cashcontrol.api.service.TokenRetentionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenRetentionServiceTest {

    @InjectMocks
    private TokenRetentionServiceImpl tokenRetentionService;

    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private AuditService auditService;
    @Mock private AppProperties appProperties;

    private AppProperties.Retention retentionProps;

    @BeforeEach
    void setUp() {
        retentionProps = new AppProperties.Retention();
        when(appProperties.getRetention()).thenReturn(retentionProps);
    }

    @Test
    void purgeExpiredPasswordResetTokens_deletesConsumedTokensOlderThanRetention() {
        retentionProps.setPasswordResetDays(30);
        when(passwordResetTokenRepository.deleteConsumedBefore(any(Instant.class))).thenReturn(5);

        tokenRetentionService.purgeExpiredPasswordResetTokens();

        verify(passwordResetTokenRepository).deleteConsumedBefore(any(Instant.class));
        verify(auditService).record(
                eq(AuditEventSlug.TOKEN_RETENTION_PURGE),
                eq(AuditOutcomeSlug.SUCCESS),
                isNull(),
                isNull(),
                anyMap());
    }

    @Test
    void purgeExpiredVerificationTokens_deletesConsumedAndInvalidatedTokens() {
        retentionProps.setVerificationTokenDays(7);
        when(emailVerificationTokenRepository.deleteConsumedOrInvalidatedBefore(any(Instant.class))).thenReturn(3);

        tokenRetentionService.purgeExpiredVerificationTokens();

        verify(emailVerificationTokenRepository).deleteConsumedOrInvalidatedBefore(any(Instant.class));
        verify(auditService).record(
                eq(AuditEventSlug.TOKEN_RETENTION_PURGE),
                eq(AuditOutcomeSlug.SUCCESS),
                isNull(),
                isNull(),
                anyMap());
    }

    @Test
    void purgeExpiredPasswordResetTokens_zeroDeleted_stillRecordsAuditEvent() {
        retentionProps.setPasswordResetDays(30);
        when(passwordResetTokenRepository.deleteConsumedBefore(any(Instant.class))).thenReturn(0);

        tokenRetentionService.purgeExpiredPasswordResetTokens();

        verify(auditService).record(
                eq(AuditEventSlug.TOKEN_RETENTION_PURGE),
                eq(AuditOutcomeSlug.SUCCESS),
                isNull(),
                isNull(),
                anyMap());
    }
}
