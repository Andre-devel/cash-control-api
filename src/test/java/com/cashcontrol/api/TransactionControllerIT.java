package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.TransactionService;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class TransactionControllerIT {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private TransactionService transactionService;

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
                "tx-pm-it-" + UUID.randomUUID() + "@example.com");

        token = jwtService.generateToken(userId, List.of(), Instant.now());

        accountId = accountService.createAccount(
                new CreateAccountRequest("Test Account", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();

        CreditCardResponse card = creditCardService.createCard(
                new CreateCardRequest("My Visa", CardBrand.VISA, "Nubank", null,
                        new BigDecimal("5000.00"), 15, 22, null),
                userId);
        creditCardId = card.id();
    }

    @Test
    void createTransaction_creditCard_withValidCard_returns201WithCardLinkage() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "EXPENSE",
                    "amount": 199.90,
                    "description": "Electronics purchase",
                    "competenceDate": "2026-06-01",
                    "status": "PENDING",
                    "paymentMethod": "CREDIT_CARD",
                    "creditCardId": "%s"
                }
                """.formatted(accountId, creditCardId);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod.slug").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.creditCard.id").value(creditCardId.toString()))
                .andExpect(jsonPath("$.creditCard.name").value("My Visa"))
                .andExpect(jsonPath("$.creditCard.brand").value("VISA"));
    }

    @Test
    void createTransaction_creditCard_missingCreditCardId_returns422() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "EXPENSE",
                    "amount": 150.00,
                    "description": "Purchase without card",
                    "competenceDate": "2026-06-01",
                    "paymentMethod": "CREDIT_CARD"
                }
                """.formatted(accountId);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createTransaction_pix_withCreditCardId_returns422() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "EXPENSE",
                    "amount": 75.00,
                    "description": "Supermarket",
                    "competenceDate": "2026-06-01",
                    "paymentMethod": "PIX",
                    "creditCardId": "%s"
                }
                """.formatted(accountId, creditCardId);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createTransaction_noPaymentMethod_defaultsToOther() throws Exception {
        String body = """
                {
                    "accountId": "%s",
                    "type": "INCOME",
                    "amount": 3000.00,
                    "description": "Salary",
                    "competenceDate": "2026-06-01",
                    "status": "PAID"
                }
                """.formatted(accountId);

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod.slug").value("OTHER"));
    }

    @Test
    void listTransactions_filterByPaymentMethod_returnsOnlyMatchingTransactions() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("50.00"), "PIX payment",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.PIX, null), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("100.00"), "Cash payment",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.CASH, null), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("200.00"), "Card payment",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                null, PaymentMethodSlug.CREDIT_CARD, creditCardId), userId);

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer())
                        .param("paymentMethod", "PIX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].description").value("PIX payment"))
                .andExpect(jsonPath("$.content[0].paymentMethod.slug").value("PIX"));
    }

    @Test
    void listTransactions_noPaymentMethodFilter_returnsAll() throws Exception {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("30.00"), "Boleto",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.BOLETO, null), userId);

        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("2000.00"), "Transfer",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.BANK_TRANSFER, null), userId);

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void summarizeTransactions_totalsEveryMatchingTransactionNotJustOnePage() throws Exception {
        LocalDate day = LocalDate.of(2026, 5, 10);
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.INCOME, new BigDecimal("1000.00"), "Salary",
                day, day, null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.BANK_TRANSFER, null), userId);
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.REFUND, new BigDecimal("20.00"), "Refund",
                day, null, null, null, null, null, null,
                TransactionStatus.PENDING, PaymentMethodSlug.PIX, null), userId);
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("300.50"), "Rent",
                day, null, null, null, null, null, null,
                TransactionStatus.PENDING, PaymentMethodSlug.BOLETO, null), userId);
        UUID cancelled = transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("999.00"), "Cancelled",
                day, day, null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.PIX, null), userId).id();
        transactionService.cancelTransaction(cancelled, userId);
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("77.00"), "Other month",
                day.plusMonths(1), day.plusMonths(1), null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.PIX, null), userId);

        mockMvc.perform(get("/api/v1/transactions/summary")
                        .header("Authorization", bearer())
                        .param("competenceDateFrom", "2026-05-01")
                        .param("competenceDateTo", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomeAmount").value(1020.00))
                .andExpect(jsonPath("$.incomeCount").value(2))
                .andExpect(jsonPath("$.expenseAmount").value(300.50))
                .andExpect(jsonPath("$.expenseCount").value(1))
                .andExpect(jsonPath("$.invoicePaymentCount").value(0))
                .andExpect(jsonPath("$.netAmount").value(719.50));

        mockMvc.perform(get("/api/v1/transactions/summary")
                        .header("Authorization", bearer())
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomeAmount").value(20.00))
                .andExpect(jsonPath("$.expenseAmount").value(300.50));
    }

    @Test
    void reactivateTransaction_cancelled_returnsToPendingAndRejectsSecondCall() throws Exception {
        UUID id = transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal("40.00"), "Gym",
                LocalDate.now(), LocalDate.now(), null, null, null, null, null,
                TransactionStatus.PAID, PaymentMethodSlug.PIX, null), userId).id();
        transactionService.cancelTransaction(id, userId);

        mockMvc.perform(post("/api/v1/transactions/{id}/reactivate", id)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/transactions/{id}/reactivate", id)
                        .header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + token;
    }
}
