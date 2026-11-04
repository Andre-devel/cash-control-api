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
    void twelveSystemPermissionsExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permissions WHERE is_system_perm = TRUE",
                Integer.class);
        assertThat(count).as("There should be exactly 12 system permissions (11 from V7, bank-sync:view from V44)").isEqualTo(12);
    }

    @Test
    void adminRoleHasAllTwelveSystemPermissions() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE r.name = 'ADMIN' AND p.is_system_perm = TRUE
                """,
                Integer.class);
        assertThat(count).as("ADMIN role should have exactly 12 system permissions (11 from V7, bank-sync:view from V44)").isEqualTo(12);
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
    void systemRoleDescriptionsAreInPortuguese() {
        assertThat(descriptionOf("roles", "ADMIN"))
                .isEqualTo("Administrador do sistema, com todas as permissões nativas.");
        assertThat(descriptionOf("roles", "USER"))
                .isEqualTo("Usuário autenticado padrão, sem permissões nativas.");
    }

    @Test
    void systemPermissionDescriptionsAreInPortuguese() {
        assertThat(descriptionOf("permissions", "user:create")).isEqualTo("Criar contas de usuário.");
        assertThat(descriptionOf("permissions", "role:update"))
                .isEqualTo("Alterar descrição e permissões dos papéis.");
        assertThat(descriptionOf("permissions", "bank-sync:view"))
                .isEqualTo("Ver o histórico de sincronização bancária das próprias conexões.");
    }

    @Test
    void noSystemRoleOrPermissionKeepsEnglishSeedDescription() {
        Integer english = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM (
                    SELECT description FROM roles WHERE is_system_role = TRUE
                    UNION ALL
                    SELECT description FROM permissions WHERE is_system_perm = TRUE
                ) d
                WHERE d.description IS NULL
                   OR d.description ~ '^(Create|Read|Update|Soft-delete|Delete|Assign|Revoke|Access|Manage|View|System|Standard) '
                """,
                Integer.class);
        assertThat(english).as("System roles and permissions should have pt-BR descriptions").isZero();
    }

    private String descriptionOf(String table, String name) {
        return jdbcTemplate.queryForObject(
                "SELECT description FROM " + table + " WHERE name = ?", String.class, name);
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