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
class PaymentMethodSchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── payment_methods table (V15) ───────────────────────────────────────────

    @Test
    void paymentMethodsTableExists() {
        assertTableExists("payment_methods");
    }

    @Test
    void paymentMethodsTableHasRequiredColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'payment_methods'",
                String.class);

        assertThat(columns).contains("id", "name", "slug", "description", "is_active", "created_at", "updated_at");
    }

    @Test
    void paymentMethodsSlugIsUnique() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE tablename = 'payment_methods' AND indexdef LIKE '%UNIQUE%' AND indexname LIKE '%slug%'",
                Long.class);
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void paymentMethodsSeededWithSevenRows() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_methods", Long.class);
        assertThat(count).isEqualTo(7L);
    }

    @Test
    void paymentMethodsSeedContainsExpectedSlugs() {
        List<String> slugs = jdbcTemplate.queryForList(
                "SELECT slug FROM payment_methods ORDER BY slug", String.class);
        assertThat(slugs).containsExactlyInAnyOrder(
                "BANK_TRANSFER", "BOLETO", "CASH", "CREDIT_CARD", "DEBIT_CARD", "OTHER", "PIX");
    }

    @Test
    void paymentMethodsAllActiveByDefault() {
        Long inactiveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_methods WHERE is_active = false", Long.class);
        assertThat(inactiveCount).isEqualTo(0L);
    }

    // ── transactions.payment_method_id (V16) ─────────────────────────────────

    @Test
    void transactionsHasPaymentMethodIdColumn() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'transactions' AND column_name = 'payment_method_id'",
                String.class);
        assertThat(columns).hasSize(1);
    }

    @Test
    void transactionsHasCreditCardIdColumn() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'transactions' AND column_name = 'credit_card_id'",
                String.class);
        assertThat(columns).hasSize(1);
    }

    @Test
    void transactionsPaymentMethodIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_payment_method");
    }

    @Test
    void transactionsCreditCardIndex_exists() {
        assertIndexExists("transactions", "idx_transactions_credit_card");
    }

    @Test
    void transactionsPaymentMethodIdIsNotNullable() {
        String isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'transactions' AND column_name = 'payment_method_id'",
                String.class);
        assertThat(isNullable).isEqualTo("NO");
    }

    // ── installment_series.payment_method_id (V17) ───────────────────────────

    @Test
    void installmentSeriesHasPaymentMethodIdColumn() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'installment_series' AND column_name = 'payment_method_id'",
                String.class);
        assertThat(columns).hasSize(1);
    }

    @Test
    void installmentSeriesPaymentMethodIndex_exists() {
        assertIndexExists("installment_series", "idx_installment_series_payment_method");
    }

    @Test
    void installmentSeriesPaymentMethodIdIsNotNullable() {
        String isNullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'installment_series' AND column_name = 'payment_method_id'",
                String.class);
        assertThat(isNullable).isEqualTo("NO");
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
