package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.response.UserAdminResponse;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class UserControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private UserService userService;

    private MockMvc mockMvc;
    private AuthenticatedUser authenticatedUser;
    private AuthenticatedUser adminUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("user@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        authenticatedUser = new AuthenticatedUser(user, List.of());

        User admin = new User();
        ReflectionTestUtils.setField(admin, "id", UUID.randomUUID());
        admin.setEmail("admin@example.com");
        admin.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        admin.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        admin.setCredentialsUpdatedAt(Instant.now());
        adminUser = new AuthenticatedUser(admin, createAuthorityList("user:read"));
    }

    @Test
    void getOwnProfile_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOwnProfile_withValidToken_returns200() throws Exception {
        UserProfileResponse profile = new UserProfileResponse(
                UUID.randomUUID(), "u***@example.com", null, "ACTIVE", "LOCAL",
                null, List.of(), List.of(), Instant.now());
        when(userService.getOwnProfile(any())).thenReturn(profile);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(user(authenticatedUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedEmail").exists());
    }

    @Test
    void getUserById_withoutUserReadAuthority_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID())
                        .with(user(authenticatedUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUserById_withUserReadAuthority_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAdminResponse resp = new UserAdminResponse(
                userId, "a***@example.com", null, "ACTIVE", "LOCAL",
                null, List.of(), List.of(), Instant.now(), 0, null, null, false);
        when(userService.getUserById(any())).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/" + userId)
                        .with(user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.maskedEmail").exists());
    }

    @Test
    void getUserById_responseNeverContainsPasswordHash() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAdminResponse resp = new UserAdminResponse(
                userId, "a***@example.com", null, "ACTIVE", "LOCAL",
                null, List.of(), List.of(), Instant.now(), 0, null, null, false);
        when(userService.getUserById(any())).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/" + userId)
                        .with(user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }
}
