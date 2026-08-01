package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AccountStatusChecker {

    /**
     * Validates that a user is eligible to authenticate.
     * All failure paths throw {@link InvalidCredentialsException} — generic message, anti-enumeration.
     * An expired automatic lockout is treated as cleared and does NOT throw; the caller is responsible
     * for resetting the lockout state after a successful password verification.
     */
    public void checkAuthenticationEligibility(User user) {
        if (user.getDeletedAt() != null) {
            throw new InvalidCredentialsException("Falha na autenticação.");
        }

        String statusSlug = user.getAccountStatus().getSlug();

        if (UserSlugConstants.STATUS_LOCKED.equals(statusSlug)) {
            Instant expiresAt = user.getLockoutExpiresAt();
            // MANUAL lockout (expiresAt == null) or unexpired automatic lockout → still locked
            if (expiresAt == null || expiresAt.isAfter(Instant.now())) {
                throw new InvalidCredentialsException("Falha na autenticação.");
            }
            // Auto-lockout window has passed; proceed and let AuthService clear it on success
            return;
        }

        if (!UserSlugConstants.STATUS_ACTIVE.equals(statusSlug)) {
            throw new InvalidCredentialsException("Falha na autenticação.");
        }
    }

    /** Returns true if the user is in an expired automatic lockout that should be cleared. */
    public boolean hasExpiredAutoLockout(User user) {
        String statusSlug = user.getAccountStatus().getSlug();
        if (!UserSlugConstants.STATUS_LOCKED.equals(statusSlug)) {
            return false;
        }
        Instant expiresAt = user.getLockoutExpiresAt();
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }
}