package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountLockout;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.SecuritySummaryResponse;
import com.cashcontrol.api.repository.AccountLockoutRepository;
import com.cashcontrol.api.repository.AuditLogRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminSecurityServiceImpl implements AdminSecurityService {

    private final UserRepository userRepository;
    private final AccountLockoutRepository accountLockoutRepository;
    private final AuditLogRepository auditLogRepository;
    private final LookupCache lookupCache;
    private final AuditService auditService;

    @Override
    @Transactional
    public void forceReAuthentication(UUID actorId, UUID targetUserId) {
        User user = requireUser(targetUserId);
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);

        auditService.record(AuditEventSlug.CREDENTIALS_INVALIDATED, AuditOutcomeSlug.SUCCESS,
                actorId, targetUserId,
                Map.of("reason", "ADMIN_FORCED_REAUTH"));
    }

    @Override
    @Transactional
    public void manualLockAccount(UUID actorId, UUID targetUserId, String reason) {
        User user = requireUser(targetUserId);
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED));
        user.setLockoutType(lookupCache.requireLockoutType(UserSlugConstants.LOCKOUT_MANUAL));
        user.setLockoutExpiresAt(null); // permanent
        user.setLockoutReason(reason);
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);

        AccountLockout lockout = new AccountLockout();
        lockout.setUser(user);
        lockout.setLockoutType(lookupCache.requireLockoutType(UserSlugConstants.LOCKOUT_MANUAL));
        lockout.setReason(reason);
        if (actorId != null) {
            lockout.setLockedBy(userRepository.getReferenceById(actorId));
        }
        accountLockoutRepository.save(lockout);

        auditService.record(AuditEventSlug.ACCOUNT_LOCKED, AuditOutcomeSlug.SUCCESS,
                actorId, targetUserId,
                Map.of("reason", reason, "lockType", "MANUAL"));
        auditService.record(AuditEventSlug.CREDENTIALS_INVALIDATED, AuditOutcomeSlug.SUCCESS,
                actorId, targetUserId,
                Map.of("reason", "ACCOUNT_MANUALLY_LOCKED"));
    }

    @Override
    @Transactional
    public void unlockAccount(UUID actorId, UUID targetUserId) {
        User user = requireUser(targetUserId);
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setLockoutType(null);
        user.setLockoutExpiresAt(null);
        user.setLockoutReason(null);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);

        accountLockoutRepository.findByUserIdAndUnlockedAtIsNull(targetUserId)
                .ifPresent(lockout -> {
                    lockout.setUnlockedAt(Instant.now());
                    if (actorId != null) {
                        lockout.setUnlockedBy(userRepository.getReferenceById(actorId));
                    }
                    accountLockoutRepository.save(lockout);
                });

        auditService.record(AuditEventSlug.ACCOUNT_UNLOCKED, AuditOutcomeSlug.SUCCESS,
                actorId, targetUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public SecuritySummaryResponse getSecuritySummary() {
        var lockedStatus = lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED);
        long lockedCount = userRepository.countByAccountStatusId(lockedStatus.getId());

        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        long failedAttempts = auditLogRepository.countByEventTypeSlugAndCreatedAtAfter(
                AuditEventSlug.AUTH_FAILURE.name(), since);
        long forcedReAuths = auditLogRepository.countByEventTypeSlugAndCreatedAtAfter(
                AuditEventSlug.CREDENTIALS_INVALIDATED.name(), since);

        return new SecuritySummaryResponse(lockedCount, failedAttempts, forcedReAuths);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }
}