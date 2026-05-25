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
class LookupTablesMigrationTest {

    @Autowired
    private DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings = {
            "account_statuses",
            "auth_origins",
            "oauth_providers",
            "lockout_types",
            "permission_categories",
            "authentication_methods",
            "audit_event_types",
            "audit_outcomes"
    })
    void lookupTableExists(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", tableName, new String[]{"TABLE"})) {
                assertThat(rs.next())
                        .as("Lookup table '%s' should exist in the public schema", tableName)
                        .isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "account_statuses",
            "auth_origins",
            "oauth_providers",
            "lockout_types",
            "permission_categories",
            "authentication_methods",
            "audit_event_types",
            "audit_outcomes"
    })
    void lookupTableHasUniqueSlugIndex(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", tableName, true, false)) {
                while (rs.next()) {
                    if ("slug".equals(rs.getString("COLUMN_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found)
                    .as("Table '%s' should have a unique index on column 'slug'", tableName)
                    .isTrue();
        }
    }

    @Test
    void auditEventTypesHasCategoryColumns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean hasCategory = false;
            boolean hasSeverity = false;
            try (ResultSet rs = meta.getColumns(null, "public", "audit_event_types", null)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if ("category".equals(col)) hasCategory = true;
                    if ("severity".equals(col)) hasSeverity = true;
                }
            }
            assertThat(hasCategory).as("audit_event_types must have column 'category'").isTrue();
            assertThat(hasSeverity).as("audit_event_types must have column 'severity'").isTrue();
        }
    }

    @Test
    void auditEventTypesHasCompositeCategorySeverityIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "audit_event_types", false, false)) {
                while (rs.next()) {
                    if ("idx_audit_event_types_category_severity".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found)
                    .as("audit_event_types should have composite index idx_audit_event_types_category_severity")
                    .isTrue();
        }
    }
}