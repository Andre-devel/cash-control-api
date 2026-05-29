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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AccountApiIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;

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
                "acct-api-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());
    }

    @Test
    void createAccount_returns201AndIdInResponse() throws Exception {
        String body = """
                {
                    "name": "Nubank Checking",
                    "type": "CHECKING",
                    "currencyCode": "BRL",
                    "sortOrder": 0
                }
                """;

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Nubank Checking"))
                .andExpect(jsonPath("$.type").value("CHECKING"));
    }

    @Test
    void createAccount_duplicateName_returns409() throws Exception {
        accountService.createAccount(
                new CreateAccountRequest("Duplicate", AccountType.CHECKING, "BRL", null, 0, null), userId);

        String body = """
                {
                    "name": "Duplicate",
                    "type": "SAVINGS",
                    "currencyCode": "BRL",
                    "sortOrder": 1
                }
                """;

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void listAccounts_excludesArchivedByDefault() throws Exception {
        accountService.createAccount(
                new CreateAccountRequest("Active Account", AccountType.CHECKING, "BRL", null, 0, null), userId);
        var archived = accountService.createAccount(
                new CreateAccountRequest("Archived Account", AccountType.SAVINGS, "BRL", null, 1, null), userId);
        accountService.archiveAccount(archived.id(), userId);

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Active Account"));
    }

    @Test
    void listAccounts_includeArchived_returnsAll() throws Exception {
        accountService.createAccount(
                new CreateAccountRequest("Active", AccountType.CHECKING, "BRL", null, 0, null), userId);
        var archived = accountService.createAccount(
                new CreateAccountRequest("Archived", AccountType.SAVINGS, "BRL", null, 1, null), userId);
        accountService.archiveAccount(archived.id(), userId);

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", bearer())
                        .param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void archiveAndUnarchive_cycle_works() throws Exception {
        var account = accountService.createAccount(
                new CreateAccountRequest("Cycle Account", AccountType.CHECKING, "BRL", null, 0, null), userId);

        mockMvc.perform(post("/api/v1/accounts/{id}/archive", account.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/accounts/{id}/unarchive", account.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value((Object) null));
    }

    @Test
    void deleteAccount_withExtraTransactions_returns422() throws Exception {
        var account = accountService.createAccount(
                new CreateAccountRequest("Account With Transactions", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("100.00")),
                userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                account.id(), TransactionType.EXPENSE, new BigDecimal("100.00"), "Extra expense",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null, TransactionStatus.PAID),
                userId);

        mockMvc.perform(delete("/api/v1/accounts/{id}", account.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void manualAdjustment_updatesBalance() throws Exception {
        var account = accountService.createAccount(
                new CreateAccountRequest("Adjustable", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("100.00")), userId);

        String adjustBody = """
                {
                    "amount": 50.00,
                    "description": "Extra deposit",
                    "date": "2026-05-29"
                }
                """;

        mockMvc.perform(post("/api/v1/accounts/{id}/adjust", account.id())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150.00));
    }

    @Test
    void getAccount_anotherUsersAccount_returns404() throws Exception {
        UUID otherUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW() - INTERVAL '1 minute') " +
                "RETURNING id",
                UUID.class,
                "other-acct-api-" + UUID.randomUUID() + "@example.com");

        var otherAccount = accountService.createAccount(
                new CreateAccountRequest("Other Account", AccountType.CHECKING, "BRL", null, 0, null),
                otherUserId);

        mockMvc.perform(get("/api/v1/accounts/{id}", otherAccount.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
