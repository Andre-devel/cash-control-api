package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.InstallmentService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class InstallmentApiIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private InstallmentService installmentService;

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
                "install-api-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();
    }

    @Test
    void createInstallmentSeries_returns201_andCorrectInstallmentCount() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "totalAmount": 1200.00,
                    "totalInstallments": 3,
                    "firstPaymentDate": "2026-06-01",
                    "description": "New Laptop"
                }
                """.formatted(accountId);

        mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.series.id").isNotEmpty())
                .andExpect(jsonPath("$.series.totalInstallments").value(3))
                .andExpect(jsonPath("$.series.description").value("New Laptop"))
                .andExpect(jsonPath("$.installments.length()").value(3));
    }

    @Test
    void listTransactions_showsInstallmentsFromCreatedSeries() throws Exception {
        String createBody = """
                {
                    "accountId": "%s",
                    "totalAmount": 600.00,
                    "totalInstallments": 3,
                    "firstPaymentDate": "2026-07-01",
                    "description": "Smartphone"
                }
                """.formatted(accountId);

        mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .param("includeCancelled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void editSeries_updatesDescriptionOnNonDetachedInstallments() throws Exception {
        String createBody = """
                {
                    "accountId": "%s",
                    "totalAmount": 900.00,
                    "totalInstallments": 3,
                    "firstPaymentDate": "2026-08-01",
                    "description": "Original Series Description"
                }
                """.formatted(accountId);

        String createResponse = mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID seriesId = UUID.fromString(JsonPath.read(createResponse, "$.series.id"));

        String editBody = """
                {
                    "description": "Updated Series Description"
                }
                """;

        mockMvc.perform(put("/api/v1/installments/series/{seriesId}", seriesId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedInstallments").value(3));
    }

    @Test
    void editInstallment_individualEdit_detachesFromSeries() throws Exception {
        String createBody = """
                {
                    "accountId": "%s",
                    "totalAmount": 300.00,
                    "totalInstallments": 2,
                    "firstPaymentDate": "2026-09-01",
                    "description": "To Detach"
                }
                """.formatted(accountId);

        String createResponse = mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID firstInstallmentId = UUID.fromString(JsonPath.read(createResponse, "$.installments[0].id"));

        String editBody = """
                {
                    "description": "Individually Edited"
                }
                """;

        mockMvc.perform(put("/api/v1/installments/{transactionId}", firstInstallmentId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Individually Edited"))
                .andExpect(jsonPath("$.detached").value(true));
    }

    @Test
    void earlySettlement_cancelsPendingInstallments() throws Exception {
        String createBody = """
                {
                    "accountId": "%s",
                    "totalAmount": 600.00,
                    "totalInstallments": 3,
                    "firstPaymentDate": "2026-10-01",
                    "description": "To Settle Early"
                }
                """.formatted(accountId);

        String createResponse = mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID seriesId = UUID.fromString(JsonPath.read(createResponse, "$.series.id"));

        String settleBody = """
                {
                    "settlementAmount": 550.00,
                    "settlementDate": "2026-10-15"
                }
                """;

        mockMvc.perform(post("/api/v1/installments/series/{seriesId}/settle", seriesId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settleBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledInstallments").value(3))
                .andExpect(jsonPath("$.settlementTransaction.id").isNotEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
