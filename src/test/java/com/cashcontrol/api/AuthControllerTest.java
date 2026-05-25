package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.response.AuthResponse;
import com.cashcontrol.api.dto.response.MessageResponse;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.EmailVerificationService;
import com.cashcontrol.api.service.PasswordResetService;
import com.cashcontrol.api.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AuthControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private AuthService authService;
    @MockitoBean private UserService userService;
    @MockitoBean private PasswordResetService passwordResetService;
    @MockitoBean private EmailVerificationService emailVerificationService;

    private MockMvc mockMvc;
    private AuthenticatedUser authenticatedUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("test@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        authenticatedUser = new AuthenticatedUser(user, List.of());
    }

    @Test
    void register_withValidRequest_returns201() throws Exception {
        when(authService.register(any())).thenReturn(new MessageResponse("Registration successful. Please verify your email."));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"P@ssword123!\",\"consentAccepted\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void register_withMissingBody_returns400WithFieldErrors() throws Exception {
        // Sending blank values triggers @NotBlank validation errors on email and password
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\",\"consentAccepted\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void login_withValidCredentials_returns200WithJwt() throws Exception {
        when(authService.login(any(), anyString(), any()))
                .thenReturn(AuthResponse.of("jwt-token-here", 900));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"P@ssword123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token-here"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void login_withMissingBody_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void logout_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_withValidToken_returns204() throws Exception {
        doNothing().when(authService).logout(any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(user(authenticatedUser)))
                .andExpect(status().isNoContent());
    }

    @Test
    void passwordResetRequest_withNonExistentEmail_returns200() throws Exception {
        doNothing().when(passwordResetService).initiateReset(anyString());

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nonexistent@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void getOwnProfile_withValidToken_returns200() throws Exception {
        UserProfileResponse profile = new UserProfileResponse(
                UUID.randomUUID(), "t***@example.com", null, "ACTIVE", "LOCAL",
                null, List.of(), List.of(), Instant.now());
        when(userService.getOwnProfile(any())).thenReturn(profile);

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user(authenticatedUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedEmail").value("t***@example.com"));
    }

    @Test
    void getOwnProfile_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
