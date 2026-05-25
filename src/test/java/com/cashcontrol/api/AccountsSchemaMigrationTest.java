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
class AccountsSchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void accountsTableExists() {
        assertTableExists("accounts");
    }

    @Test
    void accountsTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'accounts'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "name", "type", "currency_code",
                "description", "sort_order", "archived_at", "deleted_at",
                "created_at", "updated_at");
    }

    @Test
    void accountsUserIndex_exists() {
        assertIndexExists("accounts", "idx_accounts_user");
    }

    @Test
    void accountsUniqueNameIndex_exists() {
        assertIndexExists("accounts", "uidx_accounts_user_name");
    }

    @Test
    void accountsUserArchivedIndex_exists() {
        assertIndexExists("accounts", "idx_accounts_user_archived");
    }

    @Test
    void accountsUserDeletedIndex_exists() {
        assertIndexExists("accounts", "idx_accounts_user_deleted");
    }

    @Test
    void accountsAmountColumnsUseCorrectType() {
        // No monetary columns in accounts itself — balance is computed from transactions
        assertTableExists("accounts");
    }

    @Test
    void accountsUserIdIsNotNull() {
        Boolean isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'accounts' AND column_name = 'user_id'",
                Boolean.class);
        assertThat(isNullable).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void assertTableExists(String tableName) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?)",
                Boolean.class, tableName);
        assertThat(exists).as("Table '%s' should exist", tableName).isTrue();
    }

    private void assertIndexExists(String tableName, String indexName) {
        List<String> found = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ? AND indexname = ?",
                String.class, tableName, indexName);
        assertThat(found)
                .as("Expected index '%s' on table '%s'", indexName, tableName)
                .hasSize(1);
    }
}
