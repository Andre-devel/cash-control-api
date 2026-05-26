package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class TransactionStatusTransitionTest {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "tx-status-" + UUID.randomUUID() + "@example.com");

        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId);
        accountId = account.id();
    }

    // ── PENDING → PAID ────────────────────────────────────────────────────────

    @Test
    void pendingToPaid_succeeds() {
        TransactionDetailResponse tx = createPendingTransaction();

        TransactionDetailResponse paid = transactionService.markAsPaid(
                tx.id(), new MarkAsPaidRequest(null), userId);

        assertThat(paid.status()).isEqualTo(TransactionStatus.PAID);
        assertThat(paid.paymentDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void pendingToPaid_withCustomDate_succeeds() {
        TransactionDetailResponse tx = createPendingTransaction();
        LocalDate customDate = LocalDate.now().minusDays(3);

        TransactionDetailResponse paid = transactionService.markAsPaid(
                tx.id(), new MarkAsPaidRequest(customDate), userId);

        assertThat(paid.status()).isEqualTo(TransactionStatus.PAID);
        assertThat(paid.paymentDate()).isEqualTo(customDate);
    }

    // ── PENDING → CANCELLED ───────────────────────────────────────────────────

    @Test
    void pendingToCancelled_succeeds() {
        TransactionDetailResponse tx = createPendingTransaction();

        TransactionDetailResponse cancelled = transactionService.cancelTransaction(tx.id(), userId);

        assertThat(cancelled.status()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
    }

    // ── PAID → * (invalid) ────────────────────────────────────────────────────

    @Test
    void paidToMarkAsPaid_rejected() {
        TransactionDetailResponse tx = createPaidTransaction();

        assertThatThrownBy(() ->
                transactionService.markAsPaid(tx.id(), new MarkAsPaidRequest(null), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PAID");
    }

    // ── CANCELLED → * (invalid) ───────────────────────────────────────────────

    @Test
    void cancelledToMarkAsPaid_rejected() {
        TransactionDetailResponse tx = createPendingTransaction();
        transactionService.cancelTransaction(tx.id(), userId);

        assertThatThrownBy(() ->
                transactionService.markAsPaid(tx.id(), new MarkAsPaidRequest(null), userId))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void cancelledToCancelled_rejected() {
        TransactionDetailResponse tx = createPendingTransaction();
        transactionService.cancelTransaction(tx.id(), userId);

        assertThatThrownBy(() ->
                transactionService.cancelTransaction(tx.id(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already cancelled");
    }

    // ── delete protection ─────────────────────────────────────────────────────

    @Test
    void deleteTransaction_regularTransaction_succeeds() {
        TransactionDetailResponse tx = createPaidTransaction();

        transactionService.deleteTransaction(tx.id(), userId);

        assertThatThrownBy(() -> transactionService.getTransaction(tx.id(), userId))
                .isInstanceOf(com.cashcontrol.api.domain.exception.ResourceNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TransactionDetailResponse createPendingTransaction() {
        return transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE,
                        new BigDecimal("50.00"), "Pending Rent",
                        LocalDate.now(), null, null, null, null, null, null,
                        TransactionStatus.PENDING),
                userId);
    }

    private TransactionDetailResponse createPaidTransaction() {
        return transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.INCOME,
                        new BigDecimal("100.00"), "Salary",
                        LocalDate.now(), null, null, null, null, null, null,
                        null),
                userId);
    }
}
