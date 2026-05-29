package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.TransactionService;
import com.jayway.jsonpath.JsonPath;
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

import java.time.Instant;
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
class RecurrenceApiIntegrationTest {

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
                "recur-api-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void createRecurrence_returns201_withFirstInstanceGenerated() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "EXPENSE",
                    "amount": 500.00,
                    "description": "Monthly Rent",
                    "startDate": "2026-06-01",
                    "frequency": "MONTHLY"
                }
                """.formatted(accountId);

        mockMvc.perform(post("/api/v1/recurrences")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rule.id").isNotEmpty())
                .andExpect(jsonPath("$.rule.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rule.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.firstInstance.id").isNotEmpty())
                .andExpect(jsonPath("$.firstInstance.description").value("Monthly Rent"));
    }

    @Test
    void pauseRecurrence_returns200_withStatusPaused() throws Exception {
        UUID ruleId = createMonthlyRecurrence("Pausable Rent");

        mockMvc.perform(post("/api/v1/recurrences/{id}/pause", ruleId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.id").value(ruleId.toString()));
    }

    @Test
    void resumeRecurrence_returns200_withStatusActive() throws Exception {
        UUID ruleId = createMonthlyRecurrence("Resumable Rent");

        mockMvc.perform(post("/api/v1/recurrences/{id}/pause", ruleId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/recurrences/{id}/resume", ruleId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void deleteRecurrence_futureOnly_cancelsPendingInstances() throws Exception {
        UUID ruleId = createMonthlyRecurrence("Future Only Delete");

        mockMvc.perform(delete("/api/v1/recurrences/{id}", ruleId)
                        .header("Authorization", bearer())
                        .param("strategy", "FUTURE_ONLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledInstances").isNumber());
    }

    @Test
    void deleteRecurrence_all_cancelsAllPendingInstances() throws Exception {
        UUID ruleId = createMonthlyRecurrence("All Delete");

        mockMvc.perform(delete("/api/v1/recurrences/{id}", ruleId)
                        .header("Authorization", bearer())
                        .param("strategy", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledInstances").isNumber());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createMonthlyRecurrence(String description) throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "EXPENSE",
                    "amount": 1000.00,
                    "description": "%s",
                    "startDate": "2026-06-01",
                    "frequency": "MONTHLY"
                }
                """.formatted(accountId, description);

        String response = mockMvc.perform(post("/api/v1/recurrences")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(JsonPath.read(response, "$.rule.id"));
    }

    private String bearer() {
        return "Bearer " + token;
    }
}
