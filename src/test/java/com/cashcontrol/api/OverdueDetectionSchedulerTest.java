package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.repository.TransactionRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class OverdueDetectionSchedulerTest {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionRepository transactionRepository;
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
                "overdue-scheduler-" + UUID.randomUUID() + "@example.com");

        AccountResponse account = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId);
        accountId = account.id();
    }

    @Test
    void detectOverdueAll_transitionsPastDuePendingToOverdue() {
        TransactionDetailResponse created = pendingWithPaymentDate(LocalDate.now().minusDays(1));

        int count = transactionService.detectOverdueAll();
        transactionRepository.flush();

        assertThat(count).isGreaterThanOrEqualTo(1);
        Transaction updated = transactionRepository.findByIdAndUserId(created.id(), userId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TransactionStatus.OVERDUE);
    }

    @Test
    void detectOverdueAll_doesNotTransitionFuturePendingTransactions() {
        TransactionDetailResponse created = pendingWithPaymentDate(LocalDate.now().plusDays(5));

        transactionService.detectOverdueAll();
        transactionRepository.flush();

        Transaction checked = transactionRepository.findByIdAndUserId(created.id(), userId).orElseThrow();
        assertThat(checked.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void detectOverdueAll_doesNotTransitionPaidTransactions() {
        TransactionDetailResponse created = paidTransaction(LocalDate.now().minusDays(3));

        transactionService.detectOverdueAll();
        transactionRepository.flush();

        Transaction checked = transactionRepository.findByIdAndUserId(created.id(), userId).orElseThrow();
        assertThat(checked.getStatus()).isEqualTo(TransactionStatus.PAID);
    }

    @Test
    void detectOverdueAll_secondRunDoesNotAffectAlreadyOverdue() {
        TransactionDetailResponse created = pendingWithPaymentDate(LocalDate.now().minusDays(2));

        transactionService.detectOverdueAll();
        transactionRepository.flush();

        Transaction afterFirst = transactionRepository.findByIdAndUserId(created.id(), userId).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(TransactionStatus.OVERDUE);

        // Second run: already-OVERDUE transactions should not be re-counted
        int secondCount = transactionService.detectOverdueAll();
        Transaction afterSecond = transactionRepository.findByIdAndUserId(created.id(), userId).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(TransactionStatus.OVERDUE);
        assertThat(secondCount).isEqualTo(0);
    }

    @Test
    void detectOverdueAll_pendingWithoutPaymentDate_isNotTransitioned() {
        TransactionDetailResponse created = pendingWithNoPaymentDate();

        transactionService.detectOverdueAll();
        transactionRepository.flush();

        Transaction checked = transactionRepository.findByIdAndUserId(created.id(), userId).orElseThrow();
        assertThat(checked.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void detectOverdueAll_pendingDueToday_isNotTransitioned() {
        // paymentDate = today means NOT overdue (condition is paymentDate < today)
        TransactionDetailResponse created = pendingWithPaymentDate(LocalDate.now());

        transactionService.detectOverdueAll();
        transactionRepository.flush();

        Transaction checked = transactionRepository.findByIdAndUserId(created.id(), userId).orElseThrow();
        assertThat(checked.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void detectOverdueAll_returnsCorrectCountOfTransitions() {
        pendingWithPaymentDate(LocalDate.now().minusDays(1));
        pendingWithPaymentDate(LocalDate.now().minusDays(2));
        pendingWithPaymentDate(LocalDate.now().minusDays(3));
        pendingWithPaymentDate(LocalDate.now().plusDays(1)); // future — should NOT be counted

        int count = transactionService.detectOverdueAll();

        assertThat(count).isGreaterThanOrEqualTo(3);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private TransactionDetailResponse pendingWithPaymentDate(LocalDate paymentDate) {
        return transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE, new BigDecimal("50.00"),
                        "Test expense", LocalDate.now(), paymentDate,
                        null, null, null, null, null, TransactionStatus.PENDING, null, null),
                userId);
    }

    private TransactionDetailResponse paidTransaction(LocalDate paymentDate) {
        return transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE, new BigDecimal("100.00"),
                        "Paid expense", paymentDate, paymentDate,
                        null, null, null, null, null, TransactionStatus.PAID, null, null),
                userId);
    }

    private TransactionDetailResponse pendingWithNoPaymentDate() {
        return transactionService.createTransaction(
                new CreateTransactionRequest(
                        accountId, TransactionType.EXPENSE, new BigDecimal("75.00"),
                        "No due date", LocalDate.now(), null,
                        null, null, null, null, null, TransactionStatus.PENDING, null, null),
                userId);
    }
}
