package com.cashcontrol.api;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.repository.AccountLockoutRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.NoOpEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountLockoutIntegrationTest extends BaseIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountLockoutRepository accountLockoutRepository;
    @Autowired private LookupCache lookupCache;
    @Autowired private NoOpEmailService noOpEmailService;

    @BeforeEach
    void clearEmails() {
        noOpEmailService.clearSentEmails();
    }

    @Test
    void fiveFailedLogins_locksAccount_sixthReturns401() {
        String email = "lockout-integ-" + System.nanoTime() + "@example.com";
        String password = "Str0ng!Pass123";
        authService.register(new RegisterRequest(email, password, true));
        activateUser(email);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "WRONG_PASSWORD"), null, null))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        User locked = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(locked.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_LOCKED);
        assertThat(locked.getLockoutExpiresAt()).isNotNull().isAfter(Instant.now());

        // 6th attempt — even with correct password — returns same generic 401
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, password), null, null))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void expiredAutoLockout_isAutoCleared_onNextLoginAttempt() {
        String email = "lockout-expiry-" + System.nanoTime() + "@example.com";
        String password = "Str0ng!Pass123";
        authService.register(new RegisterRequest(email, password, true));
        activateUser(email);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "WRONG"), null, null))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        // Manipulate DB to simulate lockout window having expired
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setLockoutExpiresAt(Instant.now().minusSeconds(1));
        userRepository.save(user);

        // Login should now succeed — expired auto-lockout is cleared automatically
        var response = authService.login(new LoginRequest(email, password), "127.0.0.1", "TestAgent");

        assertThat(response.accessToken()).isNotBlank();

        User cleared = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(cleared.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_ACTIVE);
        assertThat(cleared.getLockoutExpiresAt()).isNull();
    }

    @Test
    void manualLock_notClearedByExpiryManipulation_remainsLocked() {
        String email = "manual-lock-" + System.nanoTime() + "@example.com";
        String password = "Str0ng!Pass123";
        authService.register(new RegisterRequest(email, password, true));
        activateUser(email);

        // Manually set LOCKED status with MANUAL lockout type (no expiresAt)
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED));
        user.setLockoutType(lookupCache.requireLockoutType(UserSlugConstants.LOCKOUT_MANUAL));
        user.setLockoutExpiresAt(null); // no expiry for MANUAL lock
        userRepository.save(user);

        // Even with null expiry (simulating "expired"), MANUAL lock is NOT cleared
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, password), null, null))
                .isInstanceOf(InvalidCredentialsException.class);

        User stillLocked = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(stillLocked.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_LOCKED);
    }

    @Test
    void successfulLogin_resetsFailedAttemptCounter() {
        String email = "counter-reset-" + System.nanoTime() + "@example.com";
        String password = "Str0ng!Pass123";
        authService.register(new RegisterRequest(email, password, true));
        activateUser(email);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "WRONG"), null, null))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        User afterFailed = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(afterFailed.getFailedLoginAttempts()).isEqualTo(3);

        authService.login(new LoginRequest(email, password), null, null);

        User afterSuccess = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(afterSuccess.getFailedLoginAttempts()).isEqualTo(0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void activateUser(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
    }
}
