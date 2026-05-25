package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
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
class LoginAttemptsSchemaTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void loginAttemptsTableExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", "login_attempts", new String[]{"TABLE"})) {
                assertThat(rs.next()).as("login_attempts table should exist").isTrue();
            }
        }
    }

    @Test
    void loginAttemptsUserIdIsNullable() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, "public", "login_attempts", "user_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("NULLABLE"))
                        .as("login_attempts.user_id must be nullable to support unknown-email attempts")
                        .isEqualTo(DatabaseMetaData.columnNullable);
            }
        }
    }

    @Test
    void loginAttemptsIpTimestampIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "login_attempts", false, false)) {
                while (rs.next()) {
                    if ("idx_login_attempts_ip_time".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found)
                    .as("login_attempts should have composite index idx_login_attempts_ip_time for rate limiting")
                    .isTrue();
        }
    }

    @Test
    void loginAttemptsHasFailureContextColumn() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getColumns(null, "public", "login_attempts", null)) {
                while (rs.next()) {
                    if ("failure_context".equals(rs.getString("COLUMN_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("login_attempts must have a failure_context column (internal only)").isTrue();
        }
    }

    @Test
    void loginAttemptsHasCorrelationIdColumn() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getColumns(null, "public", "login_attempts", null)) {
                while (rs.next()) {
                    if ("correlation_id".equals(rs.getString("COLUMN_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("login_attempts must have a correlation_id column").isTrue();
        }
    }
}