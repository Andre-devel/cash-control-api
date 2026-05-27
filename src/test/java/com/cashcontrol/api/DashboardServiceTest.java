package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.ChartGranularity;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.CategoryPieChartResponse;
import com.cashcontrol.api.dto.response.LargestExpenseResponse;
import com.cashcontrol.api.dto.response.MonthlyBarChartResponse;
import com.cashcontrol.api.dto.response.MonthlyComparisonResponse;
import com.cashcontrol.api.dto.response.NetWorthEvolutionResponse;
import com.cashcontrol.api.dto.response.OverviewMetricsResponse;
import com.cashcontrol.api.dto.response.RecentTransactionResponse;
import com.cashcontrol.api.dto.response.UpcomingBillResponse;
import com.cashcontrol.api.dto.response.UpcomingInvoiceResponse;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.DashboardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private AppProperties appProperties;
    @Mock private AppProperties.Dashboard dashboardProps;
    @InjectMocks private DashboardServiceImpl dashboardService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        when(appProperties.getDashboard()).thenReturn(dashboardProps);
        when(dashboardProps.getUpcomingBillsDays()).thenReturn(7);
        when(dashboardProps.getUpcomingBillsMaxResults()).thenReturn(20);
    }

    // ── Overview Metrics ──────────────────────────────────────────────────────

    @Test
    void getOverviewMetrics_returnsAggregatedValues() {
        when(transactionRepository.sumTotalBalanceExcludingType(eq(userId), eq(AccountType.INVESTMENT)))
                .thenReturn(new BigDecimal("5000.00"));
        when(transactionRepository.sumTotalNetWorth(eq(userId)))
                .thenReturn(new BigDecimal("8000.00"));
        when(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                eq(userId), eq(TransactionType.INCOME), any(), any(), eq(null)))
                .thenReturn(new BigDecimal("3000.00"));
        when(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                eq(userId), eq(TransactionType.EXPENSE), any(), any(), eq(null)))
                .thenReturn(new BigDecimal("1500.00"));

        OverviewMetricsResponse result = dashboardService.getOverviewMetrics(userId);

        assertThat(result.totalBalance()).isEqualByComparingTo("5000.00");
        assertThat(result.netWorth()).isEqualByComparingTo("8000.00");
        assertThat(result.monthlyIncome()).isEqualByComparingTo("3000.00");
        assertThat(result.monthlyExpenses()).isEqualByComparingTo("1500.00");
        assertThat(result.monthlySavings()).isEqualByComparingTo("1500.00");
        assertThat(result.cashFlow()).isEqualByComparingTo("1500.00");
        assertThat(result.currentMonth()).isNotBlank();
    }

    @Test
    void getOverviewMetrics_handlesNullRepositoryResults() {
        when(transactionRepository.sumTotalBalanceExcludingType(any(), any())).thenReturn(null);
        when(transactionRepository.sumTotalNetWorth(any())).thenReturn(null);
        when(transactionRepository.sumPaidByTypeAndPaymentDateRange(any(), any(), any(), any(), any()))
                .thenReturn(null);

        OverviewMetricsResponse result = dashboardService.getOverviewMetrics(userId);

        assertThat(result.totalBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.netWorth()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.monthlySavings()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Category Pie Chart ────────────────────────────────────────────────────

    @Test
    void getCategoryPieChart_returnsBreakdownWithPercentages() {
        UUID catId = UUID.randomUUID();
        Category cat = new Category();
        ReflectionTestUtils.setField(cat, "id", catId);
        cat.setName("Food");

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = LocalDate.now();

        Object[] row = new Object[]{catId, new BigDecimal("300.00")};
        List<Object[]> breakdown = new ArrayList<>();
        breakdown.add(row);
        when(transactionRepository.findCategoryBreakdown(
                eq(userId), eq(TransactionType.EXPENSE), eq(from), eq(to), eq(null)))
                .thenReturn(breakdown);
        when(transactionRepository.sumUncategorized(
                eq(userId), eq(TransactionType.EXPENSE), eq(from), eq(to), eq(null)))
                .thenReturn(new BigDecimal("100.00"));
        when(categoryRepository.findById(catId)).thenReturn(Optional.of(cat));

        CategoryPieChartResponse result = dashboardService.getCategoryPieChart(
                userId, from, to, null, TransactionType.EXPENSE);

        assertThat(result.totalAmount()).isEqualByComparingTo("400.00");
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).categoryName()).isEqualTo("Food");
        assertThat(result.entries().get(0).percentage()).isEqualByComparingTo("75.00");
        assertThat(result.entries().get(1).categoryName()).isEqualTo("UNCATEGORIZED");
        assertThat(result.entries().get(1).percentage()).isEqualByComparingTo("25.00");
    }

    @Test
    void getCategoryPieChart_noTransactions_returnsEmpty() {
        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to = LocalDate.now();

        when(transactionRepository.findCategoryBreakdown(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(transactionRepository.sumUncategorized(any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        CategoryPieChartResponse result = dashboardService.getCategoryPieChart(
                userId, from, to, null, null);

        assertThat(result.entries()).isEmpty();
        assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Monthly Bar Chart ─────────────────────────────────────────────────────

    @Test
    void getMonthlyBarChart_includesAllMonthsIncludingEmpty() {
        when(transactionRepository.findMonthlyIncomeExpense(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        MonthlyBarChartResponse result = dashboardService.getMonthlyBarChart(userId, 3, null);

        assertThat(result.months()).hasSize(3);
        result.months().forEach(entry -> {
            assertThat(entry.income()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(entry.expenses()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(entry.net()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void getMonthlyBarChart_computesNetCorrectly() {
        YearMonth current = YearMonth.now();
        String currentLabel = current.toString();

        Object[] rowIncome = new Object[]{currentLabel, "INCOME", new BigDecimal("2000.00")};
        Object[] rowExpense = new Object[]{currentLabel, "EXPENSE", new BigDecimal("800.00")};
        List<Object[]> monthlyRows = new ArrayList<>();
        monthlyRows.add(rowIncome);
        monthlyRows.add(rowExpense);
        when(transactionRepository.findMonthlyIncomeExpense(any(), any(), any(), any()))
                .thenReturn(monthlyRows);

        MonthlyBarChartResponse result = dashboardService.getMonthlyBarChart(userId, 1, null);

        assertThat(result.months()).hasSize(1);
        MonthlyBarChartResponse.Entry entry = result.months().get(0);
        assertThat(entry.income()).isEqualByComparingTo("2000.00");
        assertThat(entry.expenses()).isEqualByComparingTo("800.00");
        assertThat(entry.net()).isEqualByComparingTo("1200.00");
    }

    // ── Net Worth Evolution ───────────────────────────────────────────────────

    @Test
    void getNetWorthEvolution_monthlyGranularity_returnsOneSnapshotPerMonth() {
        when(transactionRepository.sumNetWorthUpTo(eq(userId), any()))
                .thenReturn(new BigDecimal("10000.00"));

        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        NetWorthEvolutionResponse result = dashboardService.getNetWorthEvolution(
                userId, from, to, ChartGranularity.MONTHLY);

        assertThat(result.snapshots()).isNotEmpty();
        result.snapshots().forEach(s -> assertThat(s.netWorth()).isEqualByComparingTo("10000.00"));
    }

    // ── Monthly Comparison ────────────────────────────────────────────────────

    @Test
    void getMonthlyComparison_computesDeltaValues() {
        when(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                eq(userId), eq(TransactionType.INCOME), any(), any(), eq(null)))
                .thenReturn(new BigDecimal("3000.00"), new BigDecimal("4000.00"));
        when(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                eq(userId), eq(TransactionType.EXPENSE), any(), any(), eq(null)))
                .thenReturn(new BigDecimal("1000.00"), new BigDecimal("1500.00"));
        when(transactionRepository.findCategoryBreakdown(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        MonthlyComparisonResponse result = dashboardService.getMonthlyComparison(
                userId, "2026-04", "2026-05");

        assertThat(result.month1().income()).isEqualByComparingTo("3000.00");
        assertThat(result.month2().income()).isEqualByComparingTo("4000.00");
        assertThat(result.delta().incomeDelta()).isEqualByComparingTo("1000.00");
        assertThat(result.delta().expensesDelta()).isEqualByComparingTo("500.00");
    }

    // ── Upcoming Bills ────────────────────────────────────────────────────────

    @Test
    void getUpcomingBills_returnsFormattedEntries() {
        Transaction tx = buildTransaction(userId, new BigDecimal("250.00"), TransactionStatus.PENDING);
        when(transactionRepository.findUpcomingBills(eq(userId), any(), any(), any()))
                .thenReturn(List.of(tx));

        List<UpcomingBillResponse> result = dashboardService.getUpcomingBills(userId, 7);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amount()).isEqualByComparingTo("250.00");
        assertThat(result.get(0).status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void getUpcomingBills_usesDefaultWindowWhenDaysAheadIsZero() {
        when(transactionRepository.findUpcomingBills(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        List<UpcomingBillResponse> result = dashboardService.getUpcomingBills(userId, 0);

        assertThat(result).isEmpty();
    }

    // ── Upcoming Invoices ─────────────────────────────────────────────────────

    @Test
    void getUpcomingInvoices_returnsFormattedEntries() {
        Invoice invoice = buildInvoice(new BigDecimal("1000.00"), new BigDecimal("0.00"), InvoiceStatus.CLOSED);
        when(invoiceRepository.findAllByUserIdAndDueDateLessThanEqualAndStatusIn(
                eq(userId), any(), any()))
                .thenReturn(List.of(invoice));

        List<UpcomingInvoiceResponse> result = dashboardService.getUpcomingInvoices(userId, 7);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.get(0).remainingAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.get(0).status()).isEqualTo(InvoiceStatus.CLOSED);
    }

    // ── Largest Expenses ──────────────────────────────────────────────────────

    @Test
    void getLargestExpenses_returnsOrderedByAmount() {
        Transaction t1 = buildTransaction(userId, new BigDecimal("500.00"), TransactionStatus.PAID);
        Transaction t2 = buildTransaction(userId, new BigDecimal("300.00"), TransactionStatus.PAID);
        when(transactionRepository.findLargestExpenses(eq(userId), any(), any(), any()))
                .thenReturn(List.of(t1, t2));

        List<LargestExpenseResponse> result = dashboardService.getLargestExpenses(userId, null, null, 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).amount()).isEqualByComparingTo("500.00");
    }

    // ── Recent Transactions ───────────────────────────────────────────────────

    @Test
    void getRecentTransactions_returnsFormattedEntries() {
        Transaction tx = buildTransaction(userId, new BigDecimal("100.00"), TransactionStatus.PAID);
        when(transactionRepository.findRecentTransactions(eq(userId), any()))
                .thenReturn(List.of(tx));

        List<RecentTransactionResponse> result = dashboardService.getRecentTransactions(userId, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(result.get(0).type()).isEqualTo(TransactionType.EXPENSE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildTransaction(UUID userId, BigDecimal amount, TransactionStatus status) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Account");
        account.setType(AccountType.CHECKING);

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(TransactionType.EXPENSE);
        tx.setStatus(status);
        tx.setAmount(amount);
        tx.setDescription("Test");
        tx.setCompetenceDate(LocalDate.now());
        tx.setPaymentDate(LocalDate.now().plusDays(3));
        return tx;
    }

    private Invoice buildInvoice(BigDecimal total, BigDecimal paid, InvoiceStatus status) {
        com.cashcontrol.api.domain.entity.CreditCard card = new com.cashcontrol.api.domain.entity.CreditCard();
        card.setName("My Card");
        card.setUserId(userId);

        Invoice inv = new Invoice();
        ReflectionTestUtils.setField(inv, "id", UUID.randomUUID());
        inv.setCreditCard(card);
        inv.setUserId(userId);
        inv.setStatus(status);
        inv.setTotalAmount(total);
        inv.setPaidAmount(paid);
        inv.setDueDate(LocalDate.now().plusDays(5));
        inv.setReferenceMonth("2026-05");
        inv.setClosingDate(LocalDate.now().minusDays(5));
        return inv;
    }
}
