package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.exception.BankProviderException;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.openfinance.BankConnectionService;
import com.cashcontrol.api.service.openfinance.BankSyncRunService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyAccount;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyItem;
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

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Histórico de sincronizações (V44): o que cada leitura grava e quem pode ver. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret"})
class BankSyncRunApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant BANK_SYNC = Instant.parse("2026-10-30T10:00:00Z");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private BankConnectionService bankConnectionService;
    @Autowired private BankSyncRunService bankSyncRunService;
    @MockitoBean private PluggyClient pluggyClient;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private String admin;
    private String itemId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = "Bearer " + jwtService.generateToken(userId, List.of(), Instant.now());
        admin = "Bearer " + jwtService.generateToken(userId, List.of("bank-sync:view"), Instant.now());
        itemId = UUID.randomUUID().toString();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item("UPDATED", BANK_SYNC)));
        when(pluggyClient.listAccounts(itemId)).thenReturn(List.of(
                new PluggyAccount("ext-bank-" + itemId, "BANK", "CHECKING_ACCOUNT", "Conta Corrente", null, "1234567-8")));
    }

    @Test
    void register_andManualRefresh_recordRunsWithOutcomeByBankUpdate() throws Exception {
        String connectionId = register().get("id").asText();
        // Mesma data de atualização no banco: leitura sem novidade.
        perform(post("/api/v1/bank-connections/" + connectionId + "/refresh"), token).andExpect(status().isOk());
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item("UPDATED", BANK_SYNC.plus(1, ChronoUnit.DAYS))));
        perform(post("/api/v1/bank-connections/" + connectionId + "/refresh"), token).andExpect(status().isOk());

        JsonNode runs = json(perform(get("/api/v1/admin/bank-sync/runs"), admin).andExpect(status().isOk()));
        assertThat(runs.get("totalElements").asInt()).isEqualTo(3);
        JsonNode newest = runs.get("content").get(0);
        assertThat(newest.get("trigger").asText()).isEqualTo("MANUAL");
        assertThat(newest.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(newest.get("accountsRead").asInt()).isEqualTo(1);
        assertThat(newest.get("connectionId").asText()).isEqualTo(connectionId);
        assertThat(newest.get("connectorName").asText()).isEqualTo("Inter");
        assertThat(runs.get("content").get(1).get("outcome").asText()).isEqualTo("NO_CHANGE");
        assertThat(runs.get("content").get(2).get("trigger").asText()).isEqualTo("REGISTER");
    }

    @Test
    void failedRefresh_isRecordedEvenThoughItsTransactionRollsBack() throws Exception {
        String connectionId = register().get("id").asText();
        when(pluggyClient.getItem(itemId)).thenThrow(new BankProviderException("Pluggy fora do ar", new IOException("timeout")));

        perform(post("/api/v1/bank-connections/" + connectionId + "/refresh"), token).andExpect(status().isBadGateway());

        JsonNode runs = json(perform(get("/api/v1/admin/bank-sync/runs?outcome=ERROR"), admin));
        assertThat(runs.get("totalElements").asInt()).isEqualTo(1);
        assertThat(runs.get("content").get(0).get("errorMessage").asText()).contains("Pluggy fora do ar").contains("timeout");
    }

    @Test
    void itemWithLoginError_isAnError() throws Exception {
        UUID connectionId = UUID.fromString(register().get("id").asText());
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item("LOGIN_ERROR", BANK_SYNC)));

        // O que a varredura de hora em hora faz para cada conexão.
        bankConnectionService.poll(connectionId);

        JsonNode run = json(perform(get("/api/v1/admin/bank-sync/runs"), admin)).get("content").get(0);
        assertThat(run.get("trigger").asText()).isEqualTo("SCHEDULED");
        assertThat(run.get("outcome").asText()).isEqualTo("ERROR");
        assertThat(run.get("itemStatus").asText()).isEqualTo("LOGIN_ERROR");
    }

    @Test
    void poll_itemGoneAtPluggy_recordsItAndKeepsTheHistory() throws Exception {
        String connectionId = register().get("id").asText();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.empty());

        bankConnectionService.poll(UUID.fromString(connectionId));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_connections WHERE item_id = ?", Integer.class, itemId))
                .isZero();
        JsonNode run = json(perform(get("/api/v1/admin/bank-sync/runs"), admin)).get("content").get(0);
        assertThat(run.get("outcome").asText()).isEqualTo("ITEM_GONE");
        assertThat(run.get("connectionId").asText()).isEqualTo(connectionId);
    }

    @Test
    void overview_summarisesTheUsersConnections() throws Exception {
        String connectionId = register().get("id").asText();
        when(pluggyClient.getItem(itemId)).thenThrow(new BankProviderException("Pluggy fora do ar", null));
        perform(post("/api/v1/bank-connections/" + connectionId + "/refresh"), token);

        JsonNode overview = json(perform(get("/api/v1/admin/bank-sync/overview"), admin).andExpect(status().isOk()));

        assertThat(overview.get("retentionDays").asInt()).isEqualTo(30);
        JsonNode connection = overview.get("connections").get(0);
        assertThat(connection.get("connectionId").asText()).isEqualTo(connectionId);
        assertThat(connection.get("accounts").get(0).get("number").asText()).isEqualTo("1234567-8");
        assertThat(connection.get("runsLast24h").asInt()).isEqualTo(2);
        assertThat(connection.get("problemsLast24h").asInt()).isEqualTo(1);
        assertThat(connection.get("lastRunOutcome").asText()).isEqualTo("ERROR");
        assertThat(connection.get("lastProblemMessage").asText()).isEqualTo("Pluggy fora do ar");
        assertThat(connection.get("lastSuccessAt").isNull()).isFalse();
    }

    @Test
    void log_needsThePermission_andShowsOnlyTheUsersOwnRuns() throws Exception {
        register();
        UUID other = createUser();
        String otherAdmin = "Bearer " + jwtService.generateToken(other, List.of("bank-sync:view"), Instant.now());

        perform(get("/api/v1/admin/bank-sync/runs"), token).andExpect(status().isForbidden());
        perform(get("/api/v1/admin/bank-sync/overview"), token).andExpect(status().isForbidden());
        assertThat(json(perform(get("/api/v1/admin/bank-sync/runs"), otherAdmin)).get("totalElements").asInt()).isZero();
        assertThat(json(perform(get("/api/v1/admin/bank-sync/overview"), otherAdmin)).get("connections")).isEmpty();
    }

    @Test
    void purge_deletesRunsOlderThanTheRetention() throws Exception {
        register();
        jdbcTemplate.update("UPDATE bank_sync_runs SET started_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)), userId);
        register();

        bankSyncRunService.purge(Instant.now());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_sync_runs WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    private JsonNode register() throws Exception {
        return json(perform(post("/api/v1/bank-connections").content("{\"itemId\":\"" + itemId + "\"}"), token)
                .andExpect(status().isCreated()));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON));
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private PluggyItem item(String status, Instant lastUpdatedAt) {
        return new PluggyItem(itemId, status, null, userId.toString(), lastUpdatedAt, new PluggyItem.Connector("Inter"), null);
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "sync-" + UUID.randomUUID() + "@example.com");
    }
}
