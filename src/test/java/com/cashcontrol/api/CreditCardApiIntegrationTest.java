package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.RecordChargeRequest;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class CreditCardApiIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID sourceAccountId;

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
                "cc-api-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        sourceAccountId = accountService.createAccount(
                new CreateAccountRequest("Source Account", AccountType.CHECKING, "BRL", null, 0,
                        new BigDecimal("10000.00")), userId).id();
    }

    @Test
    void createCard_returns201_andFirstInvoiceIsOpened() throws Exception {
        String body = """
                {
                    "name": "Nubank Gold",
                    "brand": "VISA",
                    "issuer": "Nubank",
                    "creditLimit": 5000.00,
                    "closingDay": 15,
                    "dueDay": 10
                }
                """;

        String cardJson = mockMvc.perform(post("/api/v1/cards")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Nubank Gold"))
                .andExpect(jsonPath("$.brand").value("VISA"))
                .andExpect(jsonPath("$.creditLimit").value(5000.00))
                .andReturn().getResponse().getContentAsString();

        String cardIdStr = JsonPath.read(cardJson, "$.id");
        LocalDate today = LocalDate.now();
        int closingDay = 15;
        YearMonth invoiceMonth = today.getDayOfMonth() <= closingDay
                ? YearMonth.from(today) : YearMonth.from(today).plusMonths(1);
        String currentMonth = invoiceMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));

        mockMvc.perform(get("/api/v1/cards/{id}/invoices/{referenceMonth}", cardIdStr, currentMonth)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.totalAmount").value(0.00));
    }

    @Test
    void recordCharge_updatesInvoiceTotal() throws Exception {
        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("Charge Test Card", CardBrand.MASTERCARD, "Bank",
                        new BigDecimal("5000.00"), 15, 10, null), userId);

        LocalDate chargeDate = LocalDate.of(2026, 5, 5);
        String chargeBody = """
                {
                    "description": "Grocery shopping",
                    "amount": 250.00,
                    "competenceDate": "2026-05-05"
                }
                """;

        mockMvc.perform(post("/api/v1/cards/{id}/charges", card.id())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chargeBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.description").value("Grocery shopping"))
                .andExpect(jsonPath("$.amount").value(250.00));

        // May 5 with closingDay=15: dayOfMonth(5) <= closingDay(15) → invoice belongs to May 2026
        String referenceMonth = "2026-05";
        mockMvc.perform(get("/api/v1/cards/{id}/invoices/{referenceMonth}", card.id(), referenceMonth)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(250.00))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void getInvoice_returnsInvoiceDetailWithItems() throws Exception {
        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("Invoice Detail Card", CardBrand.ELO, "Bank",
                        new BigDecimal("3000.00"), 15, 10, null), userId);

        LocalDate chargeDate = LocalDate.of(2026, 4, 10);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Restaurant", new BigDecimal("100.00"), chargeDate, null, null, null),
                userId);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Movie tickets", new BigDecimal("60.00"), chargeDate, null, null, null),
                userId);

        mockMvc.perform(get("/api/v1/cards/{id}/invoices/2026-04", card.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceMonth").value("2026-04"))
                .andExpect(jsonPath("$.totalAmount").value(160.00))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void payInvoice_fullPayment_marksInvoiceAsPaid() throws Exception {
        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("Pay Test Card", CardBrand.VISA, "Bank",
                        new BigDecimal("2000.00"), 15, 10, null), userId);

        LocalDate chargeDate = LocalDate.of(2026, 3, 5);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Electronics", new BigDecimal("500.00"), chargeDate, null, null, null),
                userId);

        String invoiceJson = mockMvc.perform(get("/api/v1/cards/{id}/invoices/2026-03", card.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String invoiceId = JsonPath.read(invoiceJson, "$.id");

        // Simulate billing cycle close (the scheduler would do this for past invoices)
        jdbcTemplate.update("UPDATE invoices SET status = 'CLOSED' WHERE id = ?::uuid", invoiceId);

        String payBody = """
                {
                    "amount": 500.00,
                    "sourceAccountId": "%s",
                    "paymentDate": "2026-04-10"
                }
                """.formatted(sourceAccountId);

        mockMvc.perform(post("/api/v1/cards/invoices/{invoiceId}/pay", invoiceId)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAmount").value(500.00));
    }

    @Test
    void getLimitUsage_reflectsCharges() throws Exception {
        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("Limit Usage Card", CardBrand.VISA, "Bank",
                        new BigDecimal("1000.00"), 15, 10, null), userId);

        LocalDate chargeDate = LocalDate.of(2026, 5, 5);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Charge 1", new BigDecimal("300.00"), chargeDate, null, null, null),
                userId);
        creditCardService.recordCharge(card.id(),
                new RecordChargeRequest("Charge 2", new BigDecimal("200.00"), chargeDate, null, null, null),
                userId);

        mockMvc.perform(get("/api/v1/cards/{id}/limit", card.id())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value(1000.00))
                .andExpect(jsonPath("$.usedLimit").value(500.00))
                .andExpect(jsonPath("$.availableLimit").value(500.00));
    }

    @Test
    void recordCharge_onArchivedCard_returns422() throws Exception {
        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("Archived Card", CardBrand.VISA, "Bank",
                        new BigDecimal("5000.00"), 15, 10, null), userId);
        creditCardService.archiveCard(card.id(), userId);

        String chargeBody = """
                {
                    "description": "Should fail",
                    "amount": 100.00,
                    "competenceDate": "2026-05-15"
                }
                """;

        mockMvc.perform(post("/api/v1/cards/{id}/charges", card.id())
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chargeBody))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
