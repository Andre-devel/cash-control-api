package com.cashcontrol.api;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
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
import java.time.format.DateTimeFormatter;
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
class ReimbursementApiIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID accountId;
    private UUID food;
    // Dia 1 do mês corrente: o dashboard olha o mês de hoje e o lançamento não pode ser futuro.
    private LocalDate day;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = bearer(userId);
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta", AccountType.CHECKING, "BRL", null, null, new BigDecimal("5000.00")), userId).id();
        food = categoryService.createCategory(new CreateCategoryRequest("Comida " + UUID.randomUUID(), null, null, null, null), userId).id();
        day = LocalDate.now().withDayOfMonth(1);
    }

    @Test
    void reimbursedExpensesAndTheirIncomes_stayOutOfChartsBudgetAndTotals_butNotOutOfTheBalance() throws Exception {
        create(TransactionType.INCOME, "1000.00", "Salário", null);
        UUID lunch = create(TransactionType.EXPENSE, "200.00", "Almoço com cliente", food);
        UUID lunchBack = create(TransactionType.INCOME, "200.00", "Reembolso almoço", null);
        UUID taxi = create(TransactionType.EXPENSE, "100.00", "Táxi", food);
        UUID taxiBack = create(TransactionType.INCOME, "80.00", "Reembolso táxi", null);
        UUID hotel = create(TransactionType.EXPENSE, "300.00", "Hotel", food);
        create(TransactionType.EXPENSE, "50.00", "Mercado", food);

        link(lunch, lunchBack).andExpect(status().isOk())
                .andExpect(jsonPath("$.reimbursement.reimbursable").value(true))
                .andExpect(jsonPath("$.reimbursement.linkedTransactionId").value(lunchBack.toString()))
                .andExpect(jsonPath("$.reimbursement.linkedAmount").value(200.00));
        link(taxi, taxiBack).andExpect(status().isOk());
        mark(hotel, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.reimbursement.reimbursable").value(true))
                .andExpect(jsonPath("$.reimbursement.linkedTransactionId").isEmpty());

        // Só o mercado (50) e o que não voltou do táxi (20) são gasto; só o salário é receita.
        perform(get("/api/v1/dashboard/overview"), token)
                .andExpect(jsonPath("$.monthlyIncome").value(1000.00))
                .andExpect(jsonPath("$.monthlyExpenses").value(70.00))
                .andExpect(jsonPath("$.totalBalance").value(5630.00));
        perform(get("/api/v1/dashboard/charts/categories")
                        .param("from", day.toString()).param("to", day.plusMonths(1).minusDays(1).toString()), token)
                .andExpect(jsonPath("$.totalAmount").value(70.00))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].totalAmount").value(70.00));
        perform(get("/api/v1/dashboard/charts/monthly").param("months", "1"), token)
                .andExpect(jsonPath("$.months[0].income").value(1000.00))
                .andExpect(jsonPath("$.months[0].expenses").value(70.00));
        perform(get("/api/v1/dashboard/widgets/largest-expenses"), token)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value("Mercado"))
                .andExpect(jsonPath("$[1].description").value("Táxi"))
                .andExpect(jsonPath("$[1].amount").value(20.00));
        perform(get("/api/v1/transactions/summary"), token)
                .andExpect(jsonPath("$.incomeAmount").value(1000.00))
                .andExpect(jsonPath("$.expenseAmount").value(70.00));

        perform(put("/api/v1/budgets/" + food).content("{\"amount\":500}"), token).andExpect(status().isOk());
        perform(get("/api/v1/budgets").param("month", day.format(DateTimeFormatter.ofPattern("yyyy-MM"))), token)
                .andExpect(jsonPath("$.items[0].spent").value(70.00));

        perform(get("/api/v1/transactions/reimbursements/pending"), token)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(hotel.toString()))
                .andExpect(jsonPath("$[0].reimbursement.reimbursable").value(true));

        // A receita mostra a despesa que reembolsou, no detalhe e na listagem.
        perform(get("/api/v1/transactions/" + lunchBack), token)
                .andExpect(jsonPath("$.reimbursement.reimbursable").value(false))
                .andExpect(jsonPath("$.reimbursement.linkedTransactionId").value(lunch.toString()));
        perform(get("/api/v1/transactions").param("searchText", "Reembolso almoço"), token)
                .andExpect(jsonPath("$.content[0].reimbursement.linkedTransactionId").value(lunch.toString()));
    }

    @Test
    void aRefundEntryCanBeTheReimbursement_andCountsOnlyOnce() throws Exception {
        UUID shoes = create(TransactionType.EXPENSE, "100.00", "Tênis", food);
        // O banco traz o Pix de volta como REFUND, com a mesma categoria da despesa.
        UUID pixBack = create(TransactionType.REFUND, "100.00", "Pix tênis", food);

        perform(get("/api/v1/transactions/" + shoes + "/reimbursement/candidates"), token)
                .andExpect(jsonPath("$[0].id").value(pixBack.toString()));
        link(shoes, pixBack).andExpect(status().isOk());

        perform(get("/api/v1/transactions/summary"), token)
                .andExpect(jsonPath("$.incomeAmount").value(0))
                .andExpect(jsonPath("$.expenseAmount").value(0));
        perform(put("/api/v1/budgets/" + food).content("{\"amount\":500}"), token).andExpect(status().isOk());
        perform(get("/api/v1/budgets").param("month", day.format(DateTimeFormatter.ofPattern("yyyy-MM"))), token)
                .andExpect(jsonPath("$.items[0].spent").value(0));
        perform(get("/api/v1/transactions/" + pixBack), token)
                .andExpect(jsonPath("$.reimbursement.linkedTransactionId").value(shoes.toString()));
    }

    @Test
    void editSwitchesTypeBetweenIncomeAndRefundOnly() throws Exception {
        UUID income = create(TransactionType.INCOME, "100.00", "Pix", null);
        UUID expense = create(TransactionType.EXPENSE, "100.00", "Táxi", food);

        perform(put("/api/v1/transactions/" + income).content("{\"type\":\"REFUND\"}"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REFUND"));
        perform(put("/api/v1/transactions/" + income).content("{\"type\":\"INCOME\"}"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("INCOME"));
        perform(put("/api/v1/transactions/" + income).content("{\"type\":\"EXPENSE\"}"), token)
                .andExpect(status().isUnprocessableEntity());
        perform(put("/api/v1/transactions/" + expense).content("{\"type\":\"INCOME\"}"), token)
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void candidates_areUnlinkedIncomesUpToTheExpense_closestFirst() throws Exception {
        UUID expense = create(TransactionType.EXPENSE, "100.00", "Táxi", food);
        UUID linkedElsewhere = create(TransactionType.INCOME, "100.00", "Já usado", null);
        create(TransactionType.INCOME, "60.00", "Longe", null);
        create(TransactionType.INCOME, "95.00", "Perto", null);
        create(TransactionType.INCOME, "150.00", "Maior", null);
        UUID other = create(TransactionType.EXPENSE, "100.00", "Outra", food);
        link(other, linkedElsewhere).andExpect(status().isOk());

        perform(get("/api/v1/transactions/" + expense + "/reimbursement/candidates"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value("Perto"))
                .andExpect(jsonPath("$[1].description").value("Longe"));
    }

    @Test
    void rejectsInvalidLinks() throws Exception {
        UUID expense = create(TransactionType.EXPENSE, "100.00", "Táxi", food);
        UUID bigger = create(TransactionType.INCOME, "150.00", "Maior", null);
        UUID income = create(TransactionType.INCOME, "100.00", "Reembolso", null);
        UUID other = create(TransactionType.EXPENSE, "100.00", "Outra", food);

        link(expense, bigger).andExpect(status().isUnprocessableEntity());
        link(expense, other).andExpect(status().isUnprocessableEntity());
        mark(income, true).andExpect(status().isUnprocessableEntity());
        perform(put("/api/v1/transactions/" + expense + "/reimbursement")
                        .content("{\"reimbursable\":false,\"reimbursedById\":\"" + income + "\"}"), token)
                .andExpect(status().isUnprocessableEntity());

        link(other, income).andExpect(status().isOk());
        link(expense, income).andExpect(status().isUnprocessableEntity());
        // Editar a receita para um valor maior que a despesa quebraria a regra do vínculo.
        perform(put("/api/v1/transactions/" + income).content("{\"amount\":120}"), token)
                .andExpect(status().isUnprocessableEntity());

        String stranger = bearer(createUser());
        perform(put("/api/v1/transactions/" + expense + "/reimbursement").content("{\"reimbursable\":true}"), stranger)
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingOrDeletingTheIncome_putsTheExpenseBackAsPending_andUnmarkingClearsTheLink() throws Exception {
        UUID expense = create(TransactionType.EXPENSE, "100.00", "Táxi", food);
        UUID income = create(TransactionType.INCOME, "100.00", "Reembolso", null);
        link(expense, income).andExpect(status().isOk());

        perform(post("/api/v1/transactions/" + income + "/cancel"), token).andExpect(status().isOk());
        perform(get("/api/v1/transactions/reimbursements/pending"), token)
                .andExpect(jsonPath("$[0].id").value(expense.toString()));

        UUID second = create(TransactionType.INCOME, "100.00", "Reembolso de novo", null);
        link(expense, second).andExpect(status().isOk());
        perform(delete("/api/v1/transactions/" + second), token).andExpect(status().isNoContent());
        perform(get("/api/v1/transactions/" + expense), token)
                .andExpect(jsonPath("$.reimbursement.reimbursable").value(true))
                .andExpect(jsonPath("$.reimbursement.linkedTransactionId").isEmpty());

        mark(expense, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.reimbursement").isEmpty());
        perform(get("/api/v1/dashboard/overview"), token)
                .andExpect(jsonPath("$.monthlyExpenses").value(100.00));
    }

    private ResultActions link(UUID expense, UUID income) throws Exception {
        return perform(put("/api/v1/transactions/" + expense + "/reimbursement")
                .content("{\"reimbursable\":true,\"reimbursedById\":\"" + income + "\"}"), token);
    }

    private ResultActions mark(UUID transaction, boolean reimbursable) throws Exception {
        return perform(put("/api/v1/transactions/" + transaction + "/reimbursement")
                .content("{\"reimbursable\":" + reimbursable + "}"), token);
    }

    private UUID create(TransactionType type, String amount, String description, UUID categoryId) {
        return transactionService.createTransaction(new CreateTransactionRequest(
                accountId, type, new BigDecimal(amount), description, day, day, null,
                categoryId, null, null, null, TransactionStatus.PAID, null, null), userId).id();
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
                UUID.class, "reimbursement-" + UUID.randomUUID() + "@example.com");
    }
}
