package com.cashcontrol.api;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

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
import org.springframework.http.MediaType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class RecurrenceSuggestionIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID accountId;
    private LocalDate today;

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
                "recur-suggest-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Corrente", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
        today = LocalDate.now();

        for (int monthsAgo = 1; monthsAgo <= 3; monthsAgo++) {
            paidExpense("HOSTINGER* " + monthsAgo + "/12", "39.90", today.minusMonths(monthsAgo));
        }
        paidExpense("Spotify", "21.90", today.minusMonths(1));
        paidExpense("Spotify", "21.90", today.minusMonths(2));
    }

    @Test
    void listSuggestions_returnsGroupPresentInThreeMonths_only() throws Exception {
        mockMvc.perform(get("/api/v1/recurrences/suggestions").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].pattern").value("hostinger"))
                .andExpect(jsonPath("$[0].description").value("HOSTINGER* 1/12"))
                .andExpect(jsonPath("$[0].amount").value(39.90))
                .andExpect(jsonPath("$[0].months").value(3))
                .andExpect(jsonPath("$[0].dayOfMonth").value(today.minusMonths(1).getDayOfMonth()))
                .andExpect(jsonPath("$[0].lastDate").value(today.minusMonths(1).toString()))
                .andExpect(jsonPath("$[0].accountId").value(accountId.toString()))
                .andExpect(jsonPath("$[0].creditCardId").doesNotExist());
    }

    @Test
    void dismissSuggestion_removesItFromTheList_andIsIdempotent() throws Exception {
        dismiss("hostinger");
        dismiss("hostinger");

        mockMvc.perform(get("/api/v1/recurrences/suggestions").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        Integer stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM recurrence_suggestion_dismissals WHERE user_id = ?", Integer.class, userId);
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void dismissSuggestion_rejectsBlankPattern() throws Exception {
        mockMvc.perform(post("/api/v1/recurrences/suggestions/dismiss")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listSuggestions_suppressedByActiveRecurrenceWithSameNormalizedDescription() throws Exception {
        createMonthlyRecurrence("Hostinger", "39.90");

        mockMvc.perform(get("/api/v1/recurrences/suggestions").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listSuggestions_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/recurrences/suggestions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void subscriptions_sumsActiveMonthlyExpenseRecurrencesAndSuggestions() throws Exception {
        createMonthlyRecurrence("Aluguel", "1000.00");

        mockMvc.perform(get("/api/v1/dashboard/subscriptions").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTotal").value(1039.90))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].source").value(containsInAnyOrder("RECURRENCE", "SUGGESTION")))
                .andExpect(jsonPath("$.items[*].description")
                        .value(containsInAnyOrder("Aluguel", "HOSTINGER* 1/12")));
    }

    @Test
    void subscriptions_leaveOutDismissedSuggestions() throws Exception {
        dismiss("hostinger");

        mockMvc.perform(get("/api/v1/dashboard/subscriptions").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTotal").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void paidExpense(String description, String amount, LocalDate date) {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal(amount), description, date, date,
                null, null, null, null, null, TransactionStatus.PAID, null, null), userId);
    }

    private void dismiss(String pattern) throws Exception {
        mockMvc.perform(post("/api/v1/recurrences/suggestions/dismiss")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\": \"%s\"}".formatted(pattern)))
                .andExpect(status().isNoContent());
    }

    private void createMonthlyRecurrence(String description, String amount) throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "EXPENSE",
                    "amount": %s,
                    "description": "%s",
                    "startDate": "%s",
                    "frequency": "MONTHLY"
                }
                """.formatted(accountId, amount, description, today.plusDays(1));

        mockMvc.perform(post("/api/v1/recurrences")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String bearer() {
        return "Bearer " + token;
    }
}
