package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class IndexPresenceTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void usersEmail_indexExists() {
        assertIndexExists("users", "uidx_users_email");
    }

    @Test
    void usersLastLogin_indexExists() {
        assertIndexExists("users", "idx_users_last_login");
    }

    @Test
    void auditLogs_targetTimeIndex_exists() {
        assertIndexExists("audit_logs", "idx_audit_logs_target_time");
    }

    @Test
    void auditLogs_typeTimeIndex_exists() {
        assertIndexExists("audit_logs", "idx_audit_logs_type_time");
    }

    @Test
    void loginAttempts_ipTimeIndex_exists() {
        assertIndexExists("login_attempts", "idx_login_attempts_ip_time");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private void assertIndexExists(String tableName, String indexName) {
        List<String> found = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ? AND indexname = ?",
                String.class,
                tableName,
                indexName);

        assertThat(found)
                .as("Expected index '%s' on table '%s' to exist in pg_indexes", indexName, tableName)
                .hasSize(1);
    }
}
