package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountLockout;
import com.cashcontrol.api.domain.entity.LoginAttempt;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountLockoutRepository;
import com.cashcontrol.api.repository.LoginAttemptRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.BruteForceProtectionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BruteForceProtectionServiceTest {

    @InjectMocks private BruteForceProtectionServiceImpl bruteForceService;

    @Mock private UserRepository userRepository;
    @Mock private LoginAttemptRepository loginAttemptRepository;
    @Mock private AccountLockoutRepository accountLockoutRepository;
    @Mock private LookupCache lookupCache;
    @Mock private AuditService auditService;
    @Mock private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        AppProperties.Security security = new AppProperties.Security();
        when(appProperties.getSecurity()).thenReturn(security);

        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_LOCKED));
        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        when(lookupCache.requireLockoutType(UserSlugConstants.LOCKOUT_AUTOMATIC))
                .thenReturn(TestEntityFactory.lockoutType(UserSlugConstants.LOCKOUT_AUTOMATIC));
        when(lookupCache.requireAuthenticationMethod("PASSWORD"))
                .thenReturn(TestEntityFactory.authMethod("PASSWORD"));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── incrementFailedAttempts ────────────────────────────────────────────────

    @Test
    void incrementFailedAttempts_belowThreshold_savesUserWithIncrementedCount() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setFailedLoginAttempts(2);

        bruteForceService.incrementFailedAttempts(user);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(3);
        verify(userRepository).save(user);
        verify(accountLockoutRepository, never()).save(any());
    }

    @Test
    void incrementFailedAttempts_atThreshold_locksAccountAndPersistsLockout() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setFailedLoginAttempts(4); // 5th attempt triggers lockout (maxFailedAttempts=5)

        bruteForceService.incrementFailedAttempts(user);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_LOCKED);
        assertThat(user.getLockoutExpiresAt()).isNotNull().isAfter(Instant.now());
        assertThat(user.getLockoutReason()).isNotBlank();
        assertThat(user.getCredentialsUpdatedAt()).isNotNull();
        verify(accountLockoutRepository).save(any(AccountLockout.class));
        verify(userRepository).save(user);
    }

    @Test
    void incrementFailedAttempts_atThreshold_recordsAccountLockedAuditEvent() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setFailedLoginAttempts(4);

        bruteForceService.incrementFailedAttempts(user);

        verify(auditService).record(eq(AuditEventSlug.ACCOUNT_LOCKED), eq(AuditOutcomeSlug.SUCCESS),
                isNull(), eq(user.getId()), any());
    }

    @Test
    void fiveConsecutiveIncrements_locksAccountOnFifth() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setFailedLoginAttempts(0);

        for (int i = 0; i < 4; i++) {
            bruteForceService.incrementFailedAttempts(user);
            assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_ACTIVE);
        }

        bruteForceService.incrementFailedAttempts(user);

        assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_LOCKED);
    }

    // ── isAccountLocked ───────────────────────────────────────────────────────

    @Test
    void isAccountLocked_activeUser_returnsFalse() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);

        assertThat(bruteForceService.isAccountLocked(user)).isFalse();
    }

    @Test
    void isAccountLocked_lockedWithActiveWindow_returnsTrue() {
        User user = buildUser(UserSlugConstants.STATUS_LOCKED);
        user.setLockoutExpiresAt(Instant.now().plusSeconds(900));

        assertThat(bruteForceService.isAccountLocked(user)).isTrue();
    }

    @Test
    void isAccountLocked_manualLockWithNullExpiry_returnsTrue() {
        User user = buildUser(UserSlugConstants.STATUS_LOCKED);
        user.setLockoutExpiresAt(null); // MANUAL lock — no expiry

        assertThat(bruteForceService.isAccountLocked(user)).isTrue();
        verify(accountLockoutRepository, never()).save(any()); // never cleared
    }

    @Test
    void isAccountLocked_expiredAutoLockout_clearLockoutAndReturnsFalse() {
        User user = buildUser(UserSlugConstants.STATUS_LOCKED);
        user.setLockoutExpiresAt(Instant.now().minusSeconds(60)); // expired
        when(accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(any()))
                .thenReturn(Optional.empty());

        boolean result = bruteForceService.isAccountLocked(user);

        assertThat(result).isFalse();
        assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_ACTIVE);
        assertThat(user.getLockoutExpiresAt()).isNull();
        assertThat(user.getLockoutType()).isNull();
    }

    @Test
    void isAccountLocked_expiredAutoLockout_updatesAccountLockoutRecord() {
        User user = buildUser(UserSlugConstants.STATUS_LOCKED);
        user.setLockoutExpiresAt(Instant.now().minusSeconds(60));

        AccountLockout lockoutRecord = new AccountLockout();
        when(accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(any()))
                .thenReturn(Optional.of(lockoutRecord));

        bruteForceService.isAccountLocked(user);

        assertThat(lockoutRecord.getUnlockedAt()).isNotNull();
        verify(accountLockoutRepository).save(lockoutRecord);
    }

    // ── resetFailedAttempts ───────────────────────────────────────────────────

    @Test
    void resetFailedAttempts_clearsCounterAndLockoutExpiry() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setFailedLoginAttempts(3);
        user.setLockoutExpiresAt(Instant.now().plusSeconds(100));

        bruteForceService.resetFailedAttempts(user);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(user.getLockoutExpiresAt()).isNull();
    }

    @Test
    void resetFailedAttempts_doesNotSaveUser() {
        User user = buildUser(UserSlugConstants.STATUS_ACTIVE);
        user.setFailedLoginAttempts(2);

        bruteForceService.resetFailedAttempts(user);

        verify(userRepository, never()).save(any());
    }

    // ── recordAttempt ─────────────────────────────────────────────────────────

    @Test
    void recordAttempt_savesLoginAttemptWithAllFields() {
        UUID userId = UUID.randomUUID();

        bruteForceService.recordAttempt(userId, "192.168.1.0", "Mozilla/5.0", "PASSWORD", false, "INVALID_CREDENTIALS");

        ArgumentCaptor<LoginAttempt> captor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(loginAttemptRepository).save(captor.capture());
        LoginAttempt attempt = captor.getValue();
        assertThat(attempt.getUserId()).isEqualTo(userId);
        assertThat(attempt.getIpAddressMasked()).isEqualTo("192.168.1.0");
        assertThat(attempt.isWasSuccessful()).isFalse();
        assertThat(attempt.getFailureContext()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void recordAttempt_withNullUserId_savesAttemptWithNullUserId() {
        bruteForceService.recordAttempt(null, "10.0.0.0", "Agent", "PASSWORD", false, "INVALID_CREDENTIALS");

        ArgumentCaptor<LoginAttempt> captor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(loginAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    void recordAttempt_successfulAttempt_savedWithNoFailureContext() {
        UUID userId = UUID.randomUUID();

        bruteForceService.recordAttempt(userId, "1.2.3.0", "UA", "PASSWORD", true, null);

        ArgumentCaptor<LoginAttempt> captor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(loginAttemptRepository).save(captor.capture());
        LoginAttempt attempt = captor.getValue();
        assertThat(attempt.isWasSuccessful()).isTrue();
        assertThat(attempt.getFailureContext()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User buildUser(String statusSlug) {
        User user = new User();
        user.setEmail("u@x.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(statusSlug));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return user;
    }
}
