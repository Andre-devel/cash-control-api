package com.cashcontrol.api;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.dto.response.MessageResponse;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
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

class AuthIntegrationTest extends BaseIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailVerificationTokenRepository tokenRepository;
    @Autowired private NoOpEmailService noOpEmailService;
    @Autowired private LookupCache lookupCache;

    @BeforeEach
    void clearEmails() {
        noOpEmailService.clearSentEmails();
    }

    @Test
    void register_createsUserInPendingVerification_andSendsEmail() {
        String email = "integ_" + System.nanoTime() + "@example.com";

        MessageResponse resp = authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        assertThat(resp.message()).contains("verification");
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_PENDING_VERIFICATION);
        assertThat(noOpEmailService.wasEmailSentTo(email, NoOpEmailService.EmailType.VERIFICATION)).isTrue();
    }

    @Test
    void login_beforeEmailVerification_throwsInvalidCredentials() {
        String email = "unverified_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "Str0ng!Pass123"), null, null))
                .isInstanceOf(com.cashcontrol.api.domain.exception.InvalidCredentialsException.class);
    }

    @Test
    void register_duplicateEmail_returnsSameMessageAndSendsAlreadyExistsEmail() {
        String email = "dup_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));
        noOpEmailService.clearSentEmails();

        MessageResponse secondResp = authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        assertThat(secondResp.message()).contains("verification");
        assertThat(noOpEmailService.wasEmailSentTo(email, NoOpEmailService.EmailType.ACCOUNT_ALREADY_EXISTS)).isTrue();
    }

    @Test
    void login_onActivatedUser_successfullyReturnsToken() {
        String email = "active_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        // Manually activate the user for this test (simulates email verification)
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        var response = authService.login(new LoginRequest(email, "Str0ng!Pass123"), "127.0.0.1", "TestAgent");

        assertThat(response.response().accessToken()).isNotBlank();
        assertThat(response.response().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void login_wrongPassword_fiveTimesCausesLockout() {
        String email = "lockout_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "WRONG"), null, null))
                    .isInstanceOf(com.cashcontrol.api.domain.exception.InvalidCredentialsException.class);
        }

        // After lockout, correct password still returns same generic exception
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "Str0ng!Pass123"), null, null))
                .isInstanceOf(com.cashcontrol.api.domain.exception.InvalidCredentialsException.class);

        User locked = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(locked.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_LOCKED);
    }
}