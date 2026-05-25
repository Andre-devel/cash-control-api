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
class AuditSchemaTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void auditLogsTableExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", "audit_logs", new String[]{"TABLE"})) {
                assertThat(rs.next()).as("audit_logs table should exist").isTrue();
            }
        }
    }

    @Test
    void auditLogsHasNoUpdatedAtColumn() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean hasUpdatedAt = false;
            try (ResultSet rs = meta.getColumns(null, "public", "audit_logs", null)) {
                while (rs.next()) {
                    if ("updated_at".equals(rs.getString("COLUMN_NAME"))) {
                        hasUpdatedAt = true;
                        break;
                    }
                }
            }
            assertThat(hasUpdatedAt)
                    .as("audit_logs must not have an updated_at column — it is append-only")
                    .isFalse();
        }
    }

    @Test
    void auditLogsHasCorrelationIdColumn() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getColumns(null, "public", "audit_logs", null)) {
                while (rs.next()) {
                    if ("correlation_id".equals(rs.getString("COLUMN_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("audit_logs must have a correlation_id column").isTrue();
        }
    }

    @Test
    void auditLogsHasMetadataColumn() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getColumns(null, "public", "audit_logs", null)) {
                while (rs.next()) {
                    if ("metadata".equals(rs.getString("COLUMN_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("audit_logs must have a metadata column").isTrue();
        }
    }

    @Test
    void auditLogsTargetTimeIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "audit_logs", false, false)) {
                while (rs.next()) {
                    if ("idx_audit_logs_target_time".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("audit_logs should have composite index idx_audit_logs_target_time").isTrue();
        }
    }

    @Test
    void auditLogsTypeTimeIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "audit_logs", false, false)) {
                while (rs.next()) {
                    if ("idx_audit_logs_type_time".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("audit_logs should have composite index idx_audit_logs_type_time").isTrue();
        }
    }
}