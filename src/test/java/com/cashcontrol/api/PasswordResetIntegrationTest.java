package com.cashcontrol.api;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.PasswordResetTokenRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.NoOpEmailService;
import com.cashcontrol.api.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetIntegrationTest extends BaseIntegrationTest {

    @Autowired private PasswordResetService passwordResetService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordResetTokenRepository resetTokenRepository;
    @Autowired private LookupCache lookupCache;
    @Autowired private NoOpEmailService noOpEmailService;

    @BeforeEach
    void clear() {
        noOpEmailService.clearSentEmails();
    }

    @Test
    void initiateReset_alwaysReturnsVoid_regardlessOfEmailExistence() {
        // Should not throw even for non-existing email
        passwordResetService.initiateReset("nobody@example.com");
        passwordResetService.initiateReset("also_nobody@example.com");
    }

    @Test
    void initiateReset_existingActiveUser_createsToken() {
        String email = "reset_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        passwordResetService.initiateReset(email);

        var tokens = resetTokenRepository.findAll().stream()
                .filter(t -> t.getUser().getId().equals(user.getId()))
                .toList();
        assertThat(tokens).isNotEmpty();
        assertThat(noOpEmailService.wasEmailSentTo(email, NoOpEmailService.EmailType.PASSWORD_RESET)).isTrue();
    }

    @Test
    void completeReset_invalidToken_throwsTokenExpiredException() {
        assertThatThrownBy(() -> passwordResetService.completeReset("invalid-token", "New!Pass123"))
                .isInstanceOf(TokenExpiredException.class);
    }
}