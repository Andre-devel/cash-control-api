package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.ChartGranularity;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.CategoryPieChartResponse;
import com.cashcontrol.api.dto.response.LargestExpenseResponse;
import com.cashcontrol.api.dto.response.MonthlyBarChartResponse;
import com.cashcontrol.api.dto.response.NetWorthEvolutionResponse;
import com.cashcontrol.api.dto.response.UpcomingBillResponse;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.DashboardService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class DashboardServiceIntegrationTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;
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
                "dashboard-service-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void getCategoryPieChart_correctBreakdown() {
        var moradia = categoryService.createCategory(
                new CreateCategoryRequest("Moradia", null, null, null, 0), userId);
        var alimentacao = categoryService.createCategory(
                new CreateCategoryRequest("Alimentação", null, null, null, 1), userId);
        var saude = categoryService.createCategory(
                new CreateCategoryRequest("Saúde", null, null, null, 2), userId);

        LocalDate today = LocalDate.now();
        createPaidExpense("Aluguel", "500.00", today, moradia.id());
        createPaidExpense("Supermercado", "300.00", today, alimentacao.id());
        createPaidExpense("Médico", "200.00", today, saude.id());

        LocalDate from = today.withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());

        CategoryPieChartResponse result = dashboardService.getCategoryPieChart(
                userId, from, to, null, TransactionType.EXPENSE);

        assertThat(result.totalAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.entries()).hasSize(3);

        var moradiaEntry = result.entries().stream()
                .filter(e -> "Moradia".equals(e.categoryName()))
                .findFirst().orElseThrow();
        assertThat(moradiaEntry.totalAmount()).isEqualByComparingTo("500.00");
        assertThat(moradiaEntry.percentage()).isEqualByComparingTo("50.00");

        BigDecimal sumOfPercentages = result.entries().stream()
                .map(CategoryPieChartResponse.Entry::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumOfPercentages).isEqualByComparingTo("100.00");

        // Moradia should be the first (largest) entry
        assertThat(result.entries().get(0).categoryName()).isEqualTo("Moradia");
    }

    @Test
    void getMonthlyBarChart_monthsWithNoTransactionsFilled() {
        // Seed transactions 2 months ago and in current month, skipping the middle month
        LocalDate twoMonthsAgo = LocalDate.now().minusMonths(2).withDayOfMonth(15);
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);

        createPaidIncome("Income month 1", "1000.00", twoMonthsAgo);
        createPaidIncome("Income month 3", "500.00", currentMonth);

        MonthlyBarChartResponse result = dashboardService.getMonthlyBarChart(userId, 3, null);

        assertThat(result.months()).hasSize(3);

        // Middle month (1 month ago) should have zero income and expenses
        MonthlyBarChartResponse.Entry middleMonth = result.months().get(1);
        assertThat(middleMonth.income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(middleMonth.expenses()).isEqualByComparingTo(BigDecimal.ZERO);

        // First month should have the income we seeded
        MonthlyBarChartResponse.Entry firstMonth = result.months().get(0);
        assertThat(firstMonth.income()).isEqualByComparingTo("1000.00");

        // Current month should have the income we seeded
        MonthlyBarChartResponse.Entry lastMonth = result.months().get(2);
        assertThat(lastMonth.income()).isEqualByComparingTo("500.00");
    }

    @Test
    void getNetWorthEvolution_correctSnapshotValues() {
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        LocalDate feb1 = LocalDate.of(2026, 2, 1);
        LocalDate feb28 = LocalDate.of(2026, 2, 28);

        createPaidIncome("Income January", "1000.00", jan1);
        createPaidExpense("Expense February", "200.00", feb1, null);

        NetWorthEvolutionResponse result = dashboardService.getNetWorthEvolution(
                userId, jan1, feb28, ChartGranularity.MONTHLY);

        assertThat(result.snapshots()).isNotEmpty();

        // First snapshot (at jan1) should have netWorth = 1000
        NetWorthEvolutionResponse.Snapshot first = result.snapshots().getFirst();
        assertThat(first.date()).isEqualTo(jan1);
        assertThat(first.netWorth()).isEqualByComparingTo("1000.00");

        // Last snapshot (at feb28) should include both transactions: 1000 - 200 = 800
        NetWorthEvolutionResponse.Snapshot last = result.snapshots().getLast();
        assertThat(last.date()).isEqualTo(feb28);
        assertThat(last.netWorth()).isEqualByComparingTo("800.00");
    }

    @Test
    void getLargestExpenses_topNOrderedByAmount() {
        LocalDate today = LocalDate.now();
        createPaidExpense("Small expense", "50.00", today, null);
        createPaidExpense("Medium expense", "200.00", today, null);
        createPaidExpense("Large expense", "500.00", today, null);
        createPaidExpense("Largest expense", "1000.00", today, null);
        createPaidExpense("Tiny expense", "10.00", today, null);

        LocalDate from = today.withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());

        List<LargestExpenseResponse> result = dashboardService.getLargestExpenses(userId, from, to, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).amount()).isEqualByComparingTo("1000.00");
        assertThat(result.get(1).amount()).isEqualByComparingTo("500.00");
        assertThat(result.get(2).amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void getUpcomingBills_onlyWithinWindow() {
        LocalDate today = LocalDate.now();
        createPendingExpense("Bill in 1 day", "100.00", today.plusDays(1));
        createPendingExpense("Bill in 3 days", "200.00", today.plusDays(3));
        createPendingExpense("Bill in 8 days", "300.00", today.plusDays(8));

        List<UpcomingBillResponse> result = dashboardService.getUpcomingBills(userId, 7);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).paymentDate()).isEqualTo(today.plusDays(1));
        assertThat(result.get(1).paymentDate()).isEqualTo(today.plusDays(3));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createPaidExpense(String description, String amount, LocalDate paymentDate, UUID categoryId) {
        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal(amount), description,
                        paymentDate, paymentDate, null, categoryId, null, null, null,
                        TransactionStatus.PAID, null, null),
                userId);
    }

    private void createPaidIncome(String description, String amount, LocalDate paymentDate) {
        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.INCOME,
                        new BigDecimal(amount), description,
                        paymentDate, paymentDate, null, null, null, null, null,
                        TransactionStatus.PAID, null, null),
                userId);
    }

    private void createPendingExpense(String description, String amount, LocalDate paymentDate) {
        transactionService.createTransaction(
                new CreateTransactionRequest(accountId, TransactionType.EXPENSE,
                        new BigDecimal(amount), description,
                        paymentDate, paymentDate, null, null, null, null, null,
                        TransactionStatus.PENDING, null, null),
                userId);
    }
}
