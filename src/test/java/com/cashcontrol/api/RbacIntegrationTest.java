package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.security.AuthenticatedUser;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RbacIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private UserService userService;
    @MockitoBean private RoleService roleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    // ── user:read permission ──────────────────────────────────────────────────

    @Test
    void userWithoutUserRead_gets403OnListUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .with(user(buildUser(List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithUserRead_gets200OnListUsers() throws Exception {
        when(userService.listUsers(any(), any())).thenReturn(empty());

        mockMvc.perform(get("/api/v1/users")
                        .with(user(buildUser(List.of("user:read")))))
                .andExpect(status().isOk());
    }

    // ── user:create permission ────────────────────────────────────────────────

    @Test
    void userWithoutUserCreate_gets403OnAdminCreateUser() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .with(user(buildUser(List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"roleIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    // ── role:create permission ────────────────────────────────────────────────

    @Test
    void userWithoutRoleCreate_gets403OnListRoles() throws Exception {
        mockMvc.perform(get("/api/v1/roles")
                        .with(user(buildUser(List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithRoleCreate_gets200OnListRoles() throws Exception {
        when(roleService.listRoles(any())).thenReturn(empty());

        mockMvc.perform(get("/api/v1/roles")
                        .with(user(buildUser(List.of("role:create")))))
                .andExpect(status().isOk());
    }

    @Test
    void userWithRoleUpdate_gets200OnListRoles() throws Exception {
        when(roleService.listRoles(any())).thenReturn(empty());

        mockMvc.perform(get("/api/v1/roles")
                        .with(user(buildUser(List.of("role:update")))))
                .andExpect(status().isOk());
    }

    // ── auth:manage permission ────────────────────────────────────────────────

    @Test
    void userWithoutAuthManage_gets403OnForceReAuth() throws Exception {
        mockMvc.perform(post("/api/v1/admin/security/force-reauth")
                        .with(user(buildUser(List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ── unauthenticated ───────────────────────────────────────────────────────

    @Test
    void unauthenticatedRequest_gets401OnProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildUser(List<String> authorities) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("rbac-test-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        return new AuthenticatedUser(user,
                authorities.isEmpty() ? List.of() : createAuthorityList(authorities.toArray(new String[0])));
    }
}
