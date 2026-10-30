package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.TransactionService;
import com.cashcontrol.api.service.marketdata.MarketDataBackfillRequested;
import com.cashcontrol.api.service.marketdata.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@RecordApplicationEvents
class InvestmentApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.now(MarketDataService.MARKET_ZONE);

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ApplicationEvents applicationEvents;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private UUID checkingId;
    private UUID investmentId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = "Bearer " + jwtService.generateToken(userId, List.of(), Instant.now());
        checkingId = createAccount("Conta corrente", AccountType.CHECKING);
        investmentId = createAccount("Investimentos", AccountType.INVESTMENT);
        transactionService.createTransaction(new CreateTransactionRequest(checkingId, TransactionType.INCOME,
                new BigDecimal("10000"), "Salário", TODAY.minusDays(400), TODAY.minusDays(400), null, null, null,
                null, null, TransactionStatus.PAID, null, null), userId);

        // CDI de 0,05% ao dia útil nos últimos 400 dias (~13,4% a.a.).
        jdbcTemplate.update("DELETE FROM market_index_rates");
        List<Object[]> rows = new ArrayList<>();
        for (LocalDate day = TODAY.minusDays(400); day.isBefore(TODAY); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                rows.add(new Object[]{day});
            }
        }
        jdbcTemplate.batchUpdate("INSERT INTO market_index_rates (market_index, rate_date, value) VALUES ('CDI', ?, 0.05)", rows);
    }

    @Test
    void create_fromAccount_movesTheMoneyAndValuesTheHolding() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"");

        assertThat(balance(checkingId)).isEqualByComparingTo("9000.00");
        assertThat(balance(investmentId)).isEqualByComparingTo("1000.00");
        assertThat(holding.get("fundingTransferGroupId")).isNull();
        JsonNode valuation = holding.get("valuation");
        assertThat(decimal(valuation, "gross")).isGreaterThan(new BigDecimal("1000"));
        // 200 dias corridos: IR de 20%.
        assertThat(decimal(valuation, "incomeTaxRate")).isEqualByComparingTo("20");
        // 110% da taxa diária rende um pouco mais que 110% do retorno do CDI, pela capitalização.
        assertThat(decimal(holding, "cdiPercent")).isBetween(new BigDecimal("110"), new BigDecimal("111"));
    }

    @Test
    void totalRedemption_toChecking_bringsTheInvestmentAccountBackToZero() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"");
        BigDecimal expectedNet = decimal(holding.get("valuation"), "net");

        JsonNode redeemed = json(perform(post("/api/v1/investments/holdings/" + holding.get("id").asText() + "/redemptions")
                .content("{\"date\":\"" + TODAY + "\",\"total\":true,\"toAccountId\":\"" + checkingId + "\"}"))
                .andExpect(status().isOk()));

        assertThat(redeemed.get("status").asText()).isEqualTo("REDEEMED");
        assertThat(redeemed.get("valuation").isNull()).isTrue();
        assertThat(balance(investmentId)).isEqualByComparingTo("0.00");
        assertThat(balance(checkingId)).isEqualByComparingTo(new BigDecimal("9000.00").add(expectedNet));
        String category = jdbcTemplate.queryForObject(
                "SELECT c.name FROM transactions t JOIN categories c ON c.id = t.subcategory_id "
                        + "WHERE t.account_id = ? AND t.type = 'INCOME'", String.class, investmentId);
        assertThat(category).isEqualTo("Rendimentos");
    }

    @Test
    void partialRedemption_keepsTheAccountBalanceEqualToTheRemainingPrincipal() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"");
        BigDecimal halfGross = decimal(holding.get("valuation"), "gross").divide(new BigDecimal("2"));

        JsonNode redeemed = json(perform(post("/api/v1/investments/holdings/" + holding.get("id").asText() + "/redemptions")
                .content("{\"date\":\"" + TODAY + "\",\"grossAmount\":" + halfGross.setScale(2, java.math.RoundingMode.DOWN)
                        + ",\"toAccountId\":\"" + checkingId + "\"}"))
                .andExpect(status().isOk()));

        assertThat(redeemed.get("status").asText()).isEqualTo("ACTIVE");
        BigDecimal remaining = decimal(redeemed, "remainingPrincipal");
        assertThat(remaining).isBetween(new BigDecimal("499.90"), new BigDecimal("500.10"));
        assertThat(balance(investmentId)).isEqualByComparingTo(remaining);
        assertThat(redeemed.get("redemptions")).hasSize(1);
    }

    @Test
    void redemption_linkedToAnImportedTransfer_usesItsAmountAsNet() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"");
        // O extrato já trouxe o resgate como transferência da conta de investimento para a corrente.
        UUID imported = accountService.transfer(account(investmentId), account(checkingId), new BigDecimal("1061.10"),
                TODAY, "Resgate CDB").getFirst().getTransferGroupId();

        perform(post("/api/v1/investments/holdings/" + holding.get("id").asText() + "/redemptions")
                .content("{\"date\":\"" + TODAY + "\",\"total\":true,\"transferGroupId\":\"" + imported + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redemptions[0].net").value(1061.10))
                .andExpect(jsonPath("$.redemptions[0].transferGroupId").value(imported.toString()));

        assertThat(balance(investmentId)).isEqualByComparingTo("0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE account_id = ? AND type = 'INCOME'",
                BigDecimal.class, investmentId)).isEqualByComparingTo("61.10");
    }

    @Test
    void importedApplication_isPendingUntilAHoldingLinksIt() throws Exception {
        UUID imported = accountService.transfer(account(checkingId), account(investmentId), new BigDecimal("1000"),
                TODAY.minusDays(10), "CDB Inter").getFirst().getTransferGroupId();
        // Fora da janela: saldo antigo, que entra como "já está na conta".
        accountService.transfer(account(checkingId), account(investmentId), new BigDecimal("300"),
                TODAY.minusDays(200), "CDB antigo");

        perform(get("/api/v1/investments/pending-transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].transferGroupId").value(imported.toString()))
                .andExpect(jsonPath("$[0].direction").value("APPLICATION"))
                .andExpect(jsonPath("$[0].amount").value(1000.00))
                .andExpect(jsonPath("$[0].accountId").value(investmentId.toString()))
                .andExpect(jsonPath("$[0].counterpartAccountName").value("Conta corrente"));

        createCdb(1000, TODAY.minusDays(10), "\"fundingTransferGroupId\":\"" + imported + "\"");

        perform(get("/api/v1/investments/pending-transfers"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void importedRedemption_isPendingUntilARedemptionLinksIt() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"");
        UUID imported = accountService.transfer(account(investmentId), account(checkingId), new BigDecimal("1061.10"),
                TODAY, "Resgate CDB").getFirst().getTransferGroupId();
        UUID betweenInvestments = createAccount("Corretora", AccountType.INVESTMENT);
        accountService.transfer(account(investmentId), account(betweenInvestments), new BigDecimal("1"), TODAY, "Entre contas");

        perform(get("/api/v1/investments/pending-transfers"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].direction").value("REDEMPTION"))
                .andExpect(jsonPath("$[0].amount").value(1061.10));

        perform(post("/api/v1/investments/holdings/" + holding.get("id").asText() + "/redemptions")
                .content("{\"date\":\"" + TODAY + "\",\"total\":true,\"transferGroupId\":\"" + imported + "\"}"))
                .andExpect(status().isOk());

        perform(get("/api/v1/investments/pending-transfers"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_withoutFunding_showsTheMissingCashAsNegativeUnallocated() throws Exception {
        createCdb(1000, TODAY.minusDays(30), null);

        perform(get("/api/v1/investments/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPrincipal").value(1000.00))
                .andExpect(jsonPath("$.accounts[0].accountId").value(investmentId.toString()))
                .andExpect(jsonPath("$.accounts[0].unallocated").value(-1000.00))
                .andExpect(jsonPath("$.byKind[0].key").value("CDB"))
                .andExpect(jsonPath("$.byMaturity[0].key").value("DAILY"));
    }

    @Test
    void redemptionPreview_returnsTheTaxSplitWithoutRecording() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(10), "\"fromAccountId\":\"" + checkingId + "\"");

        perform(get("/api/v1/investments/holdings/" + holding.get("id").asText() + "/redemption-preview")
                .param("date", TODAY.toString()).param("total", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iofRate").value(66))
                .andExpect(jsonPath("$.incomeTaxRate").value(22.5));

        assertThat(balance(investmentId)).isEqualByComparingTo("1000.00");
    }

    @Test
    void evolution_startsAtThePrincipal_andTracksTheCdiBenchmark() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(100), "\"fromAccountId\":\"" + checkingId + "\"");

        JsonNode points = json(perform(get("/api/v1/investments/holdings/" + holding.get("id").asText() + "/evolution"))
                .andExpect(status().isOk())).get("points");

        assertThat(points.size()).isGreaterThanOrEqualTo(4);
        assertThat(decimal(points.get(0), "gross")).isEqualByComparingTo("1000.00");
        JsonNode last = points.get(points.size() - 1);
        assertThat(last.get("date").asText()).isEqualTo(TODAY.toString());
        assertThat(decimal(last, "gross")).isGreaterThan(decimal(last, "cdiBenchmark"));
    }

    @Test
    void deletingAfterARedemption_isRefused() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(60), "\"fromAccountId\":\"" + checkingId + "\"");
        String id = holding.get("id").asText();
        perform(post("/api/v1/investments/holdings/" + id + "/redemptions")
                .content("{\"date\":\"" + TODAY + "\",\"grossAmount\":100}"))
                .andExpect(status().isOk());

        perform(delete("/api/v1/investments/holdings/" + id)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void create_validatesKindAndAccount() throws Exception {
        perform(post("/api/v1/investments/holdings").content(body(investmentId, "\"kind\":\"CDB\"", 1000, TODAY.minusDays(5), null)))
                .andExpect(status().isUnprocessableEntity());
        perform(post("/api/v1/investments/holdings").content(cdbBody(checkingId, 1000, TODAY.minusDays(5), null)))
                .andExpect(status().isUnprocessableEntity());
        perform(post("/api/v1/investments/holdings").content(cdbBody(investmentId, 1000, TODAY.plusDays(1), null)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anotherUsersHolding_isNotFound() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(5), null);
        UUID otherUser = createUser();
        String otherToken = "Bearer " + jwtService.generateToken(otherUser, List.of(), Instant.now());

        mockMvc.perform(get("/api/v1/investments/holdings/" + holding.get("id").asText()).header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/investments/holdings").header("Authorization", otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void holdingOlderThanTheLoadedHistory_requestsABackfill() throws Exception {
        createCdb(1000, TODAY.minusDays(500), null);

        assertThat(applicationEvents.stream(MarketDataBackfillRequested.class))
                .extracting(MarketDataBackfillRequested::from)
                .containsExactly(TODAY.minusDays(500));
    }

    @Test
    void dashboardOverview_valuesHoldingsNetOfTaxes_andComparesWithTheSameCriterion() throws Exception {
        JsonNode valuation = createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"").get("valuation");
        BigDecimal net = decimal(valuation, "net");

        JsonNode overview = json(perform(get("/api/v1/dashboard/overview")).andExpect(status().isOk()));

        // Contas a custo somam os 10.000 do salário; a aplicação acrescenta o que renderia líquido.
        assertThat(decimal(overview, "netWorth")).isEqualByComparingTo(new BigDecimal("9000").add(net));
        assertThat(decimal(overview, "totalBalance")).isEqualByComparingTo("9000");
        assertThat(decimal(overview, "investmentsGross")).isEqualByComparingTo(decimal(valuation, "gross"));
        assertThat(decimal(overview, "investmentsNet")).isEqualByComparingTo(net);
        assertThat(decimal(overview, "investmentsYieldMonth"))
                .isBetween(BigDecimal.ZERO, decimal(valuation, "yield"));
        assertThat(decimal(overview, "openCardDebt")).isEqualByComparingTo("0");
        assertThat(decimal(overview, "previousNetWorth"))
                .isGreaterThan(new BigDecimal("10000"))
                .isLessThanOrEqualTo(decimal(overview, "netWorth"));
    }

    @Test
    void dashboardOverview_afterTotalRedemption_doesNotCountTheYieldTwice() throws Exception {
        JsonNode holding = createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"");
        BigDecimal before = decimal(json(perform(get("/api/v1/dashboard/overview"))), "netWorth");

        perform(post("/api/v1/investments/holdings/" + holding.get("id").asText() + "/redemptions")
                .content("{\"date\":\"" + TODAY + "\",\"total\":true,\"toAccountId\":\"" + checkingId + "\"}"))
                .andExpect(status().isOk());
        JsonNode after = json(perform(get("/api/v1/dashboard/overview")));

        assertThat(decimal(after, "netWorth")).isEqualByComparingTo(before);
        assertThat(decimal(after, "investmentsGross")).isEqualByComparingTo("0");
    }

    @Test
    void netWorthEvolution_addsTheYieldFromTheApplicationOn() throws Exception {
        createCdb(1000, TODAY.minusDays(200), "\"fromAccountId\":\"" + checkingId + "\"");
        BigDecimal netWorth = decimal(json(perform(get("/api/v1/dashboard/overview"))), "netWorth");

        JsonNode snapshots = json(perform(get("/api/v1/dashboard/charts/net-worth")
                .param("from", TODAY.minusDays(300).toString())
                .param("to", TODAY.toString()))
                .andExpect(status().isOk())).get("snapshots");

        assertThat(decimal(snapshots.get(0), "netWorth")).isEqualByComparingTo("10000");
        assertThat(decimal(snapshots.get(snapshots.size() - 1), "netWorth")).isEqualByComparingTo(netWorth);
        for (int i = 1; i < snapshots.size(); i++) {
            assertThat(decimal(snapshots.get(i), "netWorth")).isGreaterThanOrEqualTo(decimal(snapshots.get(i - 1), "netWorth"));
        }
    }

    private JsonNode createCdb(int principal, LocalDate appliedAt, String funding) throws Exception {
        return json(perform(post("/api/v1/investments/holdings").content(cdbBody(investmentId, principal, appliedAt, funding)))
                .andExpect(status().isCreated()));
    }

    private static String cdbBody(UUID accountId, int principal, LocalDate appliedAt, String funding) {
        return body(accountId, "\"kind\":\"CDB\",\"indexer\":\"CDI_PERCENT\",\"rate\":110", principal, appliedAt, funding);
    }

    private static String body(UUID accountId, String kindFields, int principal, LocalDate appliedAt, String funding) {
        return "{\"accountId\":\"" + accountId + "\"," + kindFields + ",\"name\":\"CDB Teste\","
                + "\"appliedAt\":\"" + appliedAt + "\",\"principal\":" + principal
                + (funding == null ? "" : "," + funding) + "}";
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private BigDecimal balance(UUID accountId) {
        return accountService.computeBalance(accountId, userId);
    }

    private com.cashcontrol.api.domain.entity.Account account(UUID id) {
        return accountRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId).orElseThrow();
    }

    private UUID createAccount(String name, AccountType type) {
        return accountService.createAccount(new CreateAccountRequest(name, type, "BRL", null, 0, null), userId).id();
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "invest-" + UUID.randomUUID() + "@example.com");
    }
}
