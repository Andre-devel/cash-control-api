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
class RbacSchemaMigrationTest {

    @Autowired
    private DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings = {"roles", "permissions", "role_permissions", "user_roles", "user_permissions"})
    void rbacTableExists(String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", tableName, new String[]{"TABLE"})) {
                assertThat(rs.next()).as("RBAC table '%s' should exist", tableName).isTrue();
            }
        }
    }

    @Test
    void rolesHasUniqueNameIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "roles", true, false)) {
                while (rs.next()) {
                    if ("uidx_roles_name".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("roles should have unique index uidx_roles_name").isTrue();
        }
    }

    @Test
    void permissionsHasUniqueNameIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "permissions", true, false)) {
                while (rs.next()) {
                    if ("uidx_permissions_name".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("permissions should have unique index uidx_permissions_name").isTrue();
        }
    }

    @Test
    void rolePermissionsHasCompositeUniqueIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "role_permissions", true, false)) {
                while (rs.next()) {
                    if ("uidx_role_permissions".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("role_permissions should have unique index uidx_role_permissions").isTrue();
        }
    }

    @Test
    void userRolesHasCompositeUniqueIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "user_roles", true, false)) {
                while (rs.next()) {
                    if ("uidx_user_roles".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("user_roles should have unique index uidx_user_roles").isTrue();
        }
    }

    @Test
    void userPermissionsHasCompositeUniqueIndex() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean found = false;
            try (ResultSet rs = meta.getIndexInfo(null, "public", "user_permissions", true, false)) {
                while (rs.next()) {
                    if ("uidx_user_permissions".equals(rs.getString("INDEX_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertThat(found).as("user_permissions should have unique index uidx_user_permissions").isTrue();
        }
    }
}