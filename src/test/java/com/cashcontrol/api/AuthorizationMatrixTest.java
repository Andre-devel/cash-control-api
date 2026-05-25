package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.service.AdminSecurityService;
import com.cashcontrol.api.service.RoleService;
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
import static org.mockito.Mockito.when;
import static org.springframework.data.domain.Page.empty;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AuthorizationMatrixTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private UserService userService;
    @MockitoBean private RoleService roleService;
    @MockitoBean private AdminSecurityService adminSecurityService;
    @MockitoBean private AuditService auditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Default stub responses to avoid NPEs when permission check passes
        when(userService.listUsers(any(), any())).thenReturn(empty());
        when(roleService.listRoles(any())).thenReturn(empty());
        when(auditService.queryAuditLogs(any(), any())).thenReturn(empty());
    }

    // ── GET /api/v1/users — requires user:read ────────────────────────────────

    @Test
    void listUsers_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listUsers_withoutPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .with(user(buildUser(List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_withUserRead_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .with(user(buildUser(List.of("user:read")))))
                .andExpect(status().isOk());
    }

    // ── POST /api/v1/users — requires user:create ─────────────────────────────

    @Test
    void createUser_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"roleIds\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createUser_withoutPermission_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .with(user(buildUser(List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"roleIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    // ── PUT /api/v1/users/{id}/disable — requires user:update ─────────────────

    @Test
    void disableUser_withoutAuth_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/users/" + UUID.randomUUID() + "/disable"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableUser_withoutPermission_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/users/" + UUID.randomUUID() + "/disable")
                        .with(user(buildUser(List.of()))))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /api/v1/users/{id} — requires user:delete ─────────────────────

    @Test
    void deleteUser_withoutAuth_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteUser_withoutPermission_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/users/" + UUID.randomUUID())
                        .with(user(buildUser(List.of()))))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/v1/roles — requires role:create or role:update ──────────────

    @Test
    void listRoles_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/roles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listRoles_withoutPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/roles")
                        .with(user(buildUser(List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listRoles_withRoleCreate_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/roles")
                        .with(user(buildUser(List.of("role:create")))))
                .andExpect(status().isOk());
    }

    // ── POST /api/v1/roles — requires role:create ─────────────────────────────

    @Test
    void createRole_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NEW_ROLE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRole_withoutPermission_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/roles")
                        .with(user(buildUser(List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NEW_ROLE\"}"))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/v1/audit — requires audit:view ───────────────────────────────

    @Test
    void queryAuditLogs_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queryAuditLogs_withoutPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .with(user(buildUser(List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void queryAuditLogs_withAuditView_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .with(user(buildUser(List.of("audit:view")))))
                .andExpect(status().isOk());
    }

    // ── POST /api/v1/admin/security/force-reauth — requires auth:manage ───────

    @Test
    void forceReAuth_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/force-reauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forceReAuth_withoutPermission_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/force-reauth")
                        .with(user(buildUser(List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/v1/admin/security/lock — requires auth:manage ──────────────

    @Test
    void manualLock_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\",\"reason\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void manualLock_withoutPermission_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/lock")
                        .with(user(buildUser(List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\",\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/v1/admin/security/unlock — requires auth:manage ────────────

    @Test
    void unlock_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unlock_withoutPermission_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/unlock")
                        .with(user(buildUser(List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildUser(List<String> authorities) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("matrix-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user,
                authorities.isEmpty() ? List.of() : createAuthorityList(authorities.toArray(new String[0])));
    }
}
