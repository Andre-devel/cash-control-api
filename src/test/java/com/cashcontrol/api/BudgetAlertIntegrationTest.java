package com.cashcontrol.api;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.PushSubscription;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRequest;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.PushSubscriptionRequest;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.TransactionService;
import com.cashcontrol.api.service.budget.BudgetAlertService;
import com.cashcontrol.api.service.budget.BudgetService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {"app.push.vapid-public-key=test-public", "app.push.vapid-private-key=test-private"})
class BudgetAlertIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 20);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;
    @Autowired private PushService pushService;
    @Autowired private BudgetService budgetService;
    @Autowired private BudgetAlertService budgetAlertService;
    @MockitoBean private WebPushSender webPushSender;

    private UUID userId;
    private UUID accountId;
    private UUID food;

    @BeforeEach
    void setUp() {
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushSender.Result.DELIVERED);
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW() - INTERVAL '1 minute') RETURNING id",
                UUID.class, "budget-alert-" + UUID.randomUUID() + "@example.com");
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta", AccountType.CHECKING, "BRL", null, null, new BigDecimal("5000.00")), userId).id();
        food = categoryService.createCategory(new CreateCategoryRequest("Mercado", null, null, null, null), userId).id();
        budgetService.save(userId, food, new BigDecimal("1000.00"), null);
    }

    @Test
    void warnsAt80_overAt100_onceEach_andOnlyDevicesThatOptedIn() {
        subscribe(true, false);
        subscribe(false, false);
        assertThat(budgetAlertService.usersToCheck()).contains(userId);

        expense("700.00");
        assertThat(budgetAlertService.check(userId, TODAY)).isZero();

        expense("150.00");
        assertThat(budgetAlertService.check(userId, TODAY)).isEqualTo(1);
        JsonNode warn = lastPayload();
        assertThat(warn.get("type").asText()).isEqualTo("budget");
        assertThat(warn.get("categoryId").asText()).isEqualTo(food.toString());
        assertThat(warn.get("title").asText()).isEqualTo("Mercado: 85% do orçamento");
        assertThat(warn.get("body").asText()).contains("Falta").contains("150,00").contains("1.000,00");
        verify(webPushSender, atLeast(1)).send(any(), anyString());
        assertThat(sentTo()).allMatch(PushSubscription::isBudgetAlerts);

        clearInvocations(webPushSender);
        assertThat(budgetAlertService.check(userId, TODAY)).isZero();
        verify(webPushSender, never()).send(any(), anyString());

        expense("200.00");
        assertThat(budgetAlertService.check(userId, TODAY)).isEqualTo(1);
        JsonNode over = lastPayload();
        assertThat(over.get("title").asText()).isEqualTo("Mercado: passou do orçamento");
        assertThat(over.get("body").asText()).contains("Passou").contains("50,00");
    }

    @Test
    void jumpingStraightPast100_sendsOnlyTheOverAlert_andHidesAmountsWhenAsked() {
        subscribe(true, true);

        expense("1200.00");
        assertThat(budgetAlertService.check(userId, TODAY)).isEqualTo(1);
        JsonNode over = lastPayload();
        assertThat(over.get("title").asText()).isEqualTo("Mercado: passou do orçamento");
        assertThat(over.get("body").asText()).isEqualTo("Toque para ver o orçamento do mês.");

        assertThat(budgetAlertService.check(userId, TODAY)).isZero();
    }

    @Test
    void userWithoutOptedInDevice_isNotChecked() {
        subscribe(false, false);
        assertThat(budgetAlertService.usersToCheck()).doesNotContain(userId);
    }

    @Test
    void appWithoutTheFlag_keepsTheDeviceChoice() {
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        pushService.subscribe(userId, new PushSubscriptionRequest(endpoint, "p256dh", "auth", null, false, true));
        pushService.subscribe(userId, new PushSubscriptionRequest(endpoint, "p256dh", "auth", null, true, null));

        assertThat(pushService.list(userId)).singleElement()
                .satisfies(device -> assertThat(device.budgetAlerts()).isTrue())
                .satisfies(device -> assertThat(device.hideAmounts()).isTrue());
    }

    private void subscribe(boolean budgetAlerts, boolean hideAmounts) {
        pushService.subscribe(userId, new PushSubscriptionRequest(
                "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID(), "p256dh", "auth", "Android · Chrome",
                hideAmounts, budgetAlerts));
    }

    private void expense(String amount) {
        transactionService.createTransaction(new CreateTransactionRequest(
                accountId, TransactionType.EXPENSE, new BigDecimal(amount), "Compra", TODAY, TODAY, null,
                food, null, null, null, TransactionStatus.PAID, null, null), userId);
    }

    private List<PushSubscription> sentTo() {
        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(webPushSender, atLeast(1)).send(captor.capture(), anyString());
        return captor.getAllValues();
    }

    private JsonNode lastPayload() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(webPushSender, atLeast(1)).send(any(), captor.capture());
        return JSON.readTree(captor.getValue());
    }
}
