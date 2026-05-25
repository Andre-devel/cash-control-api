package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.SecuritySummaryResponse;
import com.cashcontrol.api.repository.AccountLockoutRepository;
import com.cashcontrol.api.repository.AuditLogRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AdminSecurityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminSecurityServiceTest {

    @InjectMocks private AdminSecurityServiceImpl service;

    @Mock private UserRepository userRepository;
    @Mock private AccountLockoutRepository accountLockoutRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private LookupCache lookupCache;
    @Mock private AuditService auditService;

    @BeforeEach
    void setUp() {
        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_LOCKED));
        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        when(lookupCache.requireLockoutType(UserSlugConstants.LOCKOUT_MANUAL))
                .thenReturn(TestEntityFactory.lockoutType(UserSlugConstants.LOCKOUT_MANUAL));
    }

    @Test
    void forceReAuthentication_updatesCredentialsUpdatedAt() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = buildUser(targetId);
        Instant before = Instant.now();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));

        service.forceReAuthentication(actorId, targetId);

        assertThat(user.getCredentialsUpdatedAt()).isAfterOrEqualTo(before);
        verify(auditService).record(eq(AuditEventSlug.CREDENTIALS_INVALIDATED), eq(AuditOutcomeSlug.SUCCESS),
                eq(actorId), eq(targetId), any());
    }

    @Test
    void manualLockAccount_setsLockedStatusWithNoExpiry() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = buildUser(targetId);
        Instant before = Instant.now();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));

        service.manualLockAccount(actorId, targetId, "Suspicious activity");

        assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_LOCKED);
        assertThat(user.getLockoutExpiresAt()).isNull(); // permanent
        assertThat(user.getCredentialsUpdatedAt()).isAfterOrEqualTo(before);
        verify(accountLockoutRepository).save(any());
        verify(auditService).record(eq(AuditEventSlug.ACCOUNT_LOCKED), any(), any(), any(), any());
        verify(auditService).record(eq(AuditEventSlug.CREDENTIALS_INVALIDATED), any(), any(), any(), any());
    }

    @Test
    void unlockAccount_resetsToActiveAndClearsLockout() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = buildUser(targetId);
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_LOCKED));
        user.setFailedLoginAttempts(5);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user));
        when(accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(targetId)).thenReturn(Optional.empty());

        service.unlockAccount(actorId, targetId);

        assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_ACTIVE);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(user.getLockoutExpiresAt()).isNull();
        verify(auditService).record(eq(AuditEventSlug.ACCOUNT_UNLOCKED), eq(AuditOutcomeSlug.SUCCESS),
                eq(actorId), eq(targetId));
    }

    @Test
    void forceReAuthentication_userNotFound_throwsResourceNotFound() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forceReAuthentication(null, targetId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSecuritySummary_returnsAggregatedCounts() {
        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_LOCKED));
        when(userRepository.countByAccountStatusId(any())).thenReturn(3L);
        when(auditLogRepository.countByEventTypeSlugAndCreatedAtAfter(eq("AUTH_FAILURE"), any())).thenReturn(12L);
        when(auditLogRepository.countByEventTypeSlugAndCreatedAtAfter(eq("CREDENTIALS_INVALIDATED"), any())).thenReturn(2L);

        SecuritySummaryResponse summary = service.getSecuritySummary();

        assertThat(summary.lockedAccountsCount()).isEqualTo(3L);
        assertThat(summary.failedAttemptsLast24h()).isEqualTo(12L);
        assertThat(summary.forcedReAuthsLast24h()).isEqualTo(2L);
    }

    private User buildUser(UUID id) {
        User user = new User();
        user.setEmail("u@x.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        try {
            var f = user.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }
}