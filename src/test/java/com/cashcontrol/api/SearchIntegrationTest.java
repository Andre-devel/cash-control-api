package com.cashcontrol.api;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.security.JwtService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class SearchIntegrationTest {

    private static final String PIX = "(SELECT id FROM payment_methods WHERE slug = 'PIX')";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = bearer(userId);
        accountId = account(userId, "Nubank Conta");
    }

    @Test
    void findsTransactionByDescription_caseInsensitive() throws Exception {
        UUID id = transaction(userId, accountId, "Supermercado Pão de Açúcar", null, "87.40", LocalDate.of(2026, 10, 3));
        transaction(userId, accountId, "Aluguel", null, "1500.00", LocalDate.of(2026, 10, 5));

        search("SUPERMERCADO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].id").value(id.toString()))
                .andExpect(jsonPath("$.transactions[0].title").value("Supermercado Pão de Açúcar"))
                .andExpect(jsonPath("$.transactions[0].subtitle").value("Nubank Conta"))
                .andExpect(jsonPath("$.transactions[0].date").value("2026-10-03"))
                .andExpect(jsonPath("$.transactions[0].amount").value(87.40))
                .andExpect(jsonPath("$.transactions[0].type").value("EXPENSE"));
    }

    @Test
    void findsTransactionByCounterparty() throws Exception {
        UUID id = transaction(userId, accountId, "Pix enviado", "Maria Fernanda Souza", "250.00", LocalDate.now());

        search("fernanda")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].id").value(id.toString()))
                .andExpect(jsonPath("$.transactions[0].subtitle").value("Maria Fernanda Souza"));
    }

    @Test
    void findsTransactionByExactPtBrAmount() throws Exception {
        UUID exact = transaction(userId, accountId, "Conta de luz", null, "615.23", LocalDate.now());
        transaction(userId, accountId, "Conta de água", null, "615.20", LocalDate.now());
        UUID thousands = transaction(userId, accountId, "Notebook", null, "1234.56", LocalDate.now());

        search("615,23")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].id").value(exact.toString()));

        search("1.234,56")
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].id").value(thousands.toString()));
    }

    @Test
    void searchesAccountsCardsCategoriesRecurrencesAndInstallments() throws Exception {
        UUID account = account(userId, "Reserva Viagem");
        UUID card = creditCard(userId, "Cartão Viagem", "Itaú", "4321");
        UUID category = category(userId, "Viagem Europa");
        UUID recurrence = recurrence(userId, accountId, "Seguro viagem anual");
        UUID series = installmentSeries(userId, accountId, "Passagem viagem");

        search("viagem")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[*].id").value(hasItem(account.toString())))
                .andExpect(jsonPath("$.creditCards[0].id").value(card.toString()))
                .andExpect(jsonPath("$.creditCards[0].subtitle").value("Itaú · final 4321"))
                .andExpect(jsonPath("$.categories[*].id").value(hasItem(category.toString())))
                .andExpect(jsonPath("$.recurrences[0].id").value(recurrence.toString()))
                .andExpect(jsonPath("$.recurrences[0].subtitle").value("Mensal · Nubank Conta"))
                .andExpect(jsonPath("$.installments[0].id").value(series.toString()))
                .andExpect(jsonPath("$.installments[0].subtitle").value("10 parcelas"));
    }

    @Test
    void leavesOutDeletedRecords() throws Exception {
        UUID deletedAccount = account(userId, "Conta Antiga Fechada");
        transaction(userId, deletedAccount, "Antiga tarifa", null, "10.00", LocalDate.now());
        jdbcTemplate.update("UPDATE accounts SET deleted_at = NOW() WHERE id = ?", deletedAccount);
        UUID card = creditCard(userId, "Antigo Cartão", null, null);
        jdbcTemplate.update("UPDATE credit_cards SET deleted_at = NOW() WHERE id = ?", card);

        search("antig")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(0))
                .andExpect(jsonPath("$.accounts.length()").value(0))
                .andExpect(jsonPath("$.creditCards.length()").value(0));
    }

    @Test
    void doesNotReturnAnotherUsersData() throws Exception {
        UUID otherUser = createUser();
        UUID otherAccount = account(otherUser, "Conta Secreta");
        UUID otherTransaction = transaction(otherUser, otherAccount, "Secreta compra", "Fornecedor Secreto", "615.23",
                LocalDate.now());
        creditCard(otherUser, "Cartão Secreto", null, null);
        category(otherUser, "Categoria Secreta");
        recurrence(otherUser, otherAccount, "Assinatura secreta");
        installmentSeries(otherUser, otherAccount, "Parcela secreta");

        search("secret")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(0))
                .andExpect(jsonPath("$.accounts.length()").value(0))
                .andExpect(jsonPath("$.creditCards.length()").value(0))
                .andExpect(jsonPath("$.categories.length()").value(0))
                .andExpect(jsonPath("$.recurrences.length()").value(0))
                .andExpect(jsonPath("$.installments.length()").value(0));

        search("615,23")
                .andExpect(jsonPath("$.transactions[*].id").value(not(hasItem(otherTransaction.toString()))));
    }

    @Test
    void returnsAtMostTenPerGroup() throws Exception {
        for (int i = 0; i < 12; i++) {
            transaction(userId, accountId, "Uber corrida " + i, null, "20.00", LocalDate.now().minusDays(i));
        }

        search("uber").andExpect(jsonPath("$.transactions.length()").value(10));
    }

    @Test
    void shortQuery_is400() throws Exception {
        search("a").andExpect(status().isBadRequest());
        search(" a ").andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/search").header("Authorization", token)).andExpect(status().isBadRequest());
    }

    @Test
    void withoutToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "mercado"))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResultActions search(String q) throws Exception {
        return mockMvc.perform(get("/api/v1/search").param("q", q)
                .header("Authorization", token).accept(MediaType.APPLICATION_JSON));
    }

    private UUID account(UUID owner, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (user_id, name, type) VALUES (?, ?, 'CHECKING') RETURNING id",
                UUID.class, owner, name);
    }

    private UUID transaction(UUID owner, UUID account, String description, String counterparty, String amount,
                             LocalDate date) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO transactions (user_id, account_id, type, status, amount, description, counterparty_name, "
                        + "competence_date, payment_date, payment_method_id) VALUES (?, ?, 'EXPENSE', 'PAID', ?, ?, ?, ?, ?, "
                        + PIX + ") RETURNING id",
                UUID.class, owner, account, new BigDecimal(amount), description, counterparty, date, date);
    }

    private UUID creditCard(UUID owner, String name, String issuer, String last4) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO credit_cards (user_id, name, brand, issuer, credit_limit, closing_day, due_day, last4_digits) "
                        + "VALUES (?, ?, 'VISA', ?, 5000, 5, 15, ?) RETURNING id",
                UUID.class, owner, name, issuer, last4);
    }

    private UUID category(UUID owner, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO categories (user_id, name) VALUES (?, ?) RETURNING id", UUID.class, owner, name);
    }

    private UUID recurrence(UUID owner, UUID account, String description) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO recurrence_rules (user_id, account_id, type, frequency, amount, description, start_date, "
                        + "payment_method_id) VALUES (?, ?, 'EXPENSE', 'MONTHLY', 49.90, ?, ?, " + PIX + ") RETURNING id",
                UUID.class, owner, account, description, LocalDate.now());
    }

    private UUID installmentSeries(UUID owner, UUID account, String description) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO installment_series (user_id, account_id, type, description, total_amount, "
                        + "total_installments, first_payment_date, payment_method_id) VALUES (?, ?, 'EXPENSE', ?, 3000, 10, ?, "
                        + PIX + ") RETURNING id",
                UUID.class, owner, account, description, LocalDate.now());
    }

    private String bearer(UUID user) {
        return "Bearer " + jwtService.generateToken(user, List.of(), Instant.now());
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "search-" + UUID.randomUUID() + "@example.com");
    }
}
