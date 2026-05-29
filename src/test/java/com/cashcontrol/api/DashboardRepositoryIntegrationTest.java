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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class DashboardRepositoryIntegrationTest {

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
                "dashrepo-" + UUID.randomUUID() + "@example.com");

        defaultAccount = createAccount(userId, "Default", AccountType.CHECKING);
    }

    @Test
    void findMonthlyIncomeExpense_nativeQuery_returnsCorrectRowsPerMonth() {
        LocalDate march1 = LocalDate.of(2026, 3, 15);
        LocalDate april1 = LocalDate.of(2026, 4, 15);

        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("1000.00"), march1);
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("300.00"), march1);
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("500.00"), april1);

        List<Object[]> rows = transactionRepository.findMonthlyIncomeExpense(
                userId,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 4, 30),
                null);

        // Expect 3 rows: March INCOME, March EXPENSE, April INCOME
        assertThat(rows).hasSize(3);

        boolean hasMarchIncome = rows.stream().anyMatch(r ->
                "2026-03".equals(r[0]) && "INCOME".equals(r[1]));
        boolean hasMarchExpense = rows.stream().anyMatch(r ->
                "2026-03".equals(r[0]) && "EXPENSE".equals(r[1]));
        boolean hasAprilIncome = rows.stream().anyMatch(r ->
                "2026-04".equals(r[0]) && "INCOME".equals(r[1]));

        assertThat(hasMarchIncome).isTrue();
        assertThat(hasMarchExpense).isTrue();
        assertThat(hasAprilIncome).isTrue();
    }

    @Test
    void findMonthlyIncomeExpense_withSpecificAccountId_filtersToAccount() {
        Account secondAccount = createAccount(userId, "Second", AccountType.SAVINGS);

        LocalDate date = LocalDate.of(2026, 5, 15);
        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("800.00"), date);
        saveTransaction(userId, secondAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("400.00"), date);

        List<Object[]> withAccount = transactionRepository.findMonthlyIncomeExpense(
                userId,
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 31),
                defaultAccount.getId());

        assertThat(withAccount).hasSize(1);
        Object[] row = withAccount.get(0);
        assertThat(row[0]).isEqualTo("2026-05");
        assertThat(new BigDecimal(row[2].toString())).isEqualByComparingTo("800.00");
    }

    @Test
    void sumNetWorthUpTo_computesCumulativeSumByDate() {
        LocalDate jan15 = LocalDate.of(2026, 1, 15);
        LocalDate feb15 = LocalDate.of(2026, 2, 15);

        saveTransaction(userId, defaultAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("1000.00"), jan15);
        saveTransaction(userId, defaultAccount, TransactionType.EXPENSE, TransactionStatus.PAID,
                new BigDecimal("200.00"), feb15);

        BigDecimal netWorthAtJan = transactionRepository.sumNetWorthUpTo(userId, jan15);
        BigDecimal netWorthAtFeb = transactionRepository.sumNetWorthUpTo(userId, feb15);

        assertThat(netWorthAtJan).isEqualByComparingTo("1000.00");
        assertThat(netWorthAtFeb).isEqualByComparingTo("800.00");
    }

    @Test
    void findCategoryBreakdown_groupsAndOrdersByAmountDesc() {
        Category catHousing = createCategory("DashHousing");
        Category catFood = createCategory("DashFood");

        LocalDate today = LocalDate.now();
        saveTransactionWithCategory(userId, defaultAccount, catHousing, new BigDecimal("500.00"), today);
        saveTransactionWithCategory(userId, defaultAccount, catFood, new BigDecimal("300.00"), today);
        saveTransactionWithCategory(userId, defaultAccount, catFood, new BigDecimal("100.00"), today);

        List<Object[]> breakdown = transactionRepository.findCategoryBreakdown(
                userId, TransactionType.EXPENSE,
                today.withDayOfMonth(1), today,
                null);

        assertThat(breakdown).hasSize(2);
        UUID firstCategory = (UUID) breakdown.get(0)[0];
        BigDecimal firstAmount = (BigDecimal) breakdown.get(0)[1];

        // catHousing has 500.00 > catFood has 400.00 (300+100), so housing is first
        assertThat(firstCategory).isEqualTo(catHousing.getId());
        assertThat(firstAmount).isEqualByComparingTo("500.00");
    }

    @Test
    void sumTotalNetWorth_excludesArchivedAndDeletedAccounts() {
        Account activeAccount = createAccount(userId, "Active Account", AccountType.CHECKING);
        Account archivedAccount = createAccount(userId, "Archived Account", AccountType.CHECKING);
        archivedAccount.setArchivedAt(Instant.now());
        accountRepository.save(archivedAccount);

        LocalDate today = LocalDate.now();
        saveTransaction(userId, activeAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("1000.00"), today);
        saveTransaction(userId, archivedAccount, TransactionType.INCOME, TransactionStatus.PAID,
                new BigDecimal("5000.00"), today);

        BigDecimal netWorth = transactionRepository.sumTotalNetWorth(userId);

        assertThat(netWorth).isEqualByComparingTo("1000.00");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Account createAccount(UUID ownerId, String name, AccountType type) {
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

    private void saveTransaction(UUID ownerId, Account account, TransactionType type,
                                  TransactionStatus status, BigDecimal amount, LocalDate paymentDate) {
        Transaction tx = new Transaction();
        tx.setUserId(ownerId);
        tx.setAccount(account);
        tx.setType(type);
        tx.setStatus(status);
        tx.setAmount(amount);
        tx.setDescription("Test transaction");
        tx.setCompetenceDate(paymentDate);
        tx.setPaymentDate(paymentDate);
        transactionRepository.save(tx);
    }

    private void saveTransactionWithCategory(UUID ownerId, Account account, Category category,
                                              BigDecimal amount, LocalDate date) {
        Transaction tx = new Transaction();
        tx.setUserId(ownerId);
        tx.setAccount(account);
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(TransactionStatus.PAID);
        tx.setAmount(amount);
        tx.setDescription("Categorized expense");
        tx.setCompetenceDate(date);
        tx.setPaymentDate(date);
        tx.setCategory(category);
        transactionRepository.save(tx);
    }
}
