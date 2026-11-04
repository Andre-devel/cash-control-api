package com.cashcontrol.api;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.exception.BankProviderException;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.openfinance.BankConnectionService;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Conexão duplicada no {@code GET /bank-connections} e item órfão da Pluggy (V50). */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret"})
class BankOrphanItemIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private BankConnectionService bankConnectionService;
    @MockitoBean private PluggyClient pluggyClient;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private String admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = "Bearer " + jwtService.generateToken(userId, List.of(), Instant.now());
        admin = "Bearer " + jwtService.generateToken(userId, List.of("bank-sync:view"), Instant.now());
    }

    @Test
    void twoConnectionsWithTheSameBankAccount_markTheSecondAsDuplicate() throws Exception {
        String first = stubItem(userId, "1234567-8");
        String second = stubItem(userId, "1234567-8");
        String unrelated = stubItem(userId, "7654321-0");

        String firstId = register(first).get("id").asText();
        JsonNode secondResponse = register(second);
        String unrelatedId = register(unrelated).get("id").asText();
        assertThat(secondResponse.get("duplicateOf").asText()).isEqualTo(firstId);

        JsonNode connections = json(perform(get("/api/v1/bank-connections"), token).andExpect(status().isOk()))
                .get("connections");
        assertThat(connections).hasSize(3);
        for (JsonNode connection : connections) {
            String id = connection.get("id").asText();
            if (id.equals(firstId) || id.equals(unrelatedId)) {
                assertThat(connection.get("duplicateOf").isNull()).isTrue();
            } else {
                assertThat(connection.get("duplicateOf").asText()).isEqualTo(firstId);
            }
        }
    }

    @Test
    void webhook_itemOfUnknownOwner_isNotRecorded() throws Exception {
        String itemId = UUID.randomUUID().toString();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item(itemId, UUID.randomUUID().toString())));

        bankConnectionService.onItemEvent(UUID.fromString(itemId), "item/created");

        eventually(() -> {
            try {
                verify(pluggyClient).getItem(itemId);
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });
        Thread.sleep(300);
        assertThat(orphanCount(itemId)).isZero();
        assertThat(connectionCount(itemId)).isZero();
    }

    @Test
    void webhook_itemOfOurUserThatFails_isRecorded_andLeavesTheTableOnceItBecomesAConnection() throws Exception {
        String itemId = UUID.randomUUID().toString();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item(itemId, userId.toString())));
        when(pluggyClient.listAccounts(itemId)).thenThrow(new BankProviderException("Pluggy fora do ar", new IOException("timeout")));

        bankConnectionService.onItemEvent(UUID.fromString(itemId), "item/created");

        eventually(() -> orphanCount(itemId) == 1);
        assertThat(jdbcTemplate.queryForObject("SELECT user_id FROM bank_orphan_items WHERE item_id = ?", UUID.class, itemId))
                .isEqualTo(userId);
        // A leitura que falhou não deixa conexão pela metade.
        assertThat(connectionCount(itemId)).isZero();
        JsonNode orphans = json(perform(get("/api/v1/admin/bank-sync/orphans"), admin).andExpect(status().isOk()));
        assertThat(orphans).hasSize(1);
        assertThat(orphans.get(0).get("itemId").asText()).isEqualTo(itemId);
        assertThat(orphans.get(0).get("connectorName").asText()).isEqualTo("MeuPluggy");
        assertThat(orphans.get(0).get("firstSeenAt").isNull()).isFalse();

        doReturn(List.of(bankAccount(itemId, "1234567-8"))).when(pluggyClient).listAccounts(itemId);
        bankConnectionService.onItemEvent(UUID.fromString(itemId), "item/updated");

        eventually(() -> connectionCount(itemId) == 1 && orphanCount(itemId) == 0);
    }

    @Test
    void register_removesTheItemFromTheOrphans() throws Exception {
        String itemId = recordOrphan();
        doReturn(List.of(bankAccount(itemId, "1234567-8"))).when(pluggyClient).listAccounts(itemId);

        register(itemId);

        assertThat(orphanCount(itemId)).isZero();
    }

    @Test
    void delete_deletesAtPluggyAndForgetsTheItem() throws Exception {
        String itemId = recordOrphan();

        perform(delete("/api/v1/admin/bank-sync/orphans/" + itemId), admin).andExpect(status().isNoContent());

        verify(pluggyClient).deleteItem(itemId);
        assertThat(orphanCount(itemId)).isZero();
        assertThat(json(perform(get("/api/v1/admin/bank-sync/orphans"), admin))).isEmpty();
    }

    @Test
    void delete_itemPluggyNoLongerKnows_onlyForgetsIt() throws Exception {
        String itemId = recordOrphan();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.empty());

        perform(delete("/api/v1/admin/bank-sync/orphans/" + itemId), admin).andExpect(status().isNoContent());

        verify(pluggyClient, never()).deleteItem(anyString());
        assertThat(orphanCount(itemId)).isZero();
    }

    @Test
    void withoutThePermission_isForbidden() throws Exception {
        String itemId = recordOrphan();

        perform(get("/api/v1/admin/bank-sync/orphans"), token).andExpect(status().isForbidden());
        perform(delete("/api/v1/admin/bank-sync/orphans/" + itemId), token).andExpect(status().isForbidden());

        verify(pluggyClient, never()).deleteItem(anyString());
        assertThat(orphanCount(itemId)).isEqualTo(1);
    }

    @Test
    void itemOfAnotherUser_isNotFound() throws Exception {
        String itemId = recordOrphan();
        UUID other = createUser();
        String otherAdmin = "Bearer " + jwtService.generateToken(other, List.of("bank-sync:view"), Instant.now());

        assertThat(json(perform(get("/api/v1/admin/bank-sync/orphans"), otherAdmin).andExpect(status().isOk()))).isEmpty();
        perform(delete("/api/v1/admin/bank-sync/orphans/" + itemId), otherAdmin).andExpect(status().isNotFound());

        verify(pluggyClient, never()).deleteItem(anyString());
        assertThat(orphanCount(itemId)).isEqualTo(1);
    }

    /** Item do usuário cujo cadastro pelo webhook falha: fica registrado como órfão. */
    private String recordOrphan() throws Exception {
        String itemId = UUID.randomUUID().toString();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item(itemId, userId.toString())));
        when(pluggyClient.listAccounts(itemId)).thenThrow(new BankProviderException("Pluggy fora do ar", null));
        bankConnectionService.onItemEvent(UUID.fromString(itemId), "item/created");
        eventually(() -> orphanCount(itemId) == 1);
        return itemId;
    }

    private String stubItem(UUID owner, String bankNumber) {
        String itemId = UUID.randomUUID().toString();
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(item(itemId, owner.toString())));
        when(pluggyClient.listAccounts(itemId)).thenReturn(List.of(bankAccount(itemId, bankNumber)));
        return itemId;
    }

    private JsonNode register(String itemId) throws Exception {
        return json(perform(post("/api/v1/bank-connections").content("{\"itemId\":\"" + itemId + "\"}"), token)
                .andExpect(status().isCreated()));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON));
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static PluggyItem item(String itemId, String clientUserId) {
        return new PluggyItem(itemId, "UPDATED", null, clientUserId, Instant.parse("2026-11-04T10:00:00Z"),
                new PluggyItem.Connector("MeuPluggy"), null);
    }

    private static PluggyAccount bankAccount(String itemId, String number) {
        return new PluggyAccount("ext-bank-" + itemId, "BANK", "CHECKING_ACCOUNT", "Conta Corrente", null, number);
    }

    private int orphanCount(String itemId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_orphan_items WHERE item_id = ?", Integer.class, itemId);
    }

    private int connectionCount(String itemId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_connections WHERE item_id = ?", Integer.class, itemId);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private UUID createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "orphan-" + UUID.randomUUID() + "@example.com");
    }
}
