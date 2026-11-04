package com.cashcontrol.api;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.CreditCardService;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class BudgetApiIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private TransactionService transactionService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID accountId;
    private UUID cardId;
    private UUID food;
    private UUID grocery;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = bearer(userId);
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta", AccountType.CHECKING, "BRL", null, null, new BigDecimal("5000.00")), userId).id();
        cardId = creditCardService.createCard(
                new CreateCardRequest("Cartão", CardBrand.VISA, null, null, new BigDecimal("5000.00"), 28, 7, null), userId).id();
        food = categoryService.createCategory(new CreateCategoryRequest("Comida " + UUID.randomUUID(), null, null, null, null), userId).id();
        grocery = categoryService.createCategory(new CreateCategoryRequest("Mercado", food, null, null, null), userId).id();
    }

    @Test
    void progress_countsByPurchaseMonth_cardAsSpent_pendingBillAsForecast_subcategoryInsideParent() throws Exception {
        expense("200.00", "Feira", LocalDate.of(2026, 5, 3), TransactionStatus.PAID, food, null, false);
        expense("100.00", "Mercado no cartão", LocalDate.of(2026, 5, 10), null, food, grocery, true);
        transaction(TransactionType.REFUND, "30.00", "Estorno mercado", LocalDate.of(2026, 5, 15), TransactionStatus.PAID, food, grocery, false);
        expense("50.00", "Conta a vencer", LocalDate.of(2026, 5, 25), TransactionStatus.PENDING, food, null, false);
        expense("80.00", "Compra de abril", LocalDate.of(2026, 4, 30), TransactionStatus.PAID, food, null, false);
        expense("40.00", "Sem categoria", LocalDate.of(2026, 5, 12), TransactionStatus.PAID, null, null, false);

        saveBudget(food, "{\"amount\":500}");
        saveBudget(grocery, "{\"amount\":100}");
        saveBudget(grocery, "{\"amount\":60,\"month\":\"2026-05\"}");

        perform(get("/api/v1/budgets").param("month", "2026-05"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-05"))
                .andExpect(jsonPath("$.daysInMonth").value(31))
                .andExpect(jsonPath("$.dayOfMonth").value(31))
                .andExpect(jsonPath("$.totalBudget").value(500.00))
                .andExpect(jsonPath("$.totalSpent").value(270.00))
                .andExpect(jsonPath("$.totalForecast").value(50.00))
                .andExpect(jsonPath("$.uncategorizedSpent").value(40.00))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].categoryId").value(grocery.toString()))
                .andExpect(jsonPath("$.items[0].parentId").value(food.toString()))
                .andExpect(jsonPath("$.items[0].budget").value(60.00))
                .andExpect(jsonPath("$.items[0].spent").value(70.00))
                .andExpect(jsonPath("$.items[0].remaining").value(-10.00))
                .andExpect(jsonPath("$.items[0].percent").value(116.67))
                .andExpect(jsonPath("$.items[0].source").value("MONTH"))
                .andExpect(jsonPath("$.items[1].categoryId").value(food.toString()))
                .andExpect(jsonPath("$.items[1].spent").value(270.00))
                .andExpect(jsonPath("$.items[1].forecast").value(50.00))
                .andExpect(jsonPath("$.items[1].percent").value(54.00))
                .andExpect(jsonPath("$.items[1].source").value("DEFAULT"));

        perform(get("/api/v1/budgets").param("month", "2026-04"), token)
                .andExpect(jsonPath("$.items[0].categoryId").value(food.toString()))
                .andExpect(jsonPath("$.items[0].spent").value(80.00))
                .andExpect(jsonPath("$.items[1].budget").value(100.00))
                .andExpect(jsonPath("$.items[1].source").value("DEFAULT"));
    }

    @Test
    void deletingTheMonthAdjustment_fallsBackToTheDefault_andDeletingTheDefaultRemovesTheItem() throws Exception {
        saveBudget(grocery, "{\"amount\":100}");
        saveBudget(grocery, "{\"amount\":60,\"month\":\"2026-05\"}");

        perform(delete("/api/v1/budgets/" + grocery).param("month", "2026-05"), token).andExpect(status().isNoContent());
        perform(get("/api/v1/budgets").param("month", "2026-05"), token)
                .andExpect(jsonPath("$.items[0].budget").value(100.00))
                .andExpect(jsonPath("$.items[0].source").value("DEFAULT"));

        perform(delete("/api/v1/budgets/" + grocery), token).andExpect(status().isNoContent());
        perform(get("/api/v1/budgets").param("month", "2026-05"), token)
                .andExpect(jsonPath("$.items.length()").value(0));
        perform(delete("/api/v1/budgets/" + grocery), token).andExpect(status().isNotFound());
    }

    @Test
    void savingTwice_replacesTheCap() throws Exception {
        saveBudget(food, "{\"amount\":500}");
        saveBudget(food, "{\"amount\":650.5}");

        perform(get("/api/v1/budgets").param("month", "2026-05"), token)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].budget").value(650.50));
    }

    @Test
    void anotherUser_seesNothing_andCannotBudgetSomeoneElsesCategory() throws Exception {
        saveBudget(food, "{\"amount\":500}");
        String other = bearer(createUser());

        perform(get("/api/v1/budgets").param("month", "2026-05"), other)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        perform(put("/api/v1/budgets/" + food).content("{\"amount\":10}"), other).andExpect(status().isNotFound());
        perform(delete("/api/v1/budgets/" + food), other).andExpect(status().isNotFound());
    }

    @Test
    void rejectsZeroAmount_andRequiresAuthentication() throws Exception {
        perform(put("/api/v1/budgets/" + food).content("{\"amount\":0}"), token).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/budgets")).andExpect(status().isUnauthorized());
    }

    @Test
    void history_returnsOneCellPerMonth_withTheCapOfThatMonth_andLeavesReimbursementsOut() throws Exception {
        expense("100.00", "Feira de março", LocalDate.of(2026, 3, 10), TransactionStatus.PAID, food, null, false);
        expense("200.00", "Feira de abril", LocalDate.of(2026, 4, 10), TransactionStatus.PAID, food, null, false);
        UUID lunch = transaction(TransactionType.EXPENSE, "150.00", "Almoço com cliente", LocalDate.of(2026, 4, 12),
                TransactionStatus.PAID, food, null, false);
        UUID lunchBack = transaction(TransactionType.INCOME, "150.00", "Reembolso almoço", LocalDate.of(2026, 4, 20),
                TransactionStatus.PAID, null, null, false);
        perform(put("/api/v1/transactions/" + lunch + "/reimbursement")
                .content("{\"reimbursable\":true,\"reimbursedById\":\"" + lunchBack + "\"}"), token)
                .andExpect(status().isOk());
        expense("50.00", "Mercado no cartão", LocalDate.of(2026, 5, 3), null, food, grocery, true);

        saveBudget(food, "{\"amount\":500}");
        saveBudget(food, "{\"amount\":250,\"month\":\"2026-04\"}");

        perform(get("/api/v1/budgets/history").param("months", "3").param("until", "2026-05"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(3))
                .andExpect(jsonPath("$.months[0]").value("2026-03"))
                .andExpect(jsonPath("$.months[2]").value("2026-05"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].categoryId").value(food.toString()))
                .andExpect(jsonPath("$.items[0].cells[0].month").value("2026-03"))
                .andExpect(jsonPath("$.items[0].cells[0].budget").value(500.00))
                .andExpect(jsonPath("$.items[0].cells[0].spent").value(100.00))
                .andExpect(jsonPath("$.items[0].cells[0].percent").value(20.00))
                .andExpect(jsonPath("$.items[0].cells[1].budget").value(250.00))
                .andExpect(jsonPath("$.items[0].cells[1].spent").value(200.00))
                .andExpect(jsonPath("$.items[0].cells[1].percent").value(80.00))
                .andExpect(jsonPath("$.items[0].cells[2].budget").value(500.00))
                .andExpect(jsonPath("$.items[0].cells[2].spent").value(50.00));

        // O histórico bate com o progresso do mês.
        perform(get("/api/v1/budgets").param("month", "2026-04"), token)
                .andExpect(jsonPath("$.items[0].budget").value(250.00))
                .andExpect(jsonPath("$.items[0].spent").value(200.00));
    }

    @Test
    void history_defaultsToSixMonthsUntilTheCurrentMonth_andRejectsMonthsOutsideOneToTwelve() throws Exception {
        perform(get("/api/v1/budgets/history"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(6))
                .andExpect(jsonPath("$.months[5]").value(YearMonth.now().toString()));
        perform(get("/api/v1/budgets/history").param("months", "12"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(12));
        perform(get("/api/v1/budgets/history").param("months", "0"), token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.months").exists());
        perform(get("/api/v1/budgets/history").param("months", "13"), token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.months").exists());
        perform(get("/api/v1/budgets/history").param("until", "2026-13"), token).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/budgets/history")).andExpect(status().isUnauthorized());
    }

    private void saveBudget(UUID categoryId, String body) throws Exception {
        perform(put("/api/v1/budgets/" + categoryId).content(body), token).andExpect(status().isOk());
    }

    private void expense(String amount, String description, LocalDate date, TransactionStatus status,
                         UUID categoryId, UUID subcategoryId, boolean onCard) {
        transaction(TransactionType.EXPENSE, amount, description, date, status, categoryId, subcategoryId, onCard);
    }

    private UUID transaction(TransactionType type, String amount, String description, LocalDate date,
                             TransactionStatus status, UUID categoryId, UUID subcategoryId, boolean onCard) {
        LocalDate paymentDate = status == TransactionStatus.PAID ? date : null;
        return transactionService.createTransaction(new CreateTransactionRequest(
                accountId, type, new BigDecimal(amount), description, date, paymentDate, null,
                categoryId, subcategoryId, null, null, status,
                onCard ? PaymentMethodSlug.CREDIT_CARD : null, onCard ? cardId : null), userId).id();
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON));
    }

    private String bearer(UUID user) {
        return "Bearer " + jwtService.generateToken(user, List.of(), Instant.now());
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "budget-" + UUID.randomUUID() + "@example.com");
    }
}
