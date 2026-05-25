package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class UsersTableMigrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void usersTableExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", "users", new String[]{"TABLE"})) {
                assertThat(rs.next()).as("users table should exist").isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "id", "email", "password_hash", "display_name",
        "account_status_id", "auth_origin_id", "email_verified_at",
        "failed_login_attempts", "lockout_expires_at", "lockout_type_id",
        "lockout_reason", "last_login_at", "credentials_updated_at",
        "consent_accepted_at", "consent_version", "deleted_at",
        "anonymized_at", "created_at", "updated_at"
    })
    void usersTableHasRequiredColumn(String column) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getColumns(null, "public", "users", null)) {
                while (rs.next()) {
                    if (column.equals(rs.getString("COLUMN_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("users table should have column '%s'", column).isTrue();
        }
    }

    @Test
    void usersEmailHasUniqueIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "users", true, false)) {
                while (rs.next()) {
                    if ("uidx_users_email".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("users should have unique index uidx_users_email").isTrue();
        }
    }

    @Test
    void credentialsUpdatedAtIsNotNullable() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, "public", "users", "credentials_updated_at")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("NULLABLE"))
                        .as("credentials_updated_at must be NOT NULL")
                        .isEqualTo(DatabaseMetaData.columnNoNulls);
            }
        }
    }

    @Test
    void emailCompositeIndexWithDeletedAtExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "users", false, false)) {
                while (rs.next()) {
                    if ("idx_users_email_deleted".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("users should have composite index idx_users_email_deleted").isTrue();
        }
    }
}