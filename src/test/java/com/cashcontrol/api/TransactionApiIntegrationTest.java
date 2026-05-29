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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class TransactionApiIntegrationTest {

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
                "tx-api-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void createTransaction_income_returns201AndResponseBody() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "INCOME",
                    "amount": 500.00,
                    "description": "Salary",
                    "competenceDate": "2026-05-01",
                    "status": "PAID"
                }
                """.formatted(accountId);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("INCOME"))
                .andExpect(jsonPath("$.description").value("Salary"))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void listTransactions_noFilters_returns200AndCorrectTotal() throws Exception {
        createTransaction("First", TransactionType.INCOME, "100.00", TransactionStatus.PAID);
        createTransaction("Second", TransactionType.EXPENSE, "50.00", TransactionStatus.PAID);

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listTransactions_searchText_returnsOnlyMatchingTransaction() throws Exception {
        createTransaction("Supermercado Pão de Açúcar", TransactionType.EXPENSE, "150.00", TransactionStatus.PAID);
        createTransaction("Farmácia CVS", TransactionType.EXPENSE, "30.00", TransactionStatus.PAID);

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .param("searchText", "supermercado"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("Supermercado Pão de Açúcar"));
    }

    @Test
    void listTransactions_typeFilter_returnsOnlyIncome() throws Exception {
        createTransaction("Income Tx", TransactionType.INCOME, "200.00", TransactionStatus.PAID);
        createTransaction("Expense Tx", TransactionType.EXPENSE, "80.00", TransactionStatus.PAID);

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .param("type", "INCOME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("INCOME"));
    }

    @Test
    void listTransactions_competenceDateRangeFilter_returnsOnlyInRange() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("100.00"), "January Bill",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15),
                null, null, null, null, null, TransactionStatus.PAID), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("100.00"), "February Bill",
                LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 15),
                null, null, null, null, null, TransactionStatus.PAID), userId);

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .param("competenceDateFrom", "2026-01-01")
                        .param("competenceDateTo", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("January Bill"));
    }

    @Test
    void getTransaction_existingId_returns200WithDetails() throws Exception {
        UUID txId = createTransaction("Detail Test", TransactionType.INCOME, "300.00", TransactionStatus.PAID);

        mockMvc.perform(get("/api/v1/transactions/{id}", txId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId.toString()))
                .andExpect(jsonPath("$.description").value("Detail Test"))
                .andExpect(jsonPath("$.amount").value(300.00));
    }

    @Test
    void editTransaction_validRequest_returns200WithUpdatedFields() throws Exception {
        UUID txId = createTransaction("Original Description", TransactionType.EXPENSE, "100.00", TransactionStatus.PENDING);

        String editBody = """
                {
                    "description": "Updated Description",
                    "amount": 150.00
                }
                """;

        mockMvc.perform(put("/api/v1/transactions/{id}", txId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated Description"));
    }

    @Test
    void markAsPaid_pendingTransaction_returns200AndStatusPaid() throws Exception {
        UUID txId = createTransaction("Pending Bill", TransactionType.EXPENSE, "200.00", TransactionStatus.PENDING);

        String payBody = """
                {
                    "paymentDate": "2026-05-29"
                }
                """;

        mockMvc.perform(post("/api/v1/transactions/{id}/pay", txId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentDate").value("2026-05-29"));
    }

    @Test
    void cancelTransaction_returns200AndStatusCancelled() throws Exception {
        UUID txId = createTransaction("To Cancel", TransactionType.EXPENSE, "75.00", TransactionStatus.PAID);

        mockMvc.perform(post("/api/v1/transactions/{id}/cancel", txId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void deleteTransaction_returns204() throws Exception {
        UUID txId = createTransaction("To Delete", TransactionType.EXPENSE, "50.00", TransactionStatus.PENDING);

        mockMvc.perform(delete("/api/v1/transactions/{id}", txId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/transactions/{id}", txId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void listTransactions_withoutJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTransaction_anotherUsersTransaction_returns404() throws Exception {
        UUID otherUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW() - INTERVAL '1 minute') " +
                "RETURNING id",
                UUID.class,
                "other-user-tx-" + UUID.randomUUID() + "@example.com");

        UUID otherAccountId = accountService.createAccount(
                new CreateAccountRequest("Other Account", AccountType.CHECKING, "BRL", null, 0, null),
                otherUserId).id();

        UUID otherTxId = transactionService.createTransaction(new CreateTransactionRequest(
                otherAccountId, TransactionType.INCOME, new BigDecimal("100.00"), "Other User Income",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null, TransactionStatus.PAID),
                otherUserId).id();

        mockMvc.perform(get("/api/v1/transactions/{id}", otherTxId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createTransaction(String description, TransactionType type, String amount, TransactionStatus status) {
        return transactionService.createTransaction(new CreateTransactionRequest(
                accountId, type, new BigDecimal(amount), description,
                LocalDate.now(), LocalDate.now(), null, null, null, null, null, status),
                userId).id();
    }

    private String bearer() {
        return "Bearer " + token;
    }
}
