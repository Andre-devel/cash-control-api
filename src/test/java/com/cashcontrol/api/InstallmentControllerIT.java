package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class InstallmentControllerIT {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID accountId;
    private UUID creditCardId;

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
                "inst-pm-it-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();

        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("My Mastercard", CardBrand.MASTERCARD, "Itaú", null,
                        new BigDecimal("10000.00"), 10, 17, null),
                userId);
        creditCardId = card.id();
    }

    @Test
    void createInstallmentSeries_creditCard_allInstallmentsCarryCardReference() throws Exception {
        String firstPaymentDate = LocalDate.now().plusMonths(1).withDayOfMonth(1).toString();
        String body = """
                {
                    "accountId": "%s",
                    "totalAmount": 1200.00,
                    "totalInstallments": 3,
                    "firstPaymentDate": "%s",
                    "description": "New smartphone",
                    "paymentMethod": "CREDIT_CARD",
                    "creditCardId": "%s"
                }
                """.formatted(accountId, firstPaymentDate, creditCardId);

        mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.installments").isArray())
                .andExpect(jsonPath("$.installments.length()").value(3))
                .andExpect(jsonPath("$.installments[0].paymentMethod.slug").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.installments[1].paymentMethod.slug").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.installments[2].paymentMethod.slug").value("CREDIT_CARD"));
    }

    @Test
    void createInstallmentSeries_creditCard_missingCreditCardId_returns422() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "totalAmount": 600.00,
                    "totalInstallments": 2,
                    "firstPaymentDate": "%s",
                    "description": "Purchase without card id",
                    "paymentMethod": "CREDIT_CARD"
                }
                """.formatted(accountId, LocalDate.now().plusMonths(1).toString());

        mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createInstallmentSeries_defaultPaymentMethod_usesOther() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "totalAmount": 300.00,
                    "totalInstallments": 3,
                    "firstPaymentDate": "%s",
                    "description": "Furniture"
                }
                """.formatted(accountId, LocalDate.now().plusMonths(1).toString());

        mockMvc.perform(post("/api/v1/installments")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.installments[0].paymentMethod.slug").value("OTHER"))
                .andExpect(jsonPath("$.installments[1].paymentMethod.slug").value("OTHER"))
                .andExpect(jsonPath("$.installments[2].paymentMethod.slug").value("OTHER"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
