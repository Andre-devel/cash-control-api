package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.UserConsent;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.dto.response.UserConsentResponse;
import com.cashcontrol.api.repository.UserConsentRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.NoOpEmailService;
import com.cashcontrol.api.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConsentCaptureTest extends BaseIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserConsentRepository userConsentRepository;
    @Autowired private UserService userService;
    @Autowired private NoOpEmailService noOpEmailService;

    @BeforeEach
    void clearEmails() {
        noOpEmailService.clearSentEmails();
    }

    @Test
    void register_withConsentTrue_persistsUserConsentRecord() {
        String email = "consent_" + System.nanoTime() + "@example.com";

        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        var user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        List<UserConsent> consents = userConsentRepository.findByUserIdOrderByAcceptedAtDesc(user.getId());

        assertThat(consents).hasSize(1);
        assertThat(consents.get(0).getConsentVersion()).isNotBlank();
        assertThat(consents.get(0).getAcceptedAt()).isNotNull();
        assertThat(consents.get(0).getRevokedAt()).isNull();
    }

    @Test
    void register_withConsentTrue_updatesUserDenormalizedConsentFields() {
        String email = "consent_denorm_" + System.nanoTime() + "@example.com";

        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));

        var user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(user.getConsentAcceptedAt()).isNotNull();
        assertThat(user.getConsentVersion()).isNotBlank();
    }

    @Test
    void register_withoutConsent_rejectsRequest() throws Exception {
        String body = """
                {"email":"noConsent@example.com","password":"Str0ng!Pass123","consentAccepted":false}
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getConsentHistory_returnsOwnConsents() {
        String email = "hist_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));
        var user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();

        List<UserConsentResponse> history = userService.getConsentHistory(user.getId());

        assertThat(history).hasSize(1);
        assertThat(history.get(0).consentVersion()).isNotBlank();
        assertThat(history.get(0).acceptedAt()).isNotNull();
        assertThat(history.get(0).active()).isTrue();
        assertThat(history.get(0).revokedAt()).isNull();
    }

    @Test
    void getConsentHistory_emptyForUnknownUser_throws() {
        UUID nonExistent = UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertThrows(
                com.cashcontrol.api.domain.exception.ResourceNotFoundException.class,
                () -> userService.getConsentHistory(nonExistent));
    }
}
