package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.response.AuthResponse;
import com.cashcontrol.api.service.AuthTokens;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AuthService;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class SensitiveFieldLeakTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private AuthService authService;
    @MockitoBean private UserService userService;

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
    void loginResponse_doesNotContainPasswordHash() throws Exception {
        when(authService.login(any(), anyString(), any()))
                .thenReturn(new AuthTokens(AuthResponse.of("jwt-token", 900), "refresh-token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Str0ng!Pass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.hash").doesNotExist())
                .andExpect(jsonPath("$.secret").doesNotExist());
    }

    @Test
    void loginResponse_doesNotExposeUserEmail() throws Exception {
        when(authService.login(any(), anyString(), any()))
                .thenReturn(new AuthTokens(AuthResponse.of("jwt-token", 900), "refresh-token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"Str0ng!Pass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void ownProfileResponse_doesNotContainPasswordRelatedFields() throws Exception {
        UUID userId = UUID.randomUUID();
        UserProfileResponse profile = new UserProfileResponse(
                userId, "t***@example.com", null, "ACTIVE", "LOCAL",
                null, List.of(), List.of(), Instant.now());
        when(userService.getOwnProfile(any())).thenReturn(profile);

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user(authenticatedUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.hash").doesNotExist());
    }

    @Test
    void ownProfileResponse_containsMaskedEmail_notRawEmail() throws Exception {
        UUID userId = UUID.randomUUID();
        UserProfileResponse profile = new UserProfileResponse(
                userId, "t***@example.com", null, "ACTIVE", "LOCAL",
                null, List.of(), List.of(), Instant.now());
        when(userService.getOwnProfile(any())).thenReturn(profile);

        mockMvc.perform(get("/api/v1/auth/me")
                        .with(user(authenticatedUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedEmail").value("t***@example.com"));
    }

    @Test
    void unauthorizedResponse_doesNotLeakInternalDetails() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").exists())
                .andExpect(jsonPath("$.message").exists())
                // Must not contain stack trace class names
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void forbiddenResponse_doesNotLeakInternalDetails() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .with(user(authenticatedUser)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }
}
