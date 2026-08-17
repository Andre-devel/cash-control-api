package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.service.AuthTokens;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AdminSecurityService;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.NoOpEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class CredentialsInvalidationIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private LookupCache lookupCache;
    @Autowired private AdminSecurityService adminSecurityService;
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
    void oldJwt_afterPasswordChange_returns401() throws Exception {
        String email = "cred-inv-pwd-" + System.nanoTime() + "@example.com";
        String password = "Str0ng!Pass123";

        authService.register(new RegisterRequest(email, password, true));
        activateUser(email);

        AuthTokens loginResponse = authService.login(new LoginRequest(email, password), "127.0.0.1", "TestAgent");
        String oldToken = loginResponse.response().accessToken();

        // Advance credentialsUpdatedAt past the JWT's iat to guarantee rejection
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setCredentialsUpdatedAt(Instant.now().plusSeconds(2));
        userRepository.save(user);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oldJwt_afterAdminForceReAuth_returns401() throws Exception {
        String email = "cred-inv-reauth-" + System.nanoTime() + "@example.com";
        String password = "Str0ng!Pass123";

        authService.register(new RegisterRequest(email, password, true));
        activateUser(email);

        AuthTokens loginResponse = authService.login(new LoginRequest(email, password), "127.0.0.1", "TestAgent");
        String oldToken = loginResponse.response().accessToken();

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();

        // Force re-auth via admin action (updates credentialsUpdatedAt)
        adminSecurityService.forceReAuthentication(user.getId(), user.getId());

        // Ensure credentialsUpdatedAt is strictly after the JWT's iat
        User updated = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        updated.setCredentialsUpdatedAt(Instant.now().plusSeconds(2));
        userRepository.save(updated);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oldJwt_afterAdminDisableUser_returns401() throws Exception {
        String email = "cred-inv-disable-" + System.nanoTime() + "@example.com";
        String password = "Str0ng!Pass123";

        authService.register(new RegisterRequest(email, password, true));
        activateUser(email);

        AuthTokens loginResponse = authService.login(new LoginRequest(email, password), "127.0.0.1", "TestAgent");
        String oldToken = loginResponse.response().accessToken();

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();

        // Disable the user (sets INACTIVE status)
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_INACTIVE));
        user.setCredentialsUpdatedAt(Instant.now().plusSeconds(2));
        userRepository.save(user);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void activateUser(String email) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
    }
}
