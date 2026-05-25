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
class TransactionSchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── installment_series ────────────────────────────────────

    @Test
    void installmentSeriesTableExists() {
        assertTableExists("installment_series");
    }

    @Test
    void installmentSeriesHasNumericAmountColumn() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'installment_series' AND column_name = 'total_amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
    }

    @Test
    void installmentSeriesUserIndex_exists() {
        assertIndexExists("installment_series", "idx_installment_series_user");
    }

    // ── recurrence_rules ──────────────────────────────────────

    @Test
    void recurrenceRulesTableExists() {
        assertTableExists("recurrence_rules");
    }

    @Test
    void recurrenceRulesSchedulerIndex_exists() {
        assertIndexExists("recurrence_rules", "idx_recurrence_rules_scheduler");
    }

    @Test
    void recurrenceRulesHasNumericAmountColumn() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'recurrence_rules' AND column_name = 'amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
    }

    // ── transactions ──────────────────────────────────────────

    @Test
    void transactionsTableExists() {
        assertTableExists("transactions");
    }

    @Test
    void transactionsTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'transactions'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "account_id", "type", "status", "amount",
                "description", "notes", "competence_date", "payment_date",
                "cancelled_at", "installment_series_id", "installment_number",
                "total_installments", "is_detached", "is_early_settlement",
                "recurrence_rule_id", "category_id", "subcategory_id",
                "transfer_group_id", "location", "created_at", "updated_at");
    }

    @Test
    void transactionsAmountIsNumericType() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'transactions' AND column_name = 'amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
    }

    @Test
    void transactionsUserIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_user");
    }

    @Test
    void transactionsAccountIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_account");
    }

    @Test
    void transactionsUserCompetenceIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_user_competence");
    }

    @Test
    void transactionsUserPaymentIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_user_payment");
    }

    @Test
    void transactionsUserStatusIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_user_status");
    }

    @Test
    void transactionsTransferGroupIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_transfer_group");
    }

    @Test
    void transactionsOverdueScanIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_overdue_scan");
    }

    @Test
    void transactionsInstallmentSeriesIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_installment_series");
    }

    @Test
    void transactionsRecurrenceIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_recurrence");
    }

    // ── transaction_tags ──────────────────────────────────────

    @Test
    void transactionTagsTableExists() {
        assertTableExists("transaction_tags");
    }

    @Test
    void transactionTagsUniqueIndex_exists() {
        assertIndexExists("transaction_tags", "uidx_transaction_tags");
    }

    // ── attachments ───────────────────────────────────────────

    @Test
    void attachmentsTableExists() {
        assertTableExists("attachments");
    }

    @Test
    void attachmentsTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'attachments'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "transaction_id", "original_filename",
                "mime_type", "file_size_bytes", "storage_key", "deleted_at",
                "uploaded_at", "created_at");
    }

    @Test
    void attachmentsStorageKeyUniqueIndex_exists() {
        assertIndexExists("attachments", "uidx_attachments_storage_key");
    }

    @Test
    void attachmentsTransactionActiveIndex_exists() {
        assertIndexExists("attachments", "idx_attachments_transaction_active");
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
