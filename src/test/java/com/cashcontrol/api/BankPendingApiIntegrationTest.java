package com.cashcontrol.api;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.PushSubscription;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.security.JwtService;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyAccount;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyItem;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
import com.cashcontrol.api.service.push.WebPushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
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
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret",
        "app.push.vapid-public-key=test-public", "app.push.vapid-private-key=test-private"})
class BankPendingApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant EVENT_FROM = Instant.parse("2026-10-30T12:00:00Z");

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private AccountService accountService;
    @MockitoBean private PluggyClient pluggyClient;
    @MockitoBean private WebPushSender webPushSender;

    private MockMvc mockMvc;
    private UUID userId;
    private String token;
    private String itemId;
    private String externalAccountId;
    private UUID accountId;
    private final List<PluggyTransaction> bankTransactions = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        userId = createUser();
        token = bearer(userId);
        itemId = UUID.randomUUID().toString();
        externalAccountId = "ext-bank-" + itemId;
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(new PluggyItem(itemId, "UPDATED", null,
                userId.toString(), Instant.parse("2026-10-30T10:00:00Z"), new PluggyItem.Connector("Inter"), null)));
        when(pluggyClient.listAccounts(itemId)).thenReturn(List.of(
                new PluggyAccount(externalAccountId, "BANK", "CHECKING_ACCOUNT", "Conta Corrente", null, "1234567-8")));
        when(pluggyClient.listTransactions(externalAccountId)).thenAnswer(invocation -> List.copyOf(bankTransactions));
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushSender.Result.DELIVERED);

        JsonNode connection = json(perform(post("/api/v1/bank-connections").content("{\"itemId\":\"" + itemId + "\"}"), token)
                .andExpect(status().isCreated()));
        accountId = accountService.createAccount(new CreateAccountRequest("Inter", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        perform(put("/api/v1/bank-connections/" + connection.get("id").asText() + "/accounts/"
                    + connection.get("accounts").get(0).get("id").asText() + "/link")
                .content("{\"accountId\":\"" + accountId + "\"}"), token)
                .andExpect(status().isOk());
        subscribe(token, "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID(), false);
    }

    @Test
    void webhook_queuesOnlyWhatArrivedSinceTheEvent_once_andNotifiesEachOne() throws Exception {
        bankTransaction("old", "Pix recebido - Fulano", "50", "CREDIT", EVENT_FROM.minusSeconds(3600));
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        bankTransaction("t2", "Pix recebido - Ciclano", "25.5", "CREDIT", EVENT_FROM.plusSeconds(120));

        transactionsCreated();
        eventually(() -> sentPayloads().size() == 2);
        List<String> payloads = sentPayloads();
        transactionsCreated();
        Thread.sleep(500);
        assertThat(pendingCount()).isEqualTo(2);
        assertThat(sentPayloads()).isEmpty();

        assertThat(payloads.getFirst()).contains("\"type\":\"pending\"").contains("\"token\":")
                .contains("Pix enviado · −R$ 10,00").contains("Padaria");

        JsonNode queue = json(perform(get("/api/v1/bank-pending"), token).andExpect(status().isOk()));
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).get("accountName").asText()).isEqualTo("Inter");
        assertThat(queue.get(0).get("row").get("externalRef").asText()).isEqualTo("plg:t1");
        assertThat(queue.get(0).get("row").get("type").asText()).isEqualTo("EXPENSE");
        perform(get("/api/v1/bank-pending/count"), token).andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void confirmByToken_savesOnce_onlyForItsOwnPendingTransaction() throws Exception {
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        bankTransaction("t2", "Pix enviado - Mercado", "-20", "DEBIT", EVENT_FROM.plusSeconds(60));
        transactionsCreated();
        eventually(() -> sentPayloads().size() == 2);
        JsonNode first = JSON.readTree(sentPayloads().get(0));
        JsonNode second = JSON.readTree(sentPayloads().get(1));
        String url = "/api/v1/bank-pending/" + first.get("pendingId").asText() + "/confirm-by-token";

        // Token de outra pendência, ou inventado, não confirma.
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + second.get("token").asText() + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"forged\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + first.get("token").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Padaria"));
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + first.get("token").asText() + "\"}"))
                .andExpect(status().isNotFound());

        assertThat(transactionCount("plg:t1")).isEqualTo(1);
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void confirmByToken_expired_isRefused() throws Exception {
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        transactionsCreated();
        eventually(() -> sentPayloads().size() == 1);
        JsonNode payload = JSON.readTree(sentPayloads().getFirst());
        jdbcTemplate.update("UPDATE bank_pending_transactions SET action_token_expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?",
                UUID.fromString(payload.get("pendingId").asText()));

        mockMvc.perform(post("/api/v1/bank-pending/" + payload.get("pendingId").asText() + "/confirm-by-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + payload.get("token").asText() + "\"}"))
                .andExpect(status().isNotFound());
        assertThat(transactionCount("plg:t1")).isZero();
    }

    @Test
    void confirm_withEdits_keepsDateAndAmountFromTheBank_andAnotherUserCannotTouchIt() throws Exception {
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        transactionsCreated();
        eventually(() -> pendingCount() == 1);
        String id = json(perform(get("/api/v1/bank-pending"), token)).get(0).get("id").asText();
        String other = bearer(createUser());

        perform(post("/api/v1/bank-pending/" + id + "/confirm"), other).andExpect(status().isNotFound());
        perform(delete("/api/v1/bank-pending/" + id), other).andExpect(status().isNotFound());

        perform(post("/api/v1/bank-pending/" + id + "/confirm").content("""
                {"lineNumber":9,"externalRef":"plg:forged","date":"2020-01-01","description":"Pão da manhã",
                 "amount":999,"type":"EXPENSE","paymentMethod":"PIX"}"""), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Pão da manhã"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM transactions WHERE user_id = ? AND external_ref = 'plg:t1'", BigDecimal.class, userId)).isEqualByComparingTo("10");
        assertThat(transactionCount("plg:forged")).isZero();
        assertThat(pendingCount()).isZero();
    }

    @Test
    void importedThroughTheDialog_leavesTheQueue() throws Exception {
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        transactionsCreated();
        eventually(() -> pendingCount() == 1);

        perform(post("/api/v1/transactions/import").content("""
                {"accountId":"%s","format":"OPEN_FINANCE","rows":[{"lineNumber":1,"externalRef":"plg:t1",
                 "date":"2026-10-30","description":"Padaria","amount":10,"type":"EXPENSE","paymentMethod":"PIX"}]}"""
                .formatted(accountId)), token).andExpect(status().isCreated());

        perform(get("/api/v1/bank-pending/count"), token).andExpect(jsonPath("$.count").value(0));
        assertThat(json(perform(get("/api/v1/bank-pending"), token))).isEmpty();
    }

    @Test
    void discard_keepsItOutEvenWhenAnnouncedAgain() throws Exception {
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        transactionsCreated();
        eventually(() -> pendingCount() == 1);
        String id = json(perform(get("/api/v1/bank-pending"), token)).get(0).get("id").asText();

        perform(delete("/api/v1/bank-pending/" + id), token).andExpect(status().isNoContent());
        transactionsCreated();
        Thread.sleep(500);

        assertThat(json(perform(get("/api/v1/bank-pending"), token))).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bank_pending_transactions WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void moreThanThree_sendsOneSummary_andConfirmAllSavesThemAsSuggested() throws Exception {
        for (int i = 1; i <= 4; i++) {
            bankTransaction("t" + i, "Pix enviado - Loja " + i, "-" + i, "DEBIT", EVENT_FROM.plusSeconds(60));
        }
        transactionsCreated();
        eventually(() -> sentPayloads().size() == 1);
        assertThat(sentPayloads().getFirst()).contains("\"type\":\"summary\"").contains("4 lançamentos para revisar")
                .doesNotContain("token");

        perform(post("/api/v1/bank-pending/confirm-all"), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(4))
                .andExpect(jsonPath("$.skipped").value(0));
        assertThat(pendingCount()).isZero();
        assertThat(transactionCount("plg:t4")).isEqualTo(1);
    }

    @Test
    void hideAmounts_leavesTheValueOutOfTheNotification() throws Exception {
        jdbcTemplate.update("UPDATE push_subscriptions SET hide_amounts = TRUE WHERE user_id = ?", userId);
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        transactionsCreated();
        eventually(() -> sentPayloads().size() == 1);

        assertThat(sentPayloads().getFirst()).contains("Pix enviado").doesNotContain("R$");
    }

    @Test
    void goneDevice_isDeleted() throws Exception {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushSender.Result.GONE);
        bankTransaction("t1", "Pix enviado - Padaria", "-10", "DEBIT", EVENT_FROM.plusSeconds(60));
        transactionsCreated();

        eventually(() -> jdbcTemplate.queryForObject("SELECT COUNT(*) FROM push_subscriptions WHERE user_id = ?",
                Integer.class, userId) == 0);
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void subscribe_onlyKnownPushServices_andUnsubscribeRemovesTheDevice() throws Exception {
        perform(post("/api/v1/push/subscriptions").content(subscriptionJson("http://169.254.169.254/latest", false)), token)
                .andExpect(status().isUnprocessableContent());
        perform(post("/api/v1/push/subscriptions").content(subscriptionJson("https://fcm.googleapis.com.evil.io/x", false)), token)
                .andExpect(status().isUnprocessableContent());

        String endpoint = "https://fcm.googleapis.com/fcm/send/device-2";
        subscribe(token, endpoint, true);
        perform(get("/api/v1/push/subscriptions"), token).andExpect(jsonPath("$.length()").value(2));
        perform(get("/api/v1/push/vapid-key"), token)
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publicKey").value("test-public"));

        perform(delete("/api/v1/push/subscriptions").content("{\"endpoint\":\"" + endpoint + "\"}"), token)
                .andExpect(status().isNoContent());
        perform(get("/api/v1/push/subscriptions"), token).andExpect(jsonPath("$.length()").value(1));
    }

    private void bankTransaction(String id, String description, String amount, String type, Instant createdAt) {
        bankTransactions.add(new PluggyTransaction(id, description, new BigDecimal(amount), type, "POSTED",
                Instant.parse("2026-10-30T12:00:00Z"), null, createdAt));
    }

    private void transactionsCreated() throws Exception {
        clearInvocations(webPushSender);
        mockMvc.perform(post("/api/v1/webhooks/pluggy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"transactions/created\",\"itemId\":\"" + itemId + "\",\"accountId\":\""
                                 + externalAccountId + "\",\"transactionsCreatedAtFrom\":\"" + EVENT_FROM + "\"}"))
                .andExpect(status().isNoContent());
    }

    private List<String> sentPayloads() {
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        try {
            verify(webPushSender, atLeast(0)).send(any(PushSubscription.class), payloads.capture());
        } catch (AssertionError e) {
            return List.of();
        }
        return payloads.getAllValues();
    }

    private void subscribe(String bearer, String endpoint, boolean hideAmounts) throws Exception {
        perform(post("/api/v1/push/subscriptions").content(subscriptionJson(endpoint, hideAmounts)), bearer)
                .andExpect(status().isOk());
    }

    private static String subscriptionJson(String endpoint, boolean hideAmounts) {
        return "{\"endpoint\":\"" + endpoint + "\",\"p256dh\":\"key\",\"auth\":\"auth\",\"deviceLabel\":\"Android\","
               + "\"hideAmounts\":" + hideAmounts + "}";
    }

    private int pendingCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bank_pending_transactions WHERE user_id = ? AND discarded_at IS NULL", Integer.class, userId);
    }

    private int transactionCount(String ref) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ? AND external_ref = ?",
                Integer.class, userId, ref);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON));
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
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
                UUID.class, "pending-" + UUID.randomUUID() + "@example.com");
    }
}
