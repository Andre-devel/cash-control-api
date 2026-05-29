package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class DashboardApiIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW() - INTERVAL '1 minute') " +
                "RETURNING id",
                UUID.class,
                "dash-api-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        accountId = accountService.createAccount(
                new CreateAccountRequest("Main Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void getOverviewMetrics_returns200_withAllRequiredFields() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/overview")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBalance").exists())
                .andExpect(jsonPath("$.netWorth").exists())
                .andExpect(jsonPath("$.monthlyIncome").exists())
                .andExpect(jsonPath("$.monthlyExpenses").exists())
                .andExpect(jsonPath("$.monthlySavings").exists())
                .andExpect(jsonPath("$.cashFlow").exists())
                .andExpect(jsonPath("$.currentMonth").exists());
    }

    @Test
    void getCategoryPieChart_returns200_withSlicesAfterSeeding() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("200.00"), "Rent",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1),
                null, null, null, null, null, TransactionStatus.PAID), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("100.00"), "Groceries",
                LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10),
                null, null, null, null, null, TransactionStatus.PAID), userId);

        mockMvc.perform(get("/api/v1/dashboard/charts/categories")
                        .header("Authorization", bearer())
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.totalAmount").value(300.00));
    }

    @Test
    void getMonthlyBarChart_returns200_withRequestedMonthCount() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/charts/monthly")
                        .header("Authorization", bearer())
                        .param("months", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months").isArray())
                .andExpect(jsonPath("$.months.length()").value(3));
    }

    @Test
    void getNetWorthEvolution_returns200_withDateKeyedEntries() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("1000.00"), "January Salary",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1),
                null, null, null, null, null, TransactionStatus.PAID), userId);

        mockMvc.perform(get("/api/v1/dashboard/charts/net-worth")
                        .header("Authorization", bearer())
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots").isArray())
                .andExpect(jsonPath("$.snapshots.length()").value(greaterThan(0)));
    }

    @Test
    void getUpcomingBills_returnsOnlyWithinWindow() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("200.00"), "Bill In Window",
                LocalDate.now().plusDays(3), LocalDate.now().plusDays(3),
                null, null, null, null, null, TransactionStatus.PENDING), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("300.00"), "Bill Out Of Window",
                LocalDate.now().plusDays(20), LocalDate.now().plusDays(20),
                null, null, null, null, null, TransactionStatus.PENDING), userId);

        mockMvc.perform(get("/api/v1/dashboard/widgets/upcoming-bills")
                        .header("Authorization", bearer())
                        .param("daysAhead", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value("Bill In Window"));
    }

    @Test
    void getLargestExpenses_returnsSortedByAmountDescending() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("1000.00"), "Largest",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null, TransactionStatus.PAID), userId);
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("500.00"), "Middle",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null, TransactionStatus.PAID), userId);
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("200.00"), "Smallest",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null, TransactionStatus.PAID), userId);

        mockMvc.perform(get("/api/v1/dashboard/widgets/largest-expenses")
                        .header("Authorization", bearer())
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].description").value("Largest"))
                .andExpect(jsonPath("$[1].description").value("Middle"))
                .andExpect(jsonPath("$[2].description").value("Smallest"));
    }

    @Test
    void getRecentTransactions_returnsOrderedByCompetenceDateDescending() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("100.00"), "January Income",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15),
                null, null, null, null, null, TransactionStatus.PAID), userId);
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("200.00"), "March Income",
                LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 15),
                null, null, null, null, null, TransactionStatus.PAID), userId);

        mockMvc.perform(get("/api/v1/dashboard/widgets/recent-transactions")
                        .header("Authorization", bearer())
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$[0].description").value("March Income"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
