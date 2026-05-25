package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.User;

import java.util.UUID;

public interface BruteForceProtectionService {

    void recordAttempt(UUID userId, String ipMasked, String userAgentTruncated,
                       String authMethodSlug, boolean success, String failureContext);

    /**
     * Returns true if the user's account is currently locked.
     * Automatically clears expired AUTOMATIC lockouts in-memory and updates the
     * AccountLockout audit record; does NOT persist the User — the caller is responsible
     * for saving after the login flow completes.
     */
    boolean isAccountLocked(User user);

    /**
     * Increments the failed-login counter. If the configured threshold is reached,
     * applies an AUTOMATIC lockout, persists the AccountLockout record, and records
     * the ACCOUNT_LOCKED audit event. Saves the User to the database.
     */
    void incrementFailedAttempts(User user);

    /**
     * Resets the failed-login counter to 0 and clears lockoutExpiresAt.
     * Does NOT save the User — the caller is responsible for persisting.
     */
    void resetFailedAttempts(User user);
}
