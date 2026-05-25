package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.response.SecuritySummaryResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AdminSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AuditControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private AuditService auditService;
    @MockitoBean private AdminSecurityService adminSecurityService;

    private MockMvc mockMvc;
    private AuthenticatedUser auditAdmin;
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
        auditAdmin = new AuthenticatedUser(admin, createAuthorityList("audit:view"));

        User basic = new User();
        ReflectionTestUtils.setField(basic, "id", UUID.randomUUID());
        basic.setEmail("user@example.com");
        basic.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        basic.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        basic.setCredentialsUpdatedAt(Instant.now());
        basicUser = new AuthenticatedUser(basic, List.of());
    }

    @Test
    void queryAuditLogs_withoutAuditViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .with(user(basicUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    void queryAuditLogs_withAuditViewAuthority_returns200() throws Exception {
        when(auditService.queryAuditLogs(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/audit")
                        .with(user(auditAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void queryAuditLogs_honorsPaginationParameters() throws Exception {
        when(auditService.queryAuditLogs(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/audit?page=0&size=5")
                        .with(user(auditAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void getSecuritySummary_withAuditViewAuthority_returns200() throws Exception {
        when(adminSecurityService.getSecuritySummary())
                .thenReturn(new SecuritySummaryResponse(2L, 15L, 3L));

        mockMvc.perform(get("/api/v1/audit/summary")
                        .with(user(auditAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedAccountsCount").value(2))
                .andExpect(jsonPath("$.failedAttemptsLast24h").value(15));
    }

    @Test
    void getUserTimeline_withoutAuditViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/audit/users/" + UUID.randomUUID())
                        .with(user(basicUser)))
                .andExpect(status().isForbidden());
    }
}
