package com.cashcontrol.api;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.ManualAdjustmentRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.CreditData;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyAccount;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyItem;
import com.cashcontrol.api.service.push.WebPushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Saldo e limite lidos do banco (V46) e a conciliação de conta e de cartão. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret"})
class BankReconciliationIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ADJUSTMENT = "Ajuste para conferir com o banco";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @MockitoBean private PluggyClient pluggyClient;
    @MockitoBean private WebPushSender webPushSender;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private String itemId;
    private String bankExternalId;
    private String cardExternalId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = bearer(userId);
        itemId = UUID.randomUUID().toString();
        bankExternalId = "ext-bank-" + itemId;
        cardExternalId = "ext-card-" + itemId;
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(new PluggyItem(itemId, "UPDATED", null,
                userId.toString(), Instant.parse("2026-11-03T10:00:00Z"), new PluggyItem.Connector("Inter"), null)));
        bankReports(new BigDecimal("1302.39"));
    }

    @Test
    void sync_savesBalanceAndLimitOfEachAccount() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        register();

        Map<String, Object> bank = bankRow(bankExternalId);
        assertThat((BigDecimal) bank.get("bank_balance")).isEqualByComparingTo("1302.39");
        assertThat(((Timestamp) bank.get("bank_balance_at")).toInstant()).isAfterOrEqualTo(before.minusMillis(1));
        assertThat(bank.get("credit_limit")).isNull();
        assertThat(bank.get("credit_available")).isNull();

        Map<String, Object> card = bankRow(cardExternalId);
        assertThat((BigDecimal) card.get("bank_balance")).isEqualByComparingTo("1200.00");
        assertThat((BigDecimal) card.get("credit_limit")).isEqualByComparingTo("10000.00");
        assertThat((BigDecimal) card.get("credit_available")).isEqualByComparingTo("8800.00");

        // A leitura seguinte sobrescreve.
        bankReports(new BigDecimal("1500.00"));
        perform(post("/api/v1/bank-connections/" + connectionId() + "/refresh"), token).andExpect(status().isOk());
        assertThat((BigDecimal) bankRow(bankExternalId).get("bank_balance")).isEqualByComparingTo("1500.00");
    }

    @Test
    void accountReconciliation_computesTheDifference() throws Exception {
        UUID accountId = linkedAccount(new BigDecimal("1321.64"));

        JsonNode body = perform(get("/api/v1/accounts/" + accountId + "/reconciliation"), token)
                .andExpect(status().isOk()).andReturnJson();

        assertThat(decimal(body, "appBalance")).isEqualByComparingTo("1321.64");
        assertThat(decimal(body, "bankBalance")).isEqualByComparingTo("1302.39");
        assertThat(decimal(body, "difference")).isEqualByComparingTo("-19.25");
        assertThat(body.get("bankBalanceAt").isNull()).isFalse();
    }

    @Test
    void reconcile_createsOneAdjustment_andTheSecondReconcileUpdatesTheSameOne() throws Exception {
        UUID accountId = linkedAccount(new BigDecimal("1321.64"));

        JsonNode first = perform(post("/api/v1/accounts/" + accountId + "/reconcile"), token)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(decimal(first, "appBalance")).isEqualByComparingTo("1302.39");
        assertThat(decimal(first, "difference")).isEqualByComparingTo("0");
        assertThat(adjustments(accountId)).isEqualTo(1);
        UUID adjustmentId = syncAdjustmentId(accountId);
        assertThat(adjustmentAmount(adjustmentId)).isEqualByComparingTo("-19.25");

        bankReports(new BigDecimal("1400.00"));
        perform(post("/api/v1/bank-connections/" + connectionId() + "/refresh"), token).andExpect(status().isOk());
        JsonNode second = perform(post("/api/v1/accounts/" + accountId + "/reconcile"), token)
                .andExpect(status().isOk()).andReturnJson();

        assertThat(decimal(second, "appBalance")).isEqualByComparingTo("1400.00");
        assertThat(adjustments(accountId)).isEqualTo(1);
        assertThat(syncAdjustmentId(accountId)).isEqualTo(adjustmentId);
        assertThat(adjustmentAmount(adjustmentId)).isEqualByComparingTo("78.36");
    }

    @Test
    void reconcile_comparesWithTheAppBalanceOnTheDayTheBankWasRead_notWithEntriesMadeAfter() throws Exception {
        UUID accountId = linkedAccount(new BigDecimal("1321.64"));
        LocalDate today = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
        jdbcTemplate.update("UPDATE transactions SET competence_date = ?, payment_date = ? WHERE account_id = ?",
                today.minusDays(2), today.minusDays(2), accountId);
        jdbcTemplate.update("UPDATE bank_connection_accounts SET bank_balance_at = ? WHERE external_account_id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), bankExternalId);
        // Despesa lançada hoje, depois da leitura do banco: o banco ainda não a conhece.
        accountService.manualAdjustment(accountId, new ManualAdjustmentRequest(new BigDecimal("-200.00"), "Mercado", today), userId);

        JsonNode before = perform(get("/api/v1/accounts/" + accountId + "/reconciliation"), token)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(decimal(before, "difference")).isEqualByComparingTo("-19.25");

        JsonNode after = perform(post("/api/v1/accounts/" + accountId + "/reconcile"), token)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(adjustmentAmount(syncAdjustmentId(accountId))).isEqualByComparingTo("-19.25");
        assertThat(jdbcTemplate.queryForObject("SELECT payment_date FROM transactions WHERE id = ?", LocalDate.class,
                syncAdjustmentId(accountId))).isEqualTo(today.minusDays(1));
        assertThat(decimal(after, "appBalance")).isEqualByComparingTo("1102.39");
        assertThat(decimal(after, "difference")).isEqualByComparingTo("0");
    }

    @Test
    void unlinkedAccount_hasNoBankBalance_andCannotBeReconciled() throws Exception {
        UUID accountId = accountService.createAccount(new CreateAccountRequest("Carteira", AccountType.CHECKING, "BRL",
                null, 0, new BigDecimal("50.00")), userId).id();

        JsonNode body = perform(get("/api/v1/accounts/" + accountId + "/reconciliation"), token)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(decimal(body, "appBalance")).isEqualByComparingTo("50.00");
        assertThat(body.get("bankBalance").isNull()).isTrue();
        assertThat(body.get("bankBalanceAt").isNull()).isTrue();
        assertThat(body.get("difference").isNull()).isTrue();

        perform(post("/api/v1/accounts/" + accountId + "/reconcile"), token).andExpect(status().isUnprocessableEntity());
        assertThat(adjustments(accountId)).isZero();
    }

    @Test
    void card_reconciliationComparesLimits_andReconcileCreatesTheChargeInTheFeesCategory() throws Exception {
        UUID cardId = linkedCard();
        perform(post("/api/v1/cards/" + cardId + "/charges").content(
                "{\"description\":\"Mercado\",\"amount\":200.00,\"competenceDate\":\"" + java.time.LocalDate.now() + "\"}"), token)
                .andExpect(status().isCreated());

        JsonNode before = perform(get("/api/v1/cards/" + cardId + "/reconciliation"), token)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(decimal(before, "bankLimit")).isEqualByComparingTo("10000.00");
        assertThat(decimal(before, "bankAvailable")).isEqualByComparingTo("8800.00");
        assertThat(decimal(before, "bankUsed")).isEqualByComparingTo("1200.00");
        assertThat(decimal(before, "appUsed")).isEqualByComparingTo("200.00");
        assertThat(decimal(before, "difference")).isEqualByComparingTo("1000.00");
        String openInvoiceId = before.get("openInvoiceId").asText();

        perform(post("/api/v1/cards/" + cardId + "/reconcile").content(
                "{\"amount\":600.00,\"description\":\"IOF de compra internacional\"}"), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("IOF de compra internacional"));
        perform(post("/api/v1/cards/" + cardId + "/reconcile").content("{\"amount\":400.00}"), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(ADJUSTMENT));

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT i.invoice_id, c.name, c.user_id FROM invoice_items i JOIN categories c ON c.id = i.category_id "
                        + "WHERE i.user_id = ? AND i.cancelled_at IS NULL", userId);
        assertThat(items).hasSize(2).allSatisfy(item -> {
            assertThat(item.get("name")).isEqualTo("Tarifas e IOF");
            assertThat(item.get("user_id")).isEqualTo(userId);
            assertThat(item.get("invoice_id").toString()).isEqualTo(openInvoiceId);
        });
        // A categoria criada no primeiro ajuste é reaproveitada no segundo.
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories WHERE user_id = ? AND name = 'Tarifas e IOF'",
                Integer.class, userId)).isEqualTo(1);

        JsonNode after = perform(get("/api/v1/cards/" + cardId + "/reconciliation"), token)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(decimal(after, "appUsed")).isEqualByComparingTo("1200.00");
        assertThat(decimal(after, "difference")).isEqualByComparingTo("0");
    }

    @Test
    void card_reconcile_reusesTheUsersExistingFeesCategory() throws Exception {
        UUID cardId = linkedCard();
        UUID existing = jdbcTemplate.queryForObject(
                "INSERT INTO categories (user_id, name) VALUES (?, 'tarifas e iof') RETURNING id", UUID.class, userId);

        perform(post("/api/v1/cards/" + cardId + "/reconcile").content("{\"amount\":10.00}"), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(existing.toString()));
    }

    @Test
    void card_reconcile_skipsTheSystemFeesCategoryTheUserHid() throws Exception {
        UUID cardId = linkedCard();
        UUID system = jdbcTemplate.queryForList(
                "SELECT id FROM categories WHERE user_id IS NULL AND parent_id IS NULL AND LOWER(name) = 'tarifas e iof'",
                UUID.class).stream().findFirst().orElseGet(() -> jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES ('Tarifas e IOF') RETURNING id", UUID.class));
        jdbcTemplate.update("INSERT INTO hidden_system_categories (user_id, category_id) VALUES (?, ?)", userId, system);

        JsonNode charge = perform(post("/api/v1/cards/" + cardId + "/reconcile").content("{\"amount\":10.00}"), token)
                .andExpect(status().isCreated()).andReturnJson();

        UUID categoryId = UUID.fromString(charge.get("categoryId").asText());
        assertThat(categoryId).isNotEqualTo(system);
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM categories WHERE id = ?", UUID.class, categoryId))
                .isEqualTo(userId);
    }

    @Test
    void unlinkedCard_hasNoBankFields() throws Exception {
        UUID cardId = card();

        JsonNode body = perform(get("/api/v1/cards/" + cardId + "/reconciliation"), token)
                .andExpect(status().isOk()).andReturnJson();
        assertThat(body.get("bankLimit").isNull()).isTrue();
        assertThat(body.get("bankUsed").isNull()).isTrue();
        assertThat(body.get("difference").isNull()).isTrue();
        assertThat(decimal(body, "appUsed")).isEqualByComparingTo("0");
    }

    @Test
    void anotherUser_getsNotFoundOnEveryEndpoint() throws Exception {
        UUID accountId = linkedAccount(new BigDecimal("100.00"));
        UUID cardId = linkedCard();
        String intruder = bearer(createUser());

        perform(get("/api/v1/accounts/" + accountId + "/reconciliation"), intruder).andExpect(status().isNotFound());
        perform(post("/api/v1/accounts/" + accountId + "/reconcile"), intruder).andExpect(status().isNotFound());
        perform(get("/api/v1/cards/" + cardId + "/reconciliation"), intruder).andExpect(status().isNotFound());
        perform(post("/api/v1/cards/" + cardId + "/reconcile").content("{\"amount\":10.00}"), intruder)
                .andExpect(status().isNotFound());

        assertThat(adjustments(accountId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice_items WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    private void bankReports(BigDecimal balance) {
        when(pluggyClient.listAccounts(itemId)).thenReturn(List.of(
                new PluggyAccount(bankExternalId, "BANK", "CHECKING_ACCOUNT", "Conta Corrente", null, "1234567-8",
                        balance, null),
                new PluggyAccount(cardExternalId, "CREDIT", "CREDIT_CARD", "Mastercard", "Inter Black", "1234",
                        new BigDecimal("1200.00"), new CreditData(new BigDecimal("10000.00"), new BigDecimal("8800.00"),
                        Instant.parse("2026-11-10T03:00:00Z"), Instant.parse("2026-11-17T03:00:00Z")))));
    }

    private void register() throws Exception {
        perform(post("/api/v1/bank-connections").content("{\"itemId\":\"" + itemId + "\"}"), token)
                .andExpect(status().isCreated());
    }

    private UUID linkedAccount(BigDecimal initialBalance) throws Exception {
        register();
        UUID accountId = accountService.createAccount(new CreateAccountRequest("Inter " + UUID.randomUUID(),
                AccountType.CHECKING, "BRL", null, 0, initialBalance), userId).id();
        link(bankExternalId, "{\"accountId\":\"" + accountId + "\"}");
        return accountId;
    }

    private UUID linkedCard() throws Exception {
        if (bankRowCount() == 0) {
            register();
        }
        UUID cardId = card();
        link(cardExternalId, "{\"creditCardId\":\"" + cardId + "\"}");
        return cardId;
    }

    private UUID card() {
        return creditCardService.createCard(new CreateCardRequest("Inter " + UUID.randomUUID(), CardBrand.MASTERCARD,
                "Inter", null, new BigDecimal("10000.00"), 10, 17, null), userId).id();
    }

    private void link(String externalId, String body) throws Exception {
        UUID bankAccountId = (UUID) bankRow(externalId).get("id");
        perform(put("/api/v1/bank-connections/" + connectionId() + "/accounts/" + bankAccountId + "/link").content(body), token)
                .andExpect(status().isOk());
    }

    private UUID connectionId() {
        return jdbcTemplate.queryForObject("SELECT id FROM bank_connections WHERE item_id = ?", UUID.class, itemId);
    }

    private Map<String, Object> bankRow(String externalId) {
        return jdbcTemplate.queryForMap("SELECT * FROM bank_connection_accounts WHERE external_account_id = ?", externalId);
    }

    private int bankRowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_connection_accounts WHERE external_account_id = ?",
                Integer.class, bankExternalId);
    }

    private int adjustments(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ? "
                + "AND type = 'MANUAL_ADJUSTMENT' AND description = ?", Integer.class, accountId, ADJUSTMENT);
    }

    private UUID syncAdjustmentId(UUID accountId) {
        return jdbcTemplate.queryForObject("SELECT sync_adjustment_id FROM accounts WHERE id = ?", UUID.class, accountId);
    }

    private BigDecimal adjustmentAmount(UUID transactionId) {
        return jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, transactionId);
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        return new BigDecimal(body.get(field).asText());
    }

    private Result perform(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return new Result(mockMvc.perform(request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)));
    }

    private record Result(ResultActions actions) {
        Result andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode andReturnJson() throws Exception {
            return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
        }
    }

    private String bearer(UUID user) {
        return "Bearer " + jwtService.generateToken(user, List.of(), Instant.now());
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "recon-" + UUID.randomUUID() + "@example.com");
    }
}
