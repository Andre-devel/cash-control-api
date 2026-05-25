package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AdminSecurityService;
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
import static org.mockito.Mockito.doNothing;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AdminSecurityControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private AdminSecurityService adminSecurityService;

    private MockMvc mockMvc;
    private AuthenticatedUser adminUser;
    private AuthenticatedUser basicUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        User admin = new User();
        ReflectionTestUtils.setField(admin, "id", UUID.randomUUID());
        admin.setEmail("admin@example.com");
        admin.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        admin.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        admin.setCredentialsUpdatedAt(Instant.now());
        adminUser = new AuthenticatedUser(admin, createAuthorityList("auth:manage"));

        User basic = new User();
        ReflectionTestUtils.setField(basic, "id", UUID.randomUUID());
        basic.setEmail("user@example.com");
        basic.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        basic.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        basic.setCredentialsUpdatedAt(Instant.now());
        basicUser = new AuthenticatedUser(basic, List.of());
    }

    @Test
    void forceReAuth_withoutAuthManageAuthority_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/force-reauth")
                        .with(user(basicUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void forceReAuth_withAuthManageAuthority_returns204() throws Exception {
        doNothing().when(adminSecurityService).forceReAuthentication(any(), any());

        mockMvc.perform(post("/api/v1/admin/security/force-reauth")
                        .with(user(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void manualLock_withoutAuthManageAuthority_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/lock")
                        .with(user(basicUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\",\"reason\":\"Suspicious activity\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualLock_withAuthManageAuthority_returns204() throws Exception {
        doNothing().when(adminSecurityService).manualLockAccount(any(), any(), any());

        mockMvc.perform(post("/api/v1/admin/security/lock")
                        .with(user(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\",\"reason\":\"Suspicious activity\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void unlock_withoutAuthManageAuthority_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/unlock")
                        .with(user(basicUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlock_withAuthManageAuthority_returns204() throws Exception {
        doNothing().when(adminSecurityService).unlockAccount(any(), any());

        mockMvc.perform(post("/api/v1/admin/security/unlock")
                        .with(user(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void forceReAuth_withMissingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/force-reauth")
                        .with(user(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anyRequest_withoutJwt_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/force-reauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
