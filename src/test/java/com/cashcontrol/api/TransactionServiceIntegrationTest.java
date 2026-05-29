package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRuleRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.TransactionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
class TransactionServiceIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

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
                "tx-service-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void listTransactions_withSearchText_returnsOnlyMatchingTransaction() {
        createPaidTransaction("Supermercado Pão de Açúcar", "100.00");
        createPaidTransaction("Farmácia CVS", "50.00");

        TransactionFilterRequest filter = new TransactionFilterRequest(
                null, null, null, null,
                null, null, null, null, null, null,
                "supermercado", false);

        Page<TransactionSummaryResponse> result = transactionService.listTransactions(
                filter, userId, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).description()).containsIgnoringCase("Supermercado");
    }

    @Test
    void listTransactions_pagination_worksCorrectly() {
        for (int i = 0; i < 15; i++) {
            createPaidTransaction("Transaction " + i, "100.00");
        }

        Page<TransactionSummaryResponse> page0 = transactionService.listTransactions(
                TransactionFilterRequest.empty(), userId, PageRequest.of(0, 10));
        Page<TransactionSummaryResponse> page1 = transactionService.listTransactions(
                TransactionFilterRequest.empty(), userId, PageRequest.of(1, 10));

        assertThat(page0.getContent()).hasSize(10);
        assertThat(page1.getContent()).hasSize(5);
        assertThat(page0.getTotalElements()).isEqualTo(15);
    }

    @Test
    void listTransactions_includeCancelled_false_excludesCancelledByDefault() {
        createPaidTransaction("Paid 1", "100.00");
        createPaidTransaction("Paid 2", "100.00");
        createPaidTransaction("Paid 3", "100.00");
        createCancelledTransaction("Cancelled 1");
        createCancelledTransaction("Cancelled 2");

        Page<TransactionSummaryResponse> withoutCancelled = transactionService.listTransactions(
                TransactionFilterRequest.empty(), userId, PageRequest.of(0, 20));
        assertThat(withoutCancelled.getTotalElements()).isEqualTo(3);

        TransactionFilterRequest includeAll = new TransactionFilterRequest(
                null, null, null, null, null, null, null, null, null, null, null, true);
        Page<TransactionSummaryResponse> withCancelled = transactionService.listTransactions(
                includeAll, userId, PageRequest.of(0, 20));
        assertThat(withCancelled.getTotalElements()).isEqualTo(5);
    }

    @Test
    void createTransaction_categoryRuleAutoApplied() {
        var healthCategory = categoryService.createCategory(
                new CreateCategoryRequest("Saúde", null, null, null, 0), userId);
        categoryService.createRule(
                new CreateCategoryRuleRequest("farmácia", healthCategory.id(), null, null, 0), userId);

        TransactionDetailResponse tx = transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("50.00"), "Farmácia Popular",
                        LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                        TransactionStatus.PAID),
                userId);

        assertThat(tx.categoryId()).isEqualTo(healthCategory.id());
        assertThat(tx.categoryName()).isEqualTo("Saúde");
    }

    @Test
    void detectOverdue_marksOnlyExpiredPendingTransactions() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        TransactionDetailResponse overdueCandidate = transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), "Overdue Bill",
                        yesterday, yesterday, null, null, null, null, null,
                        TransactionStatus.PENDING),
                userId);

        TransactionDetailResponse futureBill = transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("200.00"), "Future Bill",
                        tomorrow, tomorrow, null, null, null, null, null,
                        TransactionStatus.PENDING),
                userId);

        int marked = transactionService.detectOverdue(userId);
        assertThat(marked).isEqualTo(1);

        // markOverdueForUser uses clearAutomatically = true, so context is cleared
        var overdue = transactionRepository.findByIdAndUserId(overdueCandidate.id(), userId).orElseThrow();
        var pending = transactionRepository.findByIdAndUserId(futureBill.id(), userId).orElseThrow();

        assertThat(overdue.getStatus()).isEqualTo(TransactionStatus.OVERDUE);
        assertThat(pending.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void markAsPaid_persists_statusAndPaymentDate() {
        TransactionDetailResponse tx = transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("150.00"), "Pending Bill",
                        LocalDate.now(), null, null, null, null, null, null,
                        TransactionStatus.PENDING),
                userId);

        LocalDate paymentDate = LocalDate.now();
        transactionService.markAsPaid(tx.id(), new MarkAsPaidRequest(paymentDate), userId);

        entityManager.flush();
        entityManager.clear();

        var reloaded = transactionRepository.findByIdAndUserId(tx.id(), userId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(reloaded.getPaymentDate()).isEqualTo(paymentDate);
    }

    @Test
    void cancelTransaction_persists_statusAndCancelledAt() {
        TransactionDetailResponse tx = transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.INCOME,
                        new BigDecimal("300.00"), "Income to Cancel",
                        LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                        TransactionStatus.PAID),
                userId);

        transactionService.cancelTransaction(tx.id(), userId);

        entityManager.flush();
        entityManager.clear();

        var reloaded = transactionRepository.findByIdAndUserId(tx.id(), userId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(reloaded.getCancelledAt()).isNotNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createPaidTransaction(String description, String amount) {
        LocalDate today = LocalDate.now();
        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal(amount), description,
                        today, today, null, null, null, null, null, TransactionStatus.PAID),
                userId);
    }

    private void createCancelledTransaction(String description) {
        LocalDate today = LocalDate.now();
        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal("100.00"), description,
                        today, null, null, null, null, null, null, TransactionStatus.CANCELLED),
                userId);
    }
}
