package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class JwtAuthenticationFilterTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;

    private MockMvc mockMvc;
    private User activeUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        AccountStatus active = accountStatusRepository.findBySlug(UserSlugConstants.STATUS_ACTIVE).orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug(UserSlugConstants.ORIGIN_LOCAL).orElseThrow();

        activeUser = new User();
        activeUser.setEmail("jwt-filter-test-" + System.nanoTime() + "@example.com");
        activeUser.setAccountStatus(active);
        activeUser.setAuthOrigin(local);
        activeUser.setCredentialsUpdatedAt(Instant.now().minusSeconds(10));
        userRepository.save(activeUser);
    }

    @AfterEach
    void cleanUp() {
        userRepository.deleteById(activeUser.getId());
    }

    @Test
    void missingAuthorizationHeaderReturns401OnProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenAllowsPassThroughToPublicEndpoint() throws Exception {
        String token = jwtService.generateToken(
                activeUser.getId(), List.of(), activeUser.getCredentialsUpdatedAt());
        mockMvc.perform(get("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithInvalidSignatureReturns401() throws Exception {
        String token = jwtService.generateToken(
                activeUser.getId(), List.of(), activeUser.getCredentialsUpdatedAt());
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleTokenIssuedBeforeCredentialsUpdatedAtReturns401() throws Exception {
        // Token iat = approximately now
        String token = jwtService.generateToken(
                activeUser.getId(), List.of(), activeUser.getCredentialsUpdatedAt());

        // Simulate password change: credentialsUpdatedAt set to future → makes token stale
        // Filter checks: if (iat < credentialsUpdatedAt) → reject
        activeUser.setCredentialsUpdatedAt(Instant.now().plusSeconds(60));
        userRepository.save(activeUser);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForNonExistentUserReturns401() throws Exception {
        String token = jwtService.generateToken(UUID.randomUUID(), List.of(), Instant.now());
        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForInactiveUserReturns401() throws Exception {
        AccountStatus inactive = accountStatusRepository
                .findBySlug(UserSlugConstants.STATUS_INACTIVE).orElseThrow();
        activeUser.setAccountStatus(inactive);
        userRepository.save(activeUser);

        String token = jwtService.generateToken(
                activeUser.getId(), List.of(), activeUser.getCredentialsUpdatedAt());
        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}