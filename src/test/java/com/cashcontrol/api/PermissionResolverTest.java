package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.Permission;
import com.cashcontrol.api.domain.entity.Role;
import com.cashcontrol.api.domain.entity.RolePermission;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.entity.UserPermission;
import com.cashcontrol.api.domain.entity.UserRole;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.PermissionRepository;
import com.cashcontrol.api.repository.RolePermissionRepository;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserPermissionRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import com.cashcontrol.api.security.PermissionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class PermissionResolverTest {

    @Autowired private PermissionResolver resolver;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private UserPermissionRepository userPermissionRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;

    private User user;

    @BeforeEach
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug(UserSlugConstants.STATUS_ACTIVE).orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug(UserSlugConstants.ORIGIN_LOCAL).orElseThrow();

        user = new User();
        user.setEmail("resolver-test-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(active);
        user.setAuthOrigin(local);
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    @Test
    void userWithNoRolesOrDirectPermissionsHasEmpty() {
        List<String> perms = resolver.resolveEffectivePermissions(user.getId());
        assertThat(perms).isEmpty();
    }

    @Test
    void rolePermissionsAreIncluded() {
        Permission perm = permissionRepository.findByName("user:read").orElseThrow();
        Role role = roleRepository.findByNameIgnoreCase("USER").orElseThrow();

        RolePermission rp = new RolePermission();
        rp.setRole(role);
        rp.setPermission(perm);
        rolePermissionRepository.save(rp);

        UserRole ur = new UserRole();
        ur.setUser(user);
        ur.setRole(role);
        userRoleRepository.save(ur);

        List<String> perms = resolver.resolveEffectivePermissions(user.getId());
        assertThat(perms).contains("user:read");
    }

    @Test
    void directPermissionsAreIncluded() {
        Permission perm = permissionRepository.findByName("audit:view").orElseThrow();

        UserPermission up = new UserPermission();
        up.setUser(user);
        up.setPermission(perm);
        userPermissionRepository.save(up);

        List<String> perms = resolver.resolveEffectivePermissions(user.getId());
        assertThat(perms).contains("audit:view");
    }

    @Test
    void duplicatePermissionsFromRoleAndDirectAreDeduped() {
        Permission perm = permissionRepository.findByName("user:read").orElseThrow();
        Role role = roleRepository.findByNameIgnoreCase("USER").orElseThrow();

        RolePermission rp = new RolePermission();
        rp.setRole(role);
        rp.setPermission(perm);
        rolePermissionRepository.save(rp);

        UserRole ur = new UserRole();
        ur.setUser(user);
        ur.setRole(role);
        userRoleRepository.save(ur);

        UserPermission up = new UserPermission();
        up.setUser(user);
        up.setPermission(perm);
        userPermissionRepository.save(up);

        List<String> perms = resolver.resolveEffectivePermissions(user.getId());
        assertThat(perms.stream().filter("user:read"::equals).count()).isEqualTo(1);
    }

    @Test
    void expiredDirectPermissionsAreExcluded() {
        Permission perm = permissionRepository.findByName("audit:view").orElseThrow();

        UserPermission up = new UserPermission();
        up.setUser(user);
        up.setPermission(perm);
        up.setExpiresAt(Instant.now().minusSeconds(60));
        userPermissionRepository.save(up);

        List<String> perms = resolver.resolveEffectivePermissions(user.getId());
        assertThat(perms).doesNotContain("audit:view");
    }
}