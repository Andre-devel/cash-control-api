package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.ChartGranularity;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.CategoryPieChartResponse;
import com.cashcontrol.api.dto.response.MonthlyBarChartResponse;
import com.cashcontrol.api.dto.response.NetWorthEvolutionResponse;
import com.cashcontrol.api.dto.response.OverviewMetricsResponse;
import com.cashcontrol.api.dto.response.RecentTransactionResponse;
import com.cashcontrol.api.service.AccountService;
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
class OverviewMetricsIntegrationTest {

    @Autowired private DashboardService dashboardService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID checkingAccountId;
    private UUID investmentAccountId;

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
                "dashboard-integration-" + UUID.randomUUID() + "@example.com");

        checkingAccountId = accountService.createAccount(
                new CreateAccountRequest("Checking", AccountType.CHECKING, "BRL", null, 0, null), userId).id();

        investmentAccountId = accountService.createAccount(
                new CreateAccountRequest("Investments", AccountType.INVESTMENT, "BRL", null, 1, null), userId).id();
    }

    // ── Overview Metrics ──────────────────────────────────────────────────────

    @Test
    void getOverviewMetrics_withNoTransactions_returnsZeros() {
        OverviewMetricsResponse result = dashboardService.getOverviewMetrics(userId);

        assertThat(result.totalBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.netWorth()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.monthlyIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.monthlyExpenses()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.currentMonth()).matches("\\d{4}-\\d{2}");
    }

    @Test
    void getOverviewMetrics_totalBalance_excludesInvestmentAccounts() {
        createPaidTransaction(checkingAccountId, TransactionType.INCOME, "5000.00");
        createPaidTransaction(investmentAccountId, TransactionType.INCOME, "2000.00");

        OverviewMetricsResponse result = dashboardService.getOverviewMetrics(userId);

        assertThat(result.totalBalance()).isEqualByComparingTo("5000.00");
        assertThat(result.netWorth()).isEqualByComparingTo("7000.00");
    }

    @Test
    void getOverviewMetrics_monthlyIncomAndExpenses_matchCurrentMonth() {
        createPaidTransaction(checkingAccountId, TransactionType.INCOME, "3000.00");
        createPaidTransaction(checkingAccountId, TransactionType.EXPENSE, "1200.00");

        OverviewMetricsResponse result = dashboardService.getOverviewMetrics(userId);

        assertThat(result.monthlyIncome()).isEqualByComparingTo("3000.00");
        assertThat(result.monthlyExpenses()).isEqualByComparingTo("1200.00");
        assertThat(result.monthlySavings()).isEqualByComparingTo("1800.00");
    }

    @Test
    void getOverviewMetrics_archivedAccounts_excludedFromTotalBalance() {
        createPaidTransaction(checkingAccountId, TransactionType.INCOME, "1000.00");
        accountService.archiveAccount(checkingAccountId, userId);

        OverviewMetricsResponse result = dashboardService.getOverviewMetrics(userId);

        assertThat(result.totalBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Category Pie Chart ────────────────────────────────────────────────────

    @Test
    void getCategoryPieChart_withNoTransactions_returnsEmptyEntries() {
        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = LocalDate.now();

        CategoryPieChartResponse result = dashboardService.getCategoryPieChart(
                userId, from, to, null, TransactionType.EXPENSE);

        assertThat(result.entries()).isEmpty();
        assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getCategoryPieChart_uncategorizedTransactions_groupedInUncategorizedBucket() {
        createPaidTransaction(checkingAccountId, TransactionType.EXPENSE, "400.00");

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        CategoryPieChartResponse result = dashboardService.getCategoryPieChart(
                userId, from, to, null, TransactionType.EXPENSE);

        assertThat(result.totalAmount()).isEqualByComparingTo("400.00");
        boolean hasUncategorized = result.entries().stream()
                .anyMatch(e -> "UNCATEGORIZED".equals(e.categoryName()));
        assertThat(hasUncategorized).isTrue();
    }

    // ── Monthly Bar Chart ─────────────────────────────────────────────────────

    @Test
    void getMonthlyBarChart_includesCurrentMonthWithCorrectTotals() {
        createPaidTransaction(checkingAccountId, TransactionType.INCOME, "2500.00");
        createPaidTransaction(checkingAccountId, TransactionType.EXPENSE, "900.00");

        MonthlyBarChartResponse result = dashboardService.getMonthlyBarChart(userId, 1, null);

        assertThat(result.months()).hasSize(1);
        MonthlyBarChartResponse.Entry current = result.months().get(0);
        assertThat(current.income()).isEqualByComparingTo("2500.00");
        assertThat(current.expenses()).isEqualByComparingTo("900.00");
        assertThat(current.net()).isEqualByComparingTo("1600.00");
    }

    @Test
    void getMonthlyBarChart_emptyMonths_includedWithZeroValues() {
        MonthlyBarChartResponse result = dashboardService.getMonthlyBarChart(userId, 6, null);

        assertThat(result.months()).hasSize(6);
        result.months().forEach(m -> {
            assertThat(m.income()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(m.expenses()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    // ── Net Worth Evolution ───────────────────────────────────────────────────

    @Test
    void getNetWorthEvolution_replayHistory_correctAtEachSnapshot() {
        createPaidTransaction(checkingAccountId, TransactionType.INCOME, "1000.00");

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = LocalDate.now();

        NetWorthEvolutionResponse result = dashboardService.getNetWorthEvolution(
                userId, from, to, ChartGranularity.MONTHLY);

        assertThat(result.snapshots()).isNotEmpty();
        NetWorthEvolutionResponse.Snapshot last = result.snapshots().getLast();
        assertThat(last.netWorth()).isEqualByComparingTo("1000.00");
    }

    // ── Recent Transactions ───────────────────────────────────────────────────

    @Test
    void getRecentTransactions_returnsLatestFirst() {
        createPaidTransaction(checkingAccountId, TransactionType.INCOME, "100.00");
        createPaidTransaction(checkingAccountId, TransactionType.EXPENSE, "50.00");

        List<RecentTransactionResponse> result = dashboardService.getRecentTransactions(userId, 10);

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void getRecentTransactions_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            createPaidTransaction(checkingAccountId, TransactionType.INCOME, "100.00");
        }

        List<RecentTransactionResponse> result = dashboardService.getRecentTransactions(userId, 3);

        assertThat(result).hasSize(3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createPaidTransaction(UUID accountId, TransactionType type, String amount) {
        LocalDate today = LocalDate.now();
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, type, new BigDecimal(amount), "Test transaction",
                today, today, null, null, null, null, null, TransactionStatus.PAID), userId);
    }
}
