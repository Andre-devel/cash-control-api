package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret"})
class BankConnectionApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @MockitoBean private PluggyClient pluggyClient;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private String itemId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = bearer(userId);
        itemId = UUID.randomUUID().toString();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item(itemId, userId.toString(), "UPDATED")));
        when(pluggyClient.listAccounts(itemId)).thenReturn(List.of(
                new PluggyAccount("ext-bank-" + itemId, "BANK", "CHECKING_ACCOUNT", "Conta Corrente", null, "1234567-8"),
                new PluggyAccount("ext-card-" + itemId, "CREDIT", "CREDIT_CARD", "Mastercard", "Inter Black", "1234")));
    }

    @Test
    void connectToken_isIssuedForTheUser() throws Exception {
        when(pluggyClient.createConnectToken(userId.toString(), null)).thenReturn("tok");

        perform(post("/api/v1/bank-connections/connect-token"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("tok"));
    }

    @Test
    void register_savesConnectionAndAccounts_andListShowsThem() throws Exception {
        JsonNode connection = register(token).andExpect(status().isCreated()).andReturnJson();

        assertThat(connection.get("itemId").asText()).isEqualTo(itemId);
        assertThat(connection.get("connectorName").asText()).isEqualTo("Inter");
        assertThat(connection.get("status").asText()).isEqualTo("UPDATED");
        JsonNode accounts = connection.get("accounts");
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0).get("kind").asText()).isEqualTo("BANK");
        assertThat(accounts.get(1).get("name").asText()).isEqualTo("Inter Black");

        perform(get("/api/v1/bank-connections"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.connections.length()").value(1));
    }

    @Test
    void register_itemOfAnotherPluggyUser_isNotFound() throws Exception {
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item(itemId, UUID.randomUUID().toString(), "UPDATED")));

        register(token).andExpect(status().isNotFound());
        assertThat(count()).isZero();
    }

    @Test
    void register_twice_updatesInsteadOfDuplicating() throws Exception {
        register(token).andExpect(status().isCreated());
        when(pluggyClient.listAccounts(itemId)).thenReturn(List.of(
                new PluggyAccount("ext-bank-" + itemId, "BANK", "CHECKING_ACCOUNT", "Conta Corrente", null, "1234567-8")));

        JsonNode connection = register(token).andExpect(status().isCreated()).andReturnJson();

        assertThat(count()).isEqualTo(1);
        assertThat(connection.get("accounts")).hasSize(1);
    }

    @Test
    void link_bankAccountToAccount_andRefusesCardTargetAndSecondLink() throws Exception {
        JsonNode connection = register(token).andReturnJson();
        String connectionId = connection.get("id").asText();
        String bankAccountId = connection.get("accounts").get(0).get("id").asText();
        String cardAccountId = connection.get("accounts").get(1).get("id").asText();
        UUID checking = accountService.createAccount(new CreateAccountRequest("Inter", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        String linkUrl = "/api/v1/bank-connections/" + connectionId + "/accounts/";

        perform(put(linkUrl + bankAccountId + "/link").content("{\"accountId\":\"" + checking + "\"}"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].accountId").value(checking.toString()));
        // Cartão do banco não recebe conta.
        perform(put(linkUrl + cardAccountId + "/link").content("{\"accountId\":\"" + checking + "\"}"), token)
                .andExpect(status().isUnprocessableEntity());

        // A mesma conta do app não recebe de duas contas do banco.
        String otherItem = UUID.randomUUID().toString();
        when(pluggyClient.getItem(otherItem)).thenReturn(Optional.of(item(otherItem, userId.toString(), "UPDATED")));
        when(pluggyClient.listAccounts(otherItem)).thenReturn(List.of(
                new PluggyAccount("ext-other-" + otherItem, "BANK", "CHECKING_ACCOUNT", "Outra", null, null)));
        JsonNode other = perform(post("/api/v1/bank-connections").content("{\"itemId\":\"" + otherItem + "\"}"), token).andReturnJson();
        perform(put("/api/v1/bank-connections/" + other.get("id").asText() + "/accounts/"
                + other.get("accounts").get(0).get("id").asText() + "/link").content("{\"accountId\":\"" + checking + "\"}"), token)
                .andExpect(status().isConflict());

        // Desvincular.
        perform(put(linkUrl + bankAccountId + "/link").content("{}"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].accountId").isEmpty());
    }

    @Test
    void anotherUser_cannotSeeLinkOrDeleteTheConnection() throws Exception {
        JsonNode connection = register(token).andReturnJson();
        String connectionId = connection.get("id").asText();
        String bankAccountId = connection.get("accounts").get(0).get("id").asText();
        String intruder = bearer(createUser());

        perform(get("/api/v1/bank-connections"), intruder).andExpect(jsonPath("$.connections.length()").value(0));
        perform(put("/api/v1/bank-connections/" + connectionId + "/accounts/" + bankAccountId + "/link").content("{}"), intruder)
                .andExpect(status().isNotFound());
        perform(post("/api/v1/bank-connections/" + connectionId + "/connect-token"), intruder).andExpect(status().isNotFound());
        perform(delete("/api/v1/bank-connections/" + connectionId), intruder).andExpect(status().isNotFound());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void delete_revokesAtPluggyAndRemovesTheConnection() throws Exception {
        String connectionId = register(token).andReturnJson().get("id").asText();

        perform(delete("/api/v1/bank-connections/" + connectionId), token).andExpect(status().isNoContent());

        verify(pluggyClient).deleteItem(itemId);
        assertThat(count()).isZero();
    }

    @Test
    void webhook_isPublic_andRereadsTheItemFromPluggy() throws Exception {
        register(token);
        // O corpo diz "deleted", mas quem decide é a Pluggy: o item continua lá, agora com erro.
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item(itemId, userId.toString(), "LOGIN_ERROR")));

        mockMvc.perform(post("/api/v1/webhooks/pluggy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"item/deleted\",\"eventId\":\"e1\",\"itemId\":\"" + itemId + "\",\"triggeredBy\":\"SYNC\"}"))
                .andExpect(status().isNoContent());

        eventually(() -> "LOGIN_ERROR".equals(jdbcTemplate.queryForObject(
                "SELECT status FROM bank_connections WHERE item_id = ?", String.class, itemId)));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void webhook_itemGoneAtPluggy_deletesTheConnection() throws Exception {
        register(token);
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/webhooks/pluggy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"item/updated\",\"itemId\":\"" + itemId + "\"}"))
                .andExpect(status().isNoContent());

        eventually(() -> count() == 0);
    }

    @Test
    void webhook_unknownItem_isIgnored() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/pluggy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"item/created\",\"itemId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/webhooks/pluggy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"transactions/created\",\"accountId\":\"x\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void reconnectToken_carriesTheItem() throws Exception {
        String connectionId = register(token).andReturnJson().get("id").asText();
        when(pluggyClient.createConnectToken(eq(userId.toString()), eq(itemId))).thenReturn("tok-re");

        perform(post("/api/v1/bank-connections/" + connectionId + "/connect-token"), token)
                .andExpect(jsonPath("$.accessToken").value("tok-re"));
        verify(pluggyClient, org.mockito.Mockito.never()).createConnectToken(any(), isNull());
    }

    private Result register(String bearer) throws Exception {
        return perform(post("/api/v1/bank-connections").content("{\"itemId\":\"" + itemId + "\"}"), bearer);
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

    private static PluggyItem item(String id, String clientUserId, String status) {
        return new PluggyItem(id, status, null, clientUserId, Instant.parse("2026-10-30T10:00:00Z"),
                new PluggyItem.Connector("Inter"), null);
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_connections WHERE item_id = ?", Integer.class, itemId);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private String bearer(UUID user) {
        return "Bearer " + jwtService.generateToken(user, List.of(), Instant.now());
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "bank-" + UUID.randomUUID() + "@example.com");
    }
}
