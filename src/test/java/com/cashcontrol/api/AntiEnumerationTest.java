package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.NoOpEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AntiEnumerationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private LookupCache lookupCache;
    @Autowired private NoOpEmailService noOpEmailService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        noOpEmailService.clearSentEmails();
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void register_withExistingEmail_returnsSame201AsNewRegistration() throws Exception {
        String email = "anti-enum-reg-" + System.nanoTime() + "@example.com";

        // First registration
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Str0ng!Pass123\",\"consentAccepted\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").exists());

        noOpEmailService.clearSentEmails();

        // Second registration with same email — must return same 201
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Str0ng!Pass123\",\"consentAccepted\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void login_withNonExistentEmail_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + System.nanoTime() + "@example.com\","
                                + "\"password\":\"Str0ng!Pass123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        String email = "anti-enum-login-" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));
        activateUser(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"WRONG_PASSWORD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withLockedAccount_returns401_sameAsWrongPassword() throws Exception {
        String email = "anti-enum-locked-" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));
        activateUser(email);

        // Lock the account
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_LOCKED));
        userRepository.save(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Str0ng!Pass123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordResetRequest_withNonExistentEmail_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + System.nanoTime() + "@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void emailVerify_withInvalidToken_returnsGenericError() throws Exception {
        mockMvc.perform(get("/api/v1/auth/email/verify")
                        .param("token", "completely-invalid-token-that-does-not-exist"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").exists());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void activateUser(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
    }
}
