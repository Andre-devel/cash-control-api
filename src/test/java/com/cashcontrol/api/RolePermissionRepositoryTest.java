package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Permission;
import com.cashcontrol.api.domain.entity.Role;
import com.cashcontrol.api.domain.entity.RolePermission;
import com.cashcontrol.api.repository.PermissionRepository;
import com.cashcontrol.api.repository.RolePermissionRepository;
import com.cashcontrol.api.repository.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RolePermissionRepositoryTest {

    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;

    private Role userRole;
    private Permission userReadPermission;

    @BeforeEach
    void setUp() {
        // USER role has no permissions seeded — safe to assign/remove in tests
        userRole = roleRepository.findByNameIgnoreCase("USER").orElseThrow();
        // user:read is a seeded system permission not assigned to USER role
        userReadPermission = permissionRepository.findByName("user:read").orElseThrow();
    }

    @Test
    @Transactional
    void findByRoleIdReturnsAssignedPermissions() {
        RolePermission rp = new RolePermission();
        rp.setRole(userRole);
        rp.setPermission(userReadPermission);
        rp.setGrantedAt(Instant.now());
        rolePermissionRepository.save(rp);

        List<RolePermission> result = rolePermissionRepository.findByRoleId(userRole.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPermission().getName()).isEqualTo("user:read");
    }

    @Test
    @Transactional
    void existsByRoleIdAndPermissionIdReturnsTrueWhenAssigned() {
        RolePermission rp = new RolePermission();
        rp.setRole(userRole);
        rp.setPermission(userReadPermission);
        rp.setGrantedAt(Instant.now());
        rolePermissionRepository.save(rp);

        assertThat(rolePermissionRepository.existsByRoleIdAndPermissionId(
                userRole.getId(), userReadPermission.getId())).isTrue();
    }

    @Test
    @Transactional
    void existsByRoleIdAndPermissionIdReturnsFalseWhenNotAssigned() {
        assertThat(rolePermissionRepository.existsByRoleIdAndPermissionId(
                userRole.getId(), userReadPermission.getId())).isFalse();
    }

    @Test
    @Transactional
    void deleteByRoleIdAndPermissionIdRemovesAssociation() {
        RolePermission rp = new RolePermission();
        rp.setRole(userRole);
        rp.setPermission(userReadPermission);
        rp.setGrantedAt(Instant.now());
        rolePermissionRepository.saveAndFlush(rp);

        rolePermissionRepository.deleteByRoleIdAndPermissionId(userRole.getId(), userReadPermission.getId());

        assertThat(rolePermissionRepository.existsByRoleIdAndPermissionId(
                userRole.getId(), userReadPermission.getId())).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateRolePermissionThrowsDataIntegrityViolationException() {
        RolePermission first = new RolePermission();
        first.setRole(userRole);
        first.setPermission(userReadPermission);
        first.setGrantedAt(Instant.now());
        rolePermissionRepository.saveAndFlush(first);

        RolePermission duplicate = new RolePermission();
        duplicate.setRole(userRole);
        duplicate.setPermission(userReadPermission);
        duplicate.setGrantedAt(Instant.now());

        assertThatThrownBy(() -> rolePermissionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        // cleanup
        rolePermissionRepository.deleteByRoleIdAndPermissionId(userRole.getId(), userReadPermission.getId());
    }

    @Test
    @Transactional
    void adminRoleHasAllSystemPermissionsAssigned() {
        Role adminRole = roleRepository.findByNameIgnoreCase("ADMIN").orElseThrow();
        List<RolePermission> adminPerms = rolePermissionRepository.findByRoleId(adminRole.getId());
        assertThat(adminPerms).hasSizeGreaterThanOrEqualTo(11);
    }
}