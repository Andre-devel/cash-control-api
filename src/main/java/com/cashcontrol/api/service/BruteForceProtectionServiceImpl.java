package com.cashcontrol.api.service;

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
import com.cashcontrol.api.security.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BruteForceProtectionServiceImpl implements BruteForceProtectionService {

    private final UserRepository userRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final AccountLockoutRepository accountLockoutRepository;
    private final LookupCache lookupCache;
    private final AuditService auditService;
    private final AppProperties appProperties;

    @Override
    @Transactional
    public void recordAttempt(UUID userId, String ipMasked, String userAgentTruncated,
                               String authMethodSlug, boolean success, String failureContext) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setUserId(userId);
        attempt.setAuthMethod(lookupCache.requireAuthenticationMethod(authMethodSlug));
        attempt.setIpAddressMasked(ipMasked != null ? ipMasked : "");
        attempt.setUserAgentTruncated(userAgentTruncated);
        attempt.setWasSuccessful(success);
        attempt.setFailureContext(failureContext);
        attempt.setCorrelationId(CorrelationIdHolder.get());
        loginAttemptRepository.save(attempt);
    }

    @Override
    @Transactional
    public boolean isAccountLocked(User user) {
        String statusSlug = user.getAccountStatus().getSlug();
        if (!UserSlugConstants.STATUS_LOCKED.equals(statusSlug)) {
            return false;
        }

        Instant expiresAt = user.getLockoutExpiresAt();

        if (expiresAt == null) {
            // MANUAL lockout — never auto-clears
            return true;
        }

        if (expiresAt.isAfter(Instant.now())) {
            // AUTOMATIC lockout still active
            return true;
        }

        // AUTOMATIC lockout has expired — clear it
        clearExpiredAutoLockout(user);
        return false;
    }

    @Override
    @Transactional
    public void incrementFailedAttempts(User user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= appProperties.getSecurity().getMaxFailedAttempts()) {
            applyAutoLockout(user, attempts);
        } else {
            userRepository.save(user);
        }
    }

    @Override
    public void resetFailedAttempts(User user) {
        user.setFailedLoginAttempts(0);
        user.setLockoutExpiresAt(null);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void applyAutoLockout(User user, int attempts) {
        long durationMinutes = appProperties.getSecurity().getLockoutDurationMinutes();
        Instant expiresAt = Instant.now().plus(durationMinutes, ChronoUnit.MINUTES);

        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED));
        user.setLockoutType(lookupCache.requireLockoutType(UserSlugConstants.LOCKOUT_AUTOMATIC));
        user.setLockoutExpiresAt(expiresAt);
        user.setLockoutReason("Automatic lockout after " +
                appProperties.getSecurity().getMaxFailedAttempts() + " failed attempts.");
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);

        AccountLockout lockout = new AccountLockout();
        lockout.setUser(user);
        lockout.setLockoutType(lookupCache.requireLockoutType(UserSlugConstants.LOCKOUT_AUTOMATIC));
        lockout.setExpiresAt(expiresAt);
        lockout.setReason(user.getLockoutReason());
        accountLockoutRepository.save(lockout);

        auditService.record(AuditEventSlug.ACCOUNT_LOCKED, AuditOutcomeSlug.SUCCESS, null, user.getId(),
                Map.of("attempts", attempts));
    }

    private void clearExpiredAutoLockout(User user) {
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setLockoutType(null);
        user.setLockoutExpiresAt(null);
        user.setLockoutReason(null);

        accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(user.getId())
                .ifPresent(lockout -> {
                    lockout.setUnlockedAt(Instant.now());
                    accountLockoutRepository.save(lockout);
                });
    }
}
