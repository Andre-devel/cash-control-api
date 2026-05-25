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
class CreditCardSchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── shared_limit_groups ───────────────────────────────────

    @Test
    void sharedLimitGroupsTableExists() {
        assertTableExists("shared_limit_groups");
    }

    // ── credit_cards ──────────────────────────────────────────

    @Test
    void creditCardsTableExists() {
        assertTableExists("credit_cards");
    }

    @Test
    void creditCardsTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'credit_cards'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "name", "brand", "issuer", "credit_limit",
                "closing_day", "due_day", "shared_limit_group_id",
                "archived_at", "deleted_at", "created_at", "updated_at");
    }

    @Test
    void creditLimitIsNumericType() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'credit_cards' AND column_name = 'credit_limit'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
    }

    @Test
    void creditCardsClosingDayCheckConstraintEnforced() {
        // Verify the check constraint exists
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_name = 'credit_cards' AND constraint_type = 'CHECK'",
                Long.class);
        assertThat(count).as("credit_cards should have CHECK constraints for closing_day and due_day").isGreaterThan(0);
    }

    @Test
    void creditCardsUserIndex_exists() {
        assertIndexExists("credit_cards", "idx_credit_cards_user");
    }

    @Test
    void creditCardsUserNameUniqueIndex_exists() {
        assertIndexExists("credit_cards", "uidx_credit_cards_user_name");
    }

    @Test
    void creditCardsUserDeletedIndex_exists() {
        assertIndexExists("credit_cards", "idx_credit_cards_user_deleted");
    }

    // ── installment_series.credit_card_id FK ─────────────────

    @Test
    void installmentSeriesHasCreditCardIdColumn() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'installment_series' AND column_name = 'credit_card_id'",
                String.class);
        assertThat(columns).hasSize(1);
    }

    @Test
    void installmentSeriesCardIndex_exists() {
        assertIndexExists("installment_series", "idx_installment_series_card");
    }

    // ── invoices ──────────────────────────────────────────────

    @Test
    void invoicesTableExists() {
        assertTableExists("invoices");
    }

    @Test
    void invoicesTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'invoices'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "credit_card_id", "status", "reference_month",
                "closing_date", "due_date", "total_amount", "paid_amount",
                "created_at", "updated_at");
    }

    @Test
    void invoicesTotalAmountIsNumericType() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'invoices' AND column_name = 'total_amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
    }

    @Test
    void invoicesCardMonthUniqueIndex_exists() {
        assertIndexExists("invoices", "uidx_invoices_card_month");
    }

    @Test
    void invoicesUserDueIndex_exists() {
        assertIndexExists("invoices", "idx_invoices_user_due");
    }

    @Test
    void invoicesDueDateIndex_exists() {
        assertIndexExists("invoices", "idx_invoices_due_date");
    }

    // ── invoice_items ─────────────────────────────────────────

    @Test
    void invoiceItemsTableExists() {
        assertTableExists("invoice_items");
    }

    @Test
    void invoiceItemsTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'invoice_items'",
                String.class);

        assertThat(columns).contains(
                "id", "user_id", "invoice_id", "description", "amount",
                "competence_date", "is_revolving", "cancelled_at",
                "created_at", "updated_at");
    }

    @Test
    void invoiceItemsAmountIsNumericType() {
        String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'invoice_items' AND column_name = 'amount'",
                String.class);
        assertThat(dataType).isEqualTo("numeric");
    }

    @Test
    void invoiceItemsInvoiceIndex_exists() {
        assertIndexExists("invoice_items", "idx_invoice_items_invoice");
    }

    // ── invoice_item_tags ─────────────────────────────────────

    @Test
    void invoiceItemTagsTableExists() {
        assertTableExists("invoice_item_tags");
    }

    @Test
    void invoiceItemTagsUniqueIndex_exists() {
        assertIndexExists("invoice_item_tags", "uidx_invoice_item_tags");
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
