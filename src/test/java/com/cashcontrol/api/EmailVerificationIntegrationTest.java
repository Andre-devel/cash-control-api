package com.cashcontrol.api;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.EmailVerificationService;
import com.cashcontrol.api.service.NoOpEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationIntegrationTest extends BaseIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private EmailVerificationService emailVerificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private EmailVerificationTokenRepository tokenRepository;
    @Autowired private NoOpEmailService noOpEmailService;

    @BeforeEach
    void clear() {
        noOpEmailService.clearSentEmails();
    }

    @Test
    void register_createsToken_inDatabase() {
        String email = "evit_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        var tokens = tokenRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId());
        assertThat(tokens).hasSize(1);
        assertThat(tokens.getFirst().getConsumedAt()).isNull();
    }

    @Test
    void verifyEmail_withInvalidToken_throws() {
        assertThatThrownBy(() -> emailVerificationService.verifyEmail("completely-invalid-token"))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void resendVerification_invalidatesOldToken_andCreatesNew() {
        String email = "resend_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        var firstTokens = tokenRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId());
        assertThat(firstTokens).hasSize(1);

        emailVerificationService.resendVerification(email);

        var afterResend = tokenRepository.findByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNull(user.getId());
        assertThat(afterResend).hasSize(1);
        assertThat(afterResend.getFirst().getId()).isNotEqualTo(firstTokens.get(0).getId());
    }

    @Test
    void resendVerification_forNonExistentEmail_silentlySucceeds() {
        emailVerificationService.resendVerification("nobody_" + System.nanoTime() + "@x.com");
    }
}