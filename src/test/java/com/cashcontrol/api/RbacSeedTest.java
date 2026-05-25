package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RbacSeedTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void adminRoleExistsAsSystemRole() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE name = 'ADMIN' AND is_system_role = TRUE",
                Integer.class);
        assertThat(count).as("ADMIN role should exist and be a system role").isEqualTo(1);
    }

    @Test
    void userRoleExistsAsSystemRole() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE name = 'USER' AND is_system_role = TRUE",
                Integer.class);
        assertThat(count).as("USER role should exist and be a system role").isEqualTo(1);
    }

    @Test
    void elevenSystemPermissionsExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permissions WHERE is_system_perm = TRUE",
                Integer.class);
        assertThat(count).as("There should be exactly 11 system permissions").isEqualTo(11);
    }

    @Test
    void adminRoleHasAllElevenSystemPermissions() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE r.name = 'ADMIN' AND p.is_system_perm = TRUE
                """,
                Integer.class);
        assertThat(count).as("ADMIN role should have exactly 11 system permissions").isEqualTo(11);
    }

    @Test
    void userRoleHasZeroPermissions() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                WHERE r.name = 'USER'
                """,
                Integer.class);
        assertThat(count).as("USER role should have zero permissions").isZero();
    }

    @Test
    void allSystemPermissionNamesArePresent() {
        for (String permName : new String[]{
                "user:create", "user:read", "user:update", "user:delete",
                "role:create", "role:update", "role:delete",
                "permission:grant", "permission:revoke",
                "audit:view", "auth:manage"
        }) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM permissions WHERE name = ?",
                    Integer.class, permName);
            assertThat(count).as("System permission '%s' should exist", permName).isEqualTo(1);
        }
    }
}