package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.RbacAssignmentService;
import com.cashcontrol.api.service.RoleService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RoleControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @MockitoBean private RoleService roleService;
    @MockitoBean private RbacAssignmentService rbacAssignmentService;

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
        adminUser = new AuthenticatedUser(admin, createAuthorityList("role:create", "role:update", "role:delete", "permission:grant", "permission:revoke"));

        User basic = new User();
        ReflectionTestUtils.setField(basic, "id", UUID.randomUUID());
        basic.setEmail("user@example.com");
        basic.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        basic.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        basic.setCredentialsUpdatedAt(Instant.now());
        basicUser = new AuthenticatedUser(basic, List.of());
    }

    @Test
    void createRole_withoutRoleCreateAuthority_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/roles")
                        .with(user(basicUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"NEW_ROLE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRole_withDuplicateName_returns409() throws Exception {
        when(roleService.createRole(any(), anyString(), any()))
                .thenThrow(new ConflictException("Role already exists."));

        mockMvc.perform(post("/api/v1/roles")
                        .with(user(adminUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"EXISTING_ROLE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void deleteRole_systemRoleDelete_returns409() throws Exception {
        doThrow(new ConflictException("System roles cannot be deleted."))
                .when(roleService).deleteRole(any(), any());

        mockMvc.perform(delete("/api/v1/roles/" + UUID.randomUUID())
                        .with(user(adminUser)))
                .andExpect(status().isConflict());
    }

    @Test
    void listRoles_withRoleCreateAuthority_returns200() throws Exception {
        when(roleService.listRoles(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/roles")
                        .with(user(adminUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listRoles_withoutAuthority_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/roles")
                        .with(user(basicUser)))
                .andExpect(status().isForbidden());
    }
}
