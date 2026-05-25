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
class TokenTablesMigrationTest {

    @Autowired
    private DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings = {"email_verification_tokens", "password_reset_tokens"})
    void tokenTableExists(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", tableName, new String[]{"TABLE"})) {
                assertThat(rs.next()).as("Token table '%s' should exist", tableName).isTrue();
            }
        }
    }

    @Test
    void emailVerificationTokensHasUniqueTokenHashIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "email_verification_tokens", true, false)) {
                while (rs.next()) {
                    if ("uidx_email_verification_tokens_hash".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found)
                    .as("email_verification_tokens should have unique index uidx_email_verification_tokens_hash")
                    .isTrue();
        }
    }

    @Test
    void passwordResetTokensHasUniqueTokenHashIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "password_reset_tokens", true, false)) {
                while (rs.next()) {
                    if ("uidx_password_reset_tokens_hash".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found)
                    .as("password_reset_tokens should have unique index uidx_password_reset_tokens_hash")
                    .isTrue();
        }
    }

    @Test
    void emailVerificationTokensHasActiveCompositeIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "email_verification_tokens", false, false)) {
                while (rs.next()) {
                    if ("idx_email_verification_active".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found)
                    .as("email_verification_tokens should have composite index idx_email_verification_active")
                    .isTrue();
        }
    }

    @Test
    void passwordResetTokensHasActiveCompositeIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "password_reset_tokens", false, false)) {
                while (rs.next()) {
                    if ("idx_password_reset_active".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found)
                    .as("password_reset_tokens should have composite index idx_password_reset_active")
                    .isTrue();
        }
    }

    @Test
    void emailVerificationTokensConsumedAtIsNullable() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, "public", "email_verification_tokens", "consumed_at")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("NULLABLE"))
                        .as("email_verification_tokens.consumed_at must be nullable")
                        .isEqualTo(DatabaseMetaData.columnNullable);
            }
        }
    }

    @Test
    void passwordResetTokensConsumedAtIsNullable() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, "public", "password_reset_tokens", "consumed_at")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("NULLABLE"))
                        .as("password_reset_tokens.consumed_at must be nullable")
                        .isEqualTo(DatabaseMetaData.columnNullable);
            }
        }
    }
}