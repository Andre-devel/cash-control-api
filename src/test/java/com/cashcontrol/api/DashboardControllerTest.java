package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.ChartGranularity;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.response.CategoryPieChartResponse;
import com.cashcontrol.api.dto.response.LargestExpenseResponse;
import com.cashcontrol.api.dto.response.MonthlyBarChartResponse;
import com.cashcontrol.api.dto.response.MonthlyComparisonResponse;
import com.cashcontrol.api.dto.response.NetWorthEvolutionResponse;
import com.cashcontrol.api.dto.response.OverviewMetricsResponse;
import com.cashcontrol.api.dto.response.RecentTransactionResponse;
import com.cashcontrol.api.dto.response.UpcomingBillResponse;
import com.cashcontrol.api.dto.response.UpcomingInvoiceResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class DashboardControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @MockitoBean private DashboardService dashboardService;

    private MockMvc mockMvc;
    private AuthenticatedUser principal;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        userId = UUID.randomUUID();
        principal = buildAuthenticatedUser(userId);
    }

    // ── GET /api/v1/dashboard/overview ────────────────────────────────────────

    @Test
    void getOverviewMetrics_returns200() throws Exception {
        OverviewMetricsResponse response = new OverviewMetricsResponse(
                new BigDecimal("5000.00"), new BigDecimal("8000.00"),
                new BigDecimal("3000.00"), new BigDecimal("1500.00"),
                new BigDecimal("1500.00"), new BigDecimal("1500.00"),
                "2026-05");

        when(dashboardService.getOverviewMetrics(userId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/overview")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBalance").value(5000.00))
                .andExpect(jsonPath("$.netWorth").value(8000.00))
                .andExpect(jsonPath("$.monthlyIncome").value(3000.00))
                .andExpect(jsonPath("$.currentMonth").value("2026-05"));
    }

    @Test
    void getOverviewMetrics_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/overview"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/dashboard/charts/categories ───────────────────────────────

    @Test
    void getCategoryPieChart_returns200() throws Exception {
        CategoryPieChartResponse response = new CategoryPieChartResponse(
                List.of(new CategoryPieChartResponse.Entry(
                        UUID.randomUUID(), "Food", new BigDecimal("300.00"), new BigDecimal("75.00"))),
                new BigDecimal("400.00"));

        when(dashboardService.getCategoryPieChart(eq(userId), any(), any(), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/charts/categories")
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(400.00))
                .andExpect(jsonPath("$.entries[0].categoryName").value("Food"));
    }

    @Test
    void getCategoryPieChart_missingRequiredParams_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/charts/categories")
                        .with(user(principal)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/v1/dashboard/charts/monthly ─────────────────────────────────

    @Test
    void getMonthlyBarChart_returns200() throws Exception {
        MonthlyBarChartResponse response = new MonthlyBarChartResponse(
                List.of(new MonthlyBarChartResponse.Entry(
                        "2026-05", new BigDecimal("2000.00"),
                        new BigDecimal("800.00"), new BigDecimal("1200.00"))));

        when(dashboardService.getMonthlyBarChart(eq(userId), eq(6), eq(null)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/charts/monthly")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months[0].month").value("2026-05"))
                .andExpect(jsonPath("$.months[0].income").value(2000.00));
    }

    @Test
    void getMonthlyBarChart_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/charts/monthly"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/v1/dashboard/charts/net-worth ────────────────────────────────

    @Test
    void getNetWorthEvolution_returns200() throws Exception {
        NetWorthEvolutionResponse response = new NetWorthEvolutionResponse(
                List.of(new NetWorthEvolutionResponse.Snapshot(
                        LocalDate.of(2026, 5, 1), new BigDecimal("10000.00"))));

        when(dashboardService.getNetWorthEvolution(
                eq(userId), any(), any(), eq(ChartGranularity.MONTHLY)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard/charts/net-worth")
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].netWorth").value(10000.00));
    }

    // ── GET /api/v1/dashboard/charts/comparison ───────────────────────────────

    @Test
    void getMonthlyComparison_returns200() throws Exception {
        MonthlyComparisonResponse.MonthMetrics m1 = new MonthlyComparisonResponse.MonthMetrics(
                "2026-04", new BigDecimal("3000.00"), new BigDecimal("1000.00"),
                new BigDecimal("2000.00"), Collections.emptyList());
        MonthlyComparisonResponse.MonthMetrics m2 = new MonthlyComparisonResponse.MonthMetrics(
                "2026-05", new BigDecimal("4000.00"), new BigDecimal("1500.00"),
                new BigDecimal("2500.00"), Collections.emptyList());
        MonthlyComparisonResponse.Delta delta = new MonthlyComparisonResponse.Delta(
                new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("500.00"),
                new BigDecimal("33.33"), new BigDecimal("50.00"), new BigDecimal("25.00"));

        when(dashboardService.getMonthlyComparison(userId, "2026-04", "2026-05"))
                .thenReturn(new MonthlyComparisonResponse(m1, m2, delta));

        mockMvc.perform(get("/api/v1/dashboard/charts/comparison")
                        .param("month1", "2026-04")
                        .param("month2", "2026-05")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month1.month").value("2026-04"))
                .andExpect(jsonPath("$.month2.income").value(4000.00))
                .andExpect(jsonPath("$.delta.incomeDelta").value(1000.00));
    }

    // ── GET /api/v1/dashboard/widgets/upcoming-bills ──────────────────────────

    @Test
    void getUpcomingBills_returns200() throws Exception {
        UpcomingBillResponse bill = new UpcomingBillResponse(
                UUID.randomUUID(), new BigDecimal("250.00"), "Electricity",
                "Checking", "Utilities", LocalDate.now().plusDays(3), TransactionStatus.PENDING);

        when(dashboardService.getUpcomingBills(eq(userId), eq(0))).thenReturn(List.of(bill));

        mockMvc.perform(get("/api/v1/dashboard/widgets/upcoming-bills")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Electricity"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    // ── GET /api/v1/dashboard/widgets/upcoming-invoices ───────────────────────

    @Test
    void getUpcomingInvoices_returns200() throws Exception {
        UpcomingInvoiceResponse invoice = new UpcomingInvoiceResponse(
                UUID.randomUUID(), "My Visa", new BigDecimal("1500.00"),
                BigDecimal.ZERO, new BigDecimal("1500.00"),
                LocalDate.now().plusDays(5), InvoiceStatus.CLOSED);

        when(dashboardService.getUpcomingInvoices(eq(userId), eq(0))).thenReturn(List.of(invoice));

        mockMvc.perform(get("/api/v1/dashboard/widgets/upcoming-invoices")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cardName").value("My Visa"))
                .andExpect(jsonPath("$[0].status").value("CLOSED"));
    }

    // ── GET /api/v1/dashboard/widgets/largest-expenses ───────────────────────

    @Test
    void getLargestExpenses_returns200() throws Exception {
        LargestExpenseResponse expense = new LargestExpenseResponse(
                UUID.randomUUID(), new BigDecimal("800.00"), "Rent",
                "Housing", "Checking", LocalDate.now());

        when(dashboardService.getLargestExpenses(eq(userId), any(), any(), eq(5)))
                .thenReturn(List.of(expense));

        mockMvc.perform(get("/api/v1/dashboard/widgets/largest-expenses")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Rent"))
                .andExpect(jsonPath("$[0].amount").value(800.00));
    }

    // ── GET /api/v1/dashboard/widgets/recent-transactions ────────────────────

    @Test
    void getRecentTransactions_returns200() throws Exception {
        RecentTransactionResponse tx = new RecentTransactionResponse(
                UUID.randomUUID(), new BigDecimal("100.00"), "Groceries",
                TransactionType.EXPENSE, TransactionStatus.PAID,
                "Checking", "Food", LocalDate.now());

        when(dashboardService.getRecentTransactions(eq(userId), eq(10))).thenReturn(List.of(tx));

        mockMvc.perform(get("/api/v1/dashboard/widgets/recent-transactions")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].description").value("Groceries"))
                .andExpect(jsonPath("$[0].type").value("EXPENSE"));
    }

    @Test
    void getRecentTransactions_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/widgets/recent-transactions"))
                .andExpect(status().isUnauthorized());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUser buildAuthenticatedUser(UUID userId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        return new AuthenticatedUser(user, createAuthorityList("ROLE_USER"));
    }
}
