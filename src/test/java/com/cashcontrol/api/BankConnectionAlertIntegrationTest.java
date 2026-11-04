package com.cashcontrol.api;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.BankAccountKind;
import com.cashcontrol.api.domain.entity.BankConnection;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.entity.BankProvider;
import com.cashcontrol.api.dto.request.PushSubscriptionRequest;
import com.cashcontrol.api.repository.BankConnectionAccountRepository;
import com.cashcontrol.api.repository.BankConnectionRepository;
import com.cashcontrol.api.service.openfinance.BankConnectionAlertService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.push.PushService;
import com.cashcontrol.api.service.push.WebPushSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {
        "app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret",
        "app.push.vapid-public-key=test-public", "app.push.vapid-private-key=test-private"})
class BankConnectionAlertIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 10:23 em Brasília, o horário do agendador. */
    private static final Instant NOW = Instant.parse("2026-11-04T13:23:00Z");
    private static final Instant RECENT = NOW.minus(Duration.ofHours(2));

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PushService pushService;
    @Autowired private BankConnectionAlertService alertService;
    @Autowired private BankConnectionRepository connectionRepository;
    @Autowired private BankConnectionAccountRepository connectionAccountRepository;
    @MockitoBean private WebPushSender webPushSender;
    @MockitoBean private PluggyClient pluggyClient;

    private UUID userId;

    @BeforeEach
    void setUp() {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushSender.Result.DELIVERED);
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "connection-alert-" + UUID.randomUUID() + "@example.com");
        pushService.subscribe(userId, new PushSubscriptionRequest(
                "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID(), "p256dh", "auth", "Android · Chrome",
                false, false));
        clearInvocations(webPushSender);
    }

    @Test
    void loginError_asksToReconnect_withTheBankDetail() {
        BankConnection connection = connection("LOGIN_ERROR", "Senha alterada no banco.", RECENT, null);

        assertThat(alertService.checkAll(NOW)).isPositive();

        JsonNode push = onlyPushFor(connection);
        assertThat(push.get("type").asText()).isEqualTo("connection");
        assertThat(push.get("title").asText()).isEqualTo("Inter: precisa reconectar");
        assertThat(push.get("body").asText()).isEqualTo("Senha alterada no banco.");
    }

    @Test
    void outdatedWithoutDetail_usesTheDefaultBody() {
        BankConnection connection = connection("OUTDATED", null, RECENT, null);

        alertService.checkAll(NOW);

        assertThat(onlyPushFor(connection).get("body").asText())
                .isEqualTo("O banco parou de enviar dados. Reconecte no Perfil.");
    }

    @Test
    void syncedTwentyHoursAgo_doesNotWarn() {
        BankConnection connection = connection("UPDATED", null, NOW.minus(Duration.ofHours(20)), null);

        alertService.checkAll(NOW);

        assertThat(pushesFor(connection)).isEmpty();
    }

    @Test
    void syncedThirtyHoursAgo_warnsWithTheLastUpdateInBrasiliaTime() {
        BankConnection connection = connection("UPDATED", null, NOW.minus(Duration.ofHours(30)), null);

        alertService.checkAll(NOW);

        JsonNode push = onlyPushFor(connection);
        assertThat(push.get("title").asText()).isEqualTo("Inter: sem dados novos");
        assertThat(push.get("body").asText())
                .isEqualTo("Última atualização com o banco em 03/11 às 04:23. Confira a conexão no Perfil.");
    }

    @Test
    void consentAt15_7_and1Days_warns_butAt10DaysDoesNot() {
        BankConnection in15 = connection("UPDATED", null, RECENT, NOW.plus(Duration.ofDays(15)).plus(Duration.ofHours(1)));
        BankConnection in7 = connection("UPDATED", null, RECENT, NOW.plus(Duration.ofDays(7)).plus(Duration.ofHours(1)));
        BankConnection in1 = connection("UPDATED", null, RECENT, NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)));
        BankConnection in10 = connection("UPDATED", null, RECENT, NOW.plus(Duration.ofDays(10)).plus(Duration.ofHours(1)));

        alertService.checkAll(NOW);

        JsonNode push15 = onlyPushFor(in15);
        assertThat(push15.get("title").asText()).isEqualTo("Inter: consentimento vence em 15 dias");
        assertThat(push15.get("body").asText())
                .isEqualTo("O consentimento vence em 19/11. Renove no Perfil para o app continuar lendo o banco.");
        assertThat(onlyPushFor(in7).get("title").asText()).isEqualTo("Inter: consentimento vence em 7 dias");
        assertThat(onlyPushFor(in1).get("title").asText()).isEqualTo("Inter: consentimento vence em 1 dia");
        assertThat(pushesFor(in10)).isEmpty();
    }

    @Test
    void consentExpiringTomorrowMorning_lessThan24hAway_warnsOneDay_notExpired() {
        // Amanhã às 08:00 em Brasília: ~21,6 h depois do agendador, mas um dia de calendário.
        BankConnection connection = connection("UPDATED", null, RECENT, Instant.parse("2026-11-05T11:00:00Z"));

        alertService.checkAll(NOW);

        JsonNode push = onlyPushFor(connection);
        assertThat(push.get("title").asText()).isEqualTo("Inter: consentimento vence em 1 dia");
        assertThat(push.get("body").asText()).startsWith("O consentimento vence em 05/11.");
    }

    @Test
    void expiredConsent_warnsEveryDay() {
        BankConnection connection = connection("UPDATED", null, RECENT, NOW.minus(Duration.ofDays(2)));

        alertService.checkAll(NOW);
        alertService.checkAll(NOW.plus(Duration.ofDays(1)));

        List<JsonNode> pushes = pushesFor(connection);
        assertThat(pushes).hasSize(2);
        assertThat(pushes.getFirst().get("title").asText()).isEqualTo("Inter: consentimento venceu");
        assertThat(pushes.getFirst().get("body").asText()).startsWith("O consentimento venceu em 02/11. Reconecte no Perfil");
    }

    @Test
    void errorAndConsentTogether_goInOnePush_underTheErrorTitle() {
        BankConnection connection = connection("WAITING_USER_INPUT", null, RECENT,
                NOW.plus(Duration.ofDays(7)).plus(Duration.ofHours(1)));

        alertService.checkAll(NOW);

        JsonNode push = onlyPushFor(connection);
        assertThat(push.get("title").asText()).isEqualTo("Inter: precisa reconectar");
        assertThat(push.get("body").asText()).isEqualTo("O banco parou de enviar dados. Reconecte no Perfil. "
                + "O consentimento vence em 11/11. Renove no Perfil para o app continuar lendo o banco.");
    }

    @Test
    void title_usesTheCheckingAccountNumber() {
        BankConnection connection = connection("LOGIN_ERROR", null, RECENT, null);
        account(connection, BankAccountKind.CREDIT, "1234");
        account(connection, BankAccountKind.BANK, "1234567-8");

        alertService.checkAll(NOW);

        assertThat(onlyPushFor(connection).get("title").asText()).isEqualTo("Inter · 1234567-8: precisa reconectar");
    }

    @Test
    void healthyConnection_sendsNothing() {
        BankConnection connection = connection("UPDATED", null, RECENT, NOW.plus(Duration.ofDays(200)));

        alertService.checkAll(NOW);

        assertThat(pushesFor(connection)).isEmpty();
    }

    private BankConnection connection(String status, String detail, Instant lastSyncedAt, Instant consentExpiresAt) {
        BankConnection connection = new BankConnection();
        connection.setUserId(userId);
        connection.setProvider(BankProvider.PLUGGY);
        connection.setItemId(UUID.randomUUID().toString());
        connection.setConnectorName("Inter");
        connection.setStatus(status);
        connection.setStatusDetail(detail);
        connection.setLastSyncedAt(lastSyncedAt);
        connection.setConsentExpiresAt(consentExpiresAt);
        return connectionRepository.save(connection);
    }

    private void account(BankConnection connection, BankAccountKind kind, String number) {
        BankConnectionAccount account = new BankConnectionAccount();
        account.setConnectionId(connection.getId());
        account.setExternalAccountId("ext-" + UUID.randomUUID());
        account.setKind(kind);
        account.setName(kind == BankAccountKind.BANK ? "Conta Corrente" : "Cartão");
        account.setNumber(number);
        connectionAccountRepository.save(account);
    }

    private JsonNode onlyPushFor(BankConnection connection) {
        List<JsonNode> pushes = pushesFor(connection);
        assertThat(pushes).hasSize(1);
        return pushes.getFirst();
    }

    /** Pushes desta conexão; o banco é compartilhado e pode ter conexões de outras classes de teste. */
    private List<JsonNode> pushesFor(BankConnection connection) {
        if (mockingDetails(webPushSender).getInvocations().isEmpty()) {
            return List.of();
        }
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(webPushSender, atLeast(1)).send(any(), captor.capture());
        return captor.getAllValues().stream()
                .map(JSON::readTree)
                .filter(push -> connection.getId().toString().equals(push.path("connectionId").asText()))
                .toList();
    }
}
