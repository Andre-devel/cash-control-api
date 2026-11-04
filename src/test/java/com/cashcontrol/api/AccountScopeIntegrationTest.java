package com.cashcontrol.api;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountScope;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.EditAccountRequest;
import com.cashcontrol.api.dto.request.TransferRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Escopo pessoa física / jurídica (V52): o filtro {@code scope} dos agregados e listagens, a
 * transferência entre escopos marcada e fora de receita/despesa, e o padrão PERSONAL.
 *
 * <p>Cenário do mês corrente: conta PF com receita 1000 e despesa 100; conta PJ com receita 5000
 * e despesa 300; compra de 50 no cartão PJ, debitada da conta PF (pendente, conta no orçamento e
 * na fatura); transferência de 200
 * da PF para a PJ e de 10 entre duas contas PF.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class AccountScopeIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private TransactionService transactionService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private LocalDate today;
    private UUID personalId;
    private UUID savingsId;
    private UUID businessId;

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
                "scope-" + UUID.randomUUID() + "@example.com");
        token = jwtService.generateToken(userId, List.of(), Instant.now());
        today = LocalDate.now();

        personalId = accountService.createAccount(
                new CreateAccountRequest("Conta PF", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        savingsId = accountService.createAccount(
                new CreateAccountRequest("Poupança PF", AccountType.SAVINGS, "BRL", null, 1, null), userId).id();
        businessId = accountService.createAccount(
                new CreateAccountRequest("Conta PJ", AccountType.CHECKING, "BRL", null, 2, null,
                        AccountScope.BUSINESS), userId).id();
        UUID businessCardId = creditCardService.createCard(new CreateCardRequest(
                "Cartão PJ", CardBrand.VISA, "Banco", null, new BigDecimal("5000.00"), 10, 20, null,
                AccountScope.BUSINESS), userId).id();

        paid(personalId, TransactionType.INCOME, "1000.00", "Salário");
        paid(personalId, TransactionType.EXPENSE, "100.00", "Mercado");
        paid(businessId, TransactionType.INCOME, "5000.00", "Nota fiscal");
        paid(businessId, TransactionType.EXPENSE, "300.00", "Contador");
        // Compra no cartão PJ debitada da conta PF: vale o escopo do cartão.
        transactionService.createTransaction(new CreateTransactionRequest(
                personalId, TransactionType.EXPENSE, new BigDecimal("50.00"), "Software", today, null,
                null, null, null, null, null, TransactionStatus.PENDING, PaymentMethodSlug.CREDIT_CARD,
                businessCardId), userId);
        accountService.createTransfer(
                new TransferRequest(personalId, businessId, new BigDecimal("200.00"), "Aporte", today), userId);
        accountService.createTransfer(
                new TransferRequest(personalId, savingsId, new BigDecimal("10.00"), "Guardar", today), userId);
    }

    // ── Padrão e campo nas contas e cartões ─────────────────────────────────

    @Test
    void newAccountWithoutScope_defaultsToPersonal() throws Exception {
        perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nova\",\"type\":\"CHECKING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("PERSONAL"));

        perform(post("/api/v1/cards")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Novo\",\"brand\":\"VISA\",\"creditLimit\":1000.00,\"closingDay\":5,\"dueDay\":12}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("PERSONAL"));

        String scopeInDb = jdbcTemplate.queryForObject(
                "SELECT scope FROM accounts WHERE id = ?", String.class, personalId);
        assertThat(scopeInDb).isEqualTo("PERSONAL");
    }

    @Test
    void editWithoutScope_keepsIt_andWithScope_changesIt() {
        assertThat(accountService.editAccount(businessId,
                new EditAccountRequest("Conta PJ", AccountType.CHECKING, null, null, 2), userId).scope())
                .isEqualTo(AccountScope.BUSINESS);
        assertThat(accountService.editAccount(savingsId,
                new EditAccountRequest("Poupança PF", AccountType.SAVINGS, null, null, 1, AccountScope.BUSINESS),
                userId).scope())
                .isEqualTo(AccountScope.BUSINESS);
    }

    @Test
    void invalidScopeInDatabase_isRejectedByCheckConstraint() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE accounts SET scope = 'OTHER' WHERE id = ?", personalId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void accountAndCardLists_filterByScope() throws Exception {
        perform(get("/api/v1/accounts").param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(businessId.toString()))
                .andExpect(jsonPath("$[0].scope").value("BUSINESS"));
        perform(get("/api/v1/accounts").param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].scope", everyItem(is("PERSONAL"))));
        perform(get("/api/v1/accounts")).andExpect(jsonPath("$.length()").value(3));
        perform(get("/api/v1/accounts").param("scope", "ALL")).andExpect(jsonPath("$.length()").value(3));

        perform(get("/api/v1/cards").param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scope").value("BUSINESS"));
        perform(get("/api/v1/cards").param("scope", "PERSONAL")).andExpect(jsonPath("$.length()").value(0));
        perform(get("/api/v1/cards")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidScopeParameter_returns400() throws Exception {
        perform(get("/api/v1/dashboard/overview").param("scope", "PJ")).andExpect(status().isBadRequest());
    }

    // ── Agregados do dashboard ──────────────────────────────────────────────

    @Test
    void overview_filtersByScope_andAllSumsEverything() throws Exception {
        perform(get("/api/v1/dashboard/overview").param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.monthlyIncome").value(1000.00))
                .andExpect(jsonPath("$.monthlyExpenses").value(100.00))
                .andExpect(jsonPath("$.totalBalance").value(700.00));
        perform(get("/api/v1/dashboard/overview").param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.monthlyIncome").value(5000.00))
                .andExpect(jsonPath("$.monthlyExpenses").value(300.00))
                .andExpect(jsonPath("$.totalBalance").value(4900.00))
                .andExpect(jsonPath("$.openCardDebt").value(50.00));
        perform(get("/api/v1/dashboard/overview").param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.openCardDebt").value(0.0));

        // As transferências (PF→PJ e PF→PF) não entram em receita nem despesa em nenhum escopo.
        String all = perform(get("/api/v1/dashboard/overview").param("scope", "ALL"))
                .andExpect(jsonPath("$.monthlyIncome").value(6000.00))
                .andExpect(jsonPath("$.monthlyExpenses").value(400.00))
                .andExpect(jsonPath("$.totalBalance").value(5600.00))
                .andReturn().getResponse().getContentAsString();
        perform(get("/api/v1/dashboard/overview")).andExpect(content().json(all));
    }

    @Test
    void charts_filterByScope() throws Exception {
        String from = today.withDayOfMonth(1).toString();
        String to = today.withDayOfMonth(today.lengthOfMonth()).toString();

        perform(get("/api/v1/dashboard/charts/monthly").param("months", "1").param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.months[0].income").value(5000.00))
                .andExpect(jsonPath("$.months[0].expenses").value(300.00));
        perform(get("/api/v1/dashboard/charts/monthly").param("months", "1"))
                .andExpect(jsonPath("$.months[0].income").value(6000.00))
                .andExpect(jsonPath("$.months[0].expenses").value(400.00));

        perform(get("/api/v1/dashboard/charts/categories").param("from", from).param("to", to)
                .param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.totalAmount").value(100.00));
        perform(get("/api/v1/dashboard/charts/categories").param("from", from).param("to", to)
                .param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.totalAmount").value(300.00));
        perform(get("/api/v1/dashboard/charts/categories").param("from", from).param("to", to))
                .andExpect(jsonPath("$.totalAmount").value(400.00));

        String month = YearMonth.from(today).toString();
        perform(get("/api/v1/dashboard/charts/comparison").param("month1", month).param("month2", month)
                .param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.month2.income").value(5000.00))
                .andExpect(jsonPath("$.month2.expenses").value(300.00));

        perform(get("/api/v1/dashboard/widgets/largest-expenses").param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].description").value("Mercado"));
        perform(get("/api/v1/dashboard/widgets/recent-transactions").param("limit", "50").param("scope", "BUSINESS"))
                .andExpect(jsonPath("$[*].description", hasItem("Software")))
                .andExpect(jsonPath("$[*].description", not(hasItem("Mercado"))));
        perform(get("/api/v1/dashboard/widgets/upcoming-invoices").param("daysAhead", "400").param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void netWorthAndProjection_filterByScope() throws Exception {
        perform(get("/api/v1/dashboard/charts/net-worth")
                .param("from", today.toString()).param("to", today.toString()).param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.snapshots[0].netWorth").value(700.00));
        perform(get("/api/v1/dashboard/charts/net-worth")
                .param("from", today.toString()).param("to", today.toString()).param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.snapshots[0].netWorth").value(4900.00));
        perform(get("/api/v1/dashboard/charts/net-worth")
                .param("from", today.toString()).param("to", today.toString()))
                .andExpect(jsonPath("$.snapshots[0].netWorth").value(5600.00));

        perform(get("/api/v1/dashboard/projection").param("days", "30").param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.startBalance").value(4900.00));
        perform(get("/api/v1/dashboard/projection").param("days", "60").param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.startBalance").value(700.00))
                .andExpect(jsonPath("$.events.length()").value(0));
        perform(get("/api/v1/dashboard/projection").param("days", "30"))
                .andExpect(jsonPath("$.startBalance").value(5600.00));
    }

    @Test
    void budget_filtersSpendingByScope() throws Exception {
        String month = YearMonth.from(today).toString();
        // O orçamento conta pela data da compra: a compra no cartão PJ entra no escopo do cartão.
        perform(get("/api/v1/budgets").param("month", month).param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.uncategorizedSpent").value(100.00));
        perform(get("/api/v1/budgets").param("month", month).param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.uncategorizedSpent").value(350.00));
        perform(get("/api/v1/budgets").param("month", month))
                .andExpect(jsonPath("$.uncategorizedSpent").value(450.00));
        perform(get("/api/v1/budgets/history").param("months", "3").param("scope", "BUSINESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(3));
    }

    // ── Listagem de transações e transferência entre escopos ────────────────

    @Test
    void transactionList_filtersByScopeOfCardOrAccount() throws Exception {
        perform(get("/api/v1/transactions").param("scope", "BUSINESS").param("size", "50"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].description", hasItem("Software")));
        perform(get("/api/v1/transactions").param("scope", "PERSONAL").param("size", "50"))
                .andExpect(jsonPath("$.totalElements").value(5));
        perform(get("/api/v1/transactions").param("size", "50"))
                .andExpect(jsonPath("$.totalElements").value(9));

        perform(get("/api/v1/transactions/summary").param("scope", "BUSINESS"))
                .andExpect(jsonPath("$.incomeAmount").value(5000.00))
                .andExpect(jsonPath("$.expenseAmount").value(350.00));
        perform(get("/api/v1/transactions/summary").param("scope", "PERSONAL"))
                .andExpect(jsonPath("$.incomeAmount").value(1000.00))
                .andExpect(jsonPath("$.expenseAmount").value(100.00));
        perform(get("/api/v1/transactions/summary"))
                .andExpect(jsonPath("$.incomeAmount").value(6000.00))
                .andExpect(jsonPath("$.expenseAmount").value(450.00));
    }

    @Test
    void transferBetweenScopes_isMarkedCrossScope_andSameScopeIsNot() throws Exception {
        UUID crossLeg = transferLeg(businessId);
        UUID sameScopeLeg = transferLeg(savingsId);

        perform(get("/api/v1/transactions/" + crossLeg))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.crossScope").value(true));
        perform(get("/api/v1/transactions/" + sameScopeLeg))
                .andExpect(jsonPath("$.crossScope").value(false));

        perform(get("/api/v1/transactions").param("type", "TRANSFER").param("size", "50"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[?(@.description == 'Aporte')].crossScope", everyItem(is(true))))
                .andExpect(jsonPath("$.content[?(@.description == 'Guardar')].crossScope", everyItem(is(false))));
        perform(get("/api/v1/transactions").param("type", "EXPENSE").param("size", "50"))
                .andExpect(jsonPath("$.content[*].crossScope", everyItem(is(false))));
    }

    private void paid(UUID accountId, TransactionType type, String amount, String description) {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, type, new BigDecimal(amount), description, today, today,
                null, null, null, null, null, TransactionStatus.PAID, null, null), userId);
    }

    private UUID transferLeg(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM transactions WHERE account_id = ? AND type = 'TRANSFER'", UUID.class, accountId);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }
}
