package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class SchemaConstraintTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void insertDuplicateEmail_throwsDataIntegrityViolationException() {
        String sharedEmail = "constraint-test-" + System.nanoTime() + "@example.com";
        UUID activeStatusId = jdbcTemplate.queryForObject(
                "SELECT id FROM account_statuses WHERE slug = 'ACTIVE'", UUID.class);
        UUID localOriginId = jdbcTemplate.queryForObject(
                "SELECT id FROM auth_origins WHERE slug = 'LOCAL'", UUID.class);

        // First insert
        jdbcTemplate.update(
                "INSERT INTO users (id, email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), sharedEmail, activeStatusId, localOriginId, Timestamp.from(Instant.now()));

        // Second insert with same email — must throw
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO users (id, email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), sharedEmail, activeStatusId, localOriginId, Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cleanup
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", sharedEmail);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void insertDuplicateRoleName_throwsDataIntegrityViolationException() {
        String roleName = "TEST_ROLE_" + System.nanoTime();

        // First insert
        jdbcTemplate.update(
                "INSERT INTO roles (id, name, is_system_role, is_active) VALUES (?, ?, false, true)",
                UUID.randomUUID(), roleName);

        // Duplicate name — must throw
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO roles (id, name, is_system_role, is_active) VALUES (?, ?, false, true)",
                UUID.randomUUID(), roleName))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cleanup
        jdbcTemplate.update("DELETE FROM roles WHERE name = ?", roleName);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void insertDuplicatePermissionName_throwsDataIntegrityViolationException() {
        String permName = "test:perm_" + System.nanoTime();

        // First insert
        jdbcTemplate.update(
                "INSERT INTO permissions (id, name, is_system_perm, is_active) VALUES (?, ?, false, true)",
                UUID.randomUUID(), permName);

        // Duplicate name — must throw
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO permissions (id, name, is_system_perm, is_active) VALUES (?, ?, false, true)",
                UUID.randomUUID(), permName))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cleanup
        jdbcTemplate.update("DELETE FROM permissions WHERE name = ?", permName);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void insertDuplicateUserRole_throwsDataIntegrityViolationException() {
        UUID activeStatusId = jdbcTemplate.queryForObject(
                "SELECT id FROM account_statuses WHERE slug = 'ACTIVE'", UUID.class);
        UUID localOriginId = jdbcTemplate.queryForObject(
                "SELECT id FROM auth_origins WHERE slug = 'LOCAL'", UUID.class);

        UUID userId = UUID.randomUUID();
        String testEmail = "constraint-user-role-" + System.nanoTime() + "@example.com";

        jdbcTemplate.update(
                "INSERT INTO users (id, email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, testEmail, activeStatusId, localOriginId, Timestamp.from(Instant.now()));

        UUID roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM roles WHERE name = 'USER'", UUID.class);

        // First user_roles insert
        jdbcTemplate.update(
                "INSERT INTO user_roles (id, user_id, role_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), userId, roleId);

        // Duplicate (userId, roleId) pair — must throw
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO user_roles (id, user_id, role_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), userId, roleId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cleanup
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }
}
