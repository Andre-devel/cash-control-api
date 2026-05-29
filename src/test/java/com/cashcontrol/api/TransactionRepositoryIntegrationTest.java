package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.TransactionRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class TransactionRepositoryIntegrationTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private Account defaultAccount;

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
                "txrepo-" + UUID.randomUUID() + "@example.com");

        defaultAccount = createAccount(userId, AccountType.CHECKING, "Default Account");
    }

    // ── IT-1.1 — findWithFilters ──────────────────────────────────────────────

    @Test
    void findWithFilters_searchText_matchesDescription_regressionForLowerBytea() {
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Supermercado Pão de Açúcar", null,
                LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("50.00"), "Farmácia CVS", null,
                LocalDate.now(), LocalDate.now());

        Page<Transaction> result = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                null, null, null, null,
                null, null, "supermercado", false, PageRequest.of(0, 100));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getDescription()).isEqualTo("Supermercado Pão de Açúcar");
    }

    @Test
    void findWithFilters_searchText_matchesNotes() {
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("200.00"), "Compra diversa", "Compra parcelada",
                LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("150.00"), "Outra compra", null,
                LocalDate.now(), LocalDate.now());

        Page<Transaction> result = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                null, null, null, null,
                null, null, "parcelada", false, PageRequest.of(0, 100));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getNotes()).isEqualTo("Compra parcelada");
    }

    @Test
    void findWithFilters_allFiltersNull_scopedToUser() {
        UUID otherUser = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "txrepo-other-" + UUID.randomUUID() + "@example.com");
        Account otherAccount = createAccount(otherUser, AccountType.CHECKING, "Other Account");

        for (int i = 0; i < 3; i++) {
            saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                    new BigDecimal("100.00"), "Income " + i, null, LocalDate.now(), LocalDate.now());
        }
        for (int i = 0; i < 2; i++) {
            saveTransaction(otherUser, otherAccount, TransactionType.INCOME, TransactionStatus.PAID,
                    new BigDecimal("100.00"), "Other Income " + i, null, LocalDate.now(), LocalDate.now());
        }

        Page<Transaction> userA = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                null, null, null, null,
                null, null, null, false, PageRequest.of(0, 100));
        Page<Transaction> userB = transactionRepository.findWithFilters(
                otherUser, null, null, null, null,
                null, null, null, null,
                null, null, null, false, PageRequest.of(0, 100));

        assertThat(userA.getTotalElements()).isEqualTo(3);
        assertThat(userB.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findWithFilters_accountId_filtersToOneAccount() {
        Account secondAccount = createAccount(userId, AccountType.SAVINGS, "Savings");

        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Checking income", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, secondAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("200.00"), "Savings income", null, LocalDate.now(), LocalDate.now());

        Page<Transaction> result = transactionRepository.findWithFilters(
                userId, defaultAccount.getId(), null, null, null,
                null, null, null, null,
                null, null, null, false, PageRequest.of(0, 100));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getDescription()).isEqualTo("Checking income");
    }

    @Test
    void findWithFilters_type_filtersToMatchingType() {
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("500.00"), "Salary", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Groceries", null, LocalDate.now(), LocalDate.now());

        Page<Transaction> incomeOnly = transactionRepository.findWithFilters(
                userId, null, TransactionType.INCOME, null, null,
                null, null, null, null,
                null, null, null, false, PageRequest.of(0, 100));
        Page<Transaction> expenseOnly = transactionRepository.findWithFilters(
                userId, null, TransactionType.EXPENSE, null, null,
                null, null, null, null,
                null, null, null, false, PageRequest.of(0, 100));

        assertThat(incomeOnly.getTotalElements()).isEqualTo(1);
        assertThat(expenseOnly.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findWithFilters_status_filtersToMatchingStatus() {
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Paid bill", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PENDING,
                new BigDecimal("200.00"), "Pending bill", null, LocalDate.now(), LocalDate.now().plusDays(5));
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.CANCELLED,
                new BigDecimal("50.00"), "Cancelled bill", null, LocalDate.now(), null);

        Page<Transaction> paid = transactionRepository.findWithFilters(
                userId, null, null, TransactionStatus.PAID, null,
                null, null, null, null,
                null, null, null, true, PageRequest.of(0, 100));
        Page<Transaction> pending = transactionRepository.findWithFilters(
                userId, null, null, TransactionStatus.PENDING, null,
                null, null, null, null,
                null, null, null, true, PageRequest.of(0, 100));

        assertThat(paid.getTotalElements()).isEqualTo(1);
        assertThat(pending.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findWithFilters_competenceDateRange_returnsOnlyMiddleDate() {
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        LocalDate mar1 = LocalDate.of(2026, 3, 1);
        LocalDate may1 = LocalDate.of(2026, 5, 1);

        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("100.00"), "January", null, jan1, jan1);
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("200.00"), "March", null, mar1, mar1);
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("300.00"), "May", null, may1, may1);

        Page<Transaction> result = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 30),
                null, null, null, null, null, false, PageRequest.of(0, 100));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getDescription()).isEqualTo("March");
    }

    @Test
    void findWithFilters_paymentDateRange_returnsOnlyMiddleDate() {
        LocalDate feb1 = LocalDate.of(2026, 2, 1);
        LocalDate apr1 = LocalDate.of(2026, 4, 1);
        LocalDate jun1 = LocalDate.of(2026, 6, 1);

        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("100.00"), "February payment", null, feb1, feb1);
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("200.00"), "April payment", null, apr1, apr1);
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("300.00"), "June payment", null, jun1, jun1);

        Page<Transaction> result = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                null, null,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 31),
                null, null, null, false, PageRequest.of(0, 100));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getDescription()).isEqualTo("April payment");
    }

    @Test
    void findWithFilters_amountRange_returnsOnlyMatchingAmount() {
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("100.00"), "100 expense", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("500.00"), "500 expense", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("1000.00"), "1000 expense", null, LocalDate.now(), LocalDate.now());

        Page<Transaction> result = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                null, null, null, null,
                new BigDecimal("200.00"), new BigDecimal("600.00"),
                null, false, PageRequest.of(0, 100));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void findWithFilters_includeCancelled_controlsCancelledVisibility() {
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Paid", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.CANCELLED,
                new BigDecimal("50.00"), "Cancelled", null, LocalDate.now(), null);

        Page<Transaction> excludingCancelled = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                null, null, null, null,
                null, null, null, false, PageRequest.of(0, 100));
        Page<Transaction> includingCancelled = transactionRepository.findWithFilters(
                userId, null, null, null, null,
                null, null, null, null,
                null, null, null, true, PageRequest.of(0, 100));

        assertThat(excludingCancelled.getTotalElements()).isEqualTo(1);
        assertThat(includingCancelled.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findWithFilters_combinedFilters_narrowsResults() {
        Account secondAccount = createAccount(userId, AccountType.SAVINGS, "Savings");

        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("100.00"), "Supermercado Carrefour", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("1000.00"), "Supermercado income", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, secondAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("80.00"), "Supermercado Extra", null, LocalDate.now(), LocalDate.now());

        Page<Transaction> result = transactionRepository.findWithFilters(
                userId, defaultAccount.getId(), TransactionType.EXPENSE, null, null,
                null, null, null, null,
                null, null, "supermercado", false, PageRequest.of(0, 100));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getDescription()).isEqualTo("Supermercado Carrefour");
    }

    // ── IT-1.1 — sumPaidAmountByAccountIdAndUserId ────────────────────────────

    @Test
    void sumPaidAmountByAccountIdAndUserId_onlyIncludesPaidTransactions() {
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("1000.00"), "Income paid", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("300.00"), "Expense paid", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PENDING,
                new BigDecimal("500.00"), "Income pending", null, LocalDate.now(), null);

        BigDecimal sum = transactionRepository.sumPaidAmountByAccountIdAndUserId(defaultAccount.getId(), userId);

        assertThat(sum).isEqualByComparingTo("700.00"); // 1000 - 300
    }

    // ── IT-1.1 — sumTotalBalanceExcludingType ─────────────────────────────────

    @Test
    void sumTotalBalanceExcludingType_excludesInvestmentAccounts() {
        Account investmentAccount = createAccount(userId, AccountType.INVESTMENT, "Investment");

        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("1000.00"), "Checking income", null, LocalDate.now(), LocalDate.now());
        saveTransaction(userId, investmentAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("5000.00"), "Investment income", null, LocalDate.now(), LocalDate.now());

        BigDecimal balance = transactionRepository.sumTotalBalanceExcludingType(userId, AccountType.INVESTMENT);

        assertThat(balance).isEqualByComparingTo("1000.00");
    }

    // ── IT-1.1 — findUpcomingBills ────────────────────────────────────────────

    @Test
    void findUpcomingBills_returnsOnlyWithinDeadline() {
        LocalDate today = LocalDate.now();

        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PENDING,
                new BigDecimal("100.00"), "Bill tomorrow", null, today.plusDays(1), today.plusDays(1));
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PENDING,
                new BigDecimal("200.00"), "Bill in 3 days", null, today.plusDays(3), today.plusDays(3));
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PENDING,
                new BigDecimal("300.00"), "Bill in 10 days", null, today.plusDays(10), today.plusDays(10));

        List<Transaction> bills = transactionRepository.findUpcomingBills(
                userId,
                List.of(TransactionStatus.PENDING, TransactionStatus.OVERDUE),
                today.plusDays(7),
                PageRequest.of(0, 100));

        assertThat(bills).hasSize(2);
        assertThat(bills).extracting(Transaction::getDescription)
                .containsExactlyInAnyOrder("Bill tomorrow", "Bill in 3 days");
    }

    // ── IT-1.1 — findLargestExpenses ─────────────────────────────────────────

    @Test
    void findLargestExpenses_returnsTopNByAmountDescending() {
        LocalDate today = LocalDate.now();

        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("100.00"), "100 expense", null, today, today);
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("200.00"), "200 expense", null, today, today);
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("300.00"), "300 expense", null, today, today);
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("400.00"), "400 expense", null, today, today);
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("500.00"), "500 expense", null, today, today);

        List<Transaction> largest = transactionRepository.findLargestExpenses(
                userId, today.minusMonths(1), today, PageRequest.of(0, 3));

        assertThat(largest).hasSize(3);
        assertThat(largest.get(0).getAmount()).isEqualByComparingTo("500.00");
        assertThat(largest.get(1).getAmount()).isEqualByComparingTo("400.00");
        assertThat(largest.get(2).getAmount()).isEqualByComparingTo("300.00");
    }

    // ── IT-1.1 — findTopCategoriesByDescriptionText ───────────────────────────

    @Test
    void findTopCategoriesByDescriptionText_ranksHigherFrequencyFirst() {
        Category catA = createCategory("MercadoTestA");
        Category catB = createCategory("MercadoTestB");

        for (int i = 0; i < 3; i++) {
            Transaction t = buildTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                    new BigDecimal("100.00"), "Mercado Livre compra", null,
                    LocalDate.now(), LocalDate.now());
            t.setCategory(catA);
            transactionRepository.save(t);
        }
        Transaction tB = buildTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("80.00"), "Mercado Extra compra", null,
                LocalDate.now(), LocalDate.now());
        tB.setCategory(catB);
        transactionRepository.save(tB);

        List<Object[]> top = transactionRepository.findTopCategoriesByDescriptionText(
                userId, "Mercado", PageRequest.of(0, 5));

        assertThat(top).isNotEmpty();
        UUID topCategoryId = (UUID) top.get(0)[0];
        assertThat(topCategoryId).isEqualTo(catA.getId());
    }

    // ── IT-1.1 — markOverdueForUser ───────────────────────────────────────────

    @Test
    void markOverdueForUser_transitionsOnlyOverdueTransactions() {
        LocalDate today = LocalDate.now();

        Transaction overdueTx = saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PENDING,
                new BigDecimal("100.00"), "Overdue bill", null, today.minusDays(1), today.minusDays(1));
        Transaction futureTx = saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PENDING,
                new BigDecimal("200.00"), "Future bill", null, today.plusDays(1), today.plusDays(1));

        transactionRepository.markOverdueForUser(userId, today);

        Transaction reloadedOverdue = transactionRepository.findById(overdueTx.getId()).orElseThrow();
        Transaction reloadedFuture = transactionRepository.findById(futureTx.getId()).orElseThrow();

        assertThat(reloadedOverdue.getStatus()).isEqualTo(TransactionStatus.OVERDUE);
        assertThat(reloadedFuture.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Account createAccount(UUID ownerId, AccountType type, String name) {
        Account account = new Account();
        account.setUserId(ownerId);
        account.setName(name);
        account.setType(type);
        account.setCurrencyCode("BRL");
        return accountRepository.save(account);
    }

    private Category createCategory(String name) {
        Category category = new Category();
        category.setUserId(userId);
        category.setName(name);
        return categoryRepository.save(category);
    }

    private Transaction buildTransaction(UUID owner, Account account, TransactionType type,
                                         TransactionStatus status, BigDecimal amount,
                                         String description, String notes,
                                         LocalDate competenceDate, LocalDate paymentDate) {
        Transaction tx = new Transaction();
        tx.setUserId(owner);
        tx.setAccount(account);
        tx.setType(type);
        tx.setStatus(status);
        tx.setAmount(amount);
        tx.setDescription(description);
        tx.setNotes(notes);
        tx.setCompetenceDate(competenceDate);
        tx.setPaymentDate(paymentDate);
        return tx;
    }

    private Transaction saveTransaction(UUID owner, Account account, TransactionType type,
                                        TransactionStatus status, BigDecimal amount,
                                        String description, String notes,
                                        LocalDate competenceDate, LocalDate paymentDate) {
        return transactionRepository.save(
                buildTransaction(owner, account, type, status, amount, description, notes,
                        competenceDate, paymentDate));
    }
}
