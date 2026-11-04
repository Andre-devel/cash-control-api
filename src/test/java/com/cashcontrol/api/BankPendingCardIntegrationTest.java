package com.cashcontrol.api;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.entity.PushSubscription;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRow;
import com.cashcontrol.api.dto.response.BankPendingConfirmAllResponse;
import com.cashcontrol.api.dto.response.BankPendingItem;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.FaturaImportService;
import com.cashcontrol.api.service.openfinance.BankConnectionService;
import com.cashcontrol.api.service.openfinance.BankPendingService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.CreditCardMetadata;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyAccount;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyBill;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyItem;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Compras do cartão vinculado na fila de revisão: a varredura lê a fatura atual na Pluggy (só o
 * que já liquidou), enfileira com a chave da prévia da fatura e notifica; confirmar grava pelo
 * commit do import da fatura, então a parcelada vira série.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret",
        "app.push.vapid-public-key=test-public", "app.push.vapid-private-key=test-private"})
class BankPendingCardIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant SINCE = Instant.parse("2026-11-03T12:00:00Z");
    private static final Instant NEW = SINCE.plusSeconds(60);
    private static final Instant OLD = SINCE.minusSeconds(86_400);

    @Autowired private BankPendingService bankPendingService;
    @Autowired private BankConnectionService bankConnectionService;
    @Autowired private FaturaImportService faturaImportService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private PluggyClient pluggyClient;
    @MockitoBean private WebPushSender webPushSender;

    private UUID userId;
    private UUID accountId;
    private UUID cardId;
    private UUID connectionId;
    private String itemId;
    private String externalAccountId;
    private final List<PluggyTransaction> bank = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW()) RETURNING id",
                UUID.class, "pending-card-" + UUID.randomUUID() + "@example.com");
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        // Fechamento 28 e vencimento 7: compra de 03/11 cai na fatura 2026-11, que vence em 07/12.
        cardId = creditCardService.createCard(new CreateCardRequest("Inter Gold", CardBrand.MASTERCARD, "Banco Inter",
                "7866", new BigDecimal("8400.00"), 28, 7, null), userId).id();
        itemId = UUID.randomUUID().toString();
        externalAccountId = "ext-card-" + itemId;
        connectionId = jdbcTemplate.queryForObject(
                "INSERT INTO bank_connections (user_id, provider, item_id, status, last_synced_at) "
                        + "VALUES (?, 'PLUGGY', ?, 'UPDATED', ?) RETURNING id",
                UUID.class, userId, itemId, java.sql.Timestamp.from(SINCE));
        jdbcTemplate.update("INSERT INTO bank_connection_accounts (connection_id, external_account_id, kind, name, number, credit_card_id) "
                + "VALUES (?, ?, 'CREDIT', 'GOLD', '7866', ?)", connectionId, externalAccountId, cardId);
        jdbcTemplate.update("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, hide_amounts) VALUES (?, ?, 'k', 'a', FALSE)",
                userId, "https://push.example.com/" + UUID.randomUUID());
        when(pluggyClient.listTransactions(externalAccountId)).thenAnswer(invocation -> List.copyOf(bank));
        when(pluggyClient.listAccounts(itemId)).thenReturn(List.of(
                new PluggyAccount(externalAccountId, "CREDIT", "CREDIT_CARD", "GOLD", null, "7866")));
        when(pluggyClient.listBills(externalAccountId)).thenReturn(List.of(
                new PluggyBill("bill-10", Instant.parse("2026-11-07T00:00:00Z"), new BigDecimal("500.00"))));
        when(webPushSender.send(any(), anyString())).thenReturn(WebPushSender.Result.DELIVERED);
    }

    @Test
    void newCharge_entersTheQueue_andNotifies_withTheCategory_andHidingAmounts() throws Exception {
        UUID shopping = jdbcTemplate.queryForObject(
                "INSERT INTO categories (user_id, name) VALUES (?, 'Compras') RETURNING id", UUID.class, userId);
        jdbcTemplate.update("INSERT INTO category_rules (user_id, pattern, category_id) VALUES (?, 'loja', ?)", userId, shopping);
        jdbcTemplate.update("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, hide_amounts) VALUES (?, ?, 'k', 'a', TRUE)",
                userId, "https://push.example.com/" + UUID.randomUUID());
        charge("c1", "LOJA   DO   ZE", "300.00", "POSTED", NEW, null, null, null);

        assertThat(enqueue()).isEqualTo(1);

        List<JsonNode> payloads = sentPayloads();
        assertThat(payloads).hasSize(2);
        assertThat(payloads).extracting(payload -> payload.get("title").asText())
                .containsExactlyInAnyOrder("Compra no cartão · R$ 300,00", "Compra no cartão");
        assertThat(payloads).extracting(payload -> payload.get("body").asText()).containsOnly("LOJA DO ZE → Compras?");
        assertThat(payloads.getFirst().get("token").asText()).isNotBlank();

        BankPendingItem item = bankPendingService.list(userId).getFirst();
        assertThat(item.accountId()).isNull();
        assertThat(item.accountName()).isNull();
        assertThat(item.creditCardId()).isEqualTo(cardId);
        assertThat(item.creditCardName()).isEqualTo("Inter Gold");
        assertThat(item.row().type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(item.row().amount()).isEqualByComparingTo("300.00");
        assertThat(item.row().suggestedCategoryId()).isEqualTo(shopping);
        assertThat(item.row().duplicate()).isFalse();
    }

    @Test
    void confirm_savesTheChargeOnTheCurrentInvoice_withTheEditedDescriptionAndCategory() {
        UUID food = jdbcTemplate.queryForObject(
                "INSERT INTO categories (user_id, name) VALUES (?, 'Alimentação') RETURNING id", UUID.class, userId);
        charge("c1", "IFOOD *RESTAURANTE", "45.90", "POSTED", NEW, null, null, null);
        enqueue();
        BankPendingItem item = bankPendingService.list(userId).getFirst();

        bankPendingService.confirm(userId, item.id(), new com.cashcontrol.api.dto.request.ImportCommitRow(
                1, item.row().externalRef(), item.row().date(), "Jantar", item.row().description(),
                new BigDecimal("1.00"), TransactionType.INCOME, null, food, false, null, null, null, null));

        // Valor e tipo são do banco; só descrição e categoria vêm da edição.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT i.description || '|' || i.amount || '|' || i.category_id || '|' || v.reference_month || '|' "
                        + "|| t.account_id || '|' || t.credit_card_id || '|' || t.type "
                        + "FROM invoice_items i JOIN invoices v ON v.id = i.invoice_id JOIN transactions t ON t.id = i.transaction_id "
                        + "WHERE i.user_id = ? AND i.external_ref = ?",
                String.class, userId, item.row().externalRef()))
                .isEqualTo("Jantar|45.90|" + food + "|2026-11|" + accountId + "|" + cardId + "|EXPENSE");
        assertThat(pendingCount()).isZero();
    }

    @Test
    void parcel1of10_confirmedFromTheNotification_createsTheSeriesWithTenInstallments() {
        charge("p1", "MAGAZINE LUIZA", "30.00", "POSTED", NEW, 1, 10, "2026-11-03");
        enqueue();
        JsonNode payload = sentPayloads().getFirst();
        assertThat(payload.get("title").asText()).isEqualTo("Compra 10x no cartão · R$ 30,00/mês");

        BankPendingItem item = bankPendingService.list(userId).getFirst();
        assertThat(item.installmentNumber()).isEqualTo(1);
        assertThat(item.totalInstallments()).isEqualTo(10);

        bankPendingService.confirmByToken(UUID.fromString(payload.get("pendingId").asText()), payload.get("token").asText());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM installment_series WHERE user_id = ? AND total_installments = 10",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT installment_number) FROM invoice_items WHERE user_id = ? AND installment_series_id IS NOT NULL",
                Integer.class, userId)).isEqualTo(10);
        assertThat(pendingCount()).isZero();

        // A parcela 2 chega no mês seguinte com a chave da que a confirmação já gerou: não entra.
        bank.clear();
        charge("p2", "MAGAZINE LUIZA", "30.00", "POSTED", NEW, 2, 10, "2026-11-03");
        assertThat(enqueue()).isZero();
    }

    @Test
    void parcelOfAnExistingSeries_isLinkedToIt_withoutANewSeries() {
        // A parcela 3 de 10 entrou pelo diálogo "Buscar do banco" e gerou a série com as 4 a 10.
        charge("p3", "LOJA PARCELADA", "50.00", "POSTED", OLD, 3, 10, "2026-09-03");
        commitDialog(faturaImportService.previewFromBank(cardId, "open", userId));
        UUID series = jdbcTemplate.queryForObject("SELECT id FROM installment_series WHERE user_id = ?", UUID.class, userId);

        charge("p2", "LOJA PARCELADA", "50.00", "POSTED", NEW, 2, 10, "2026-09-03");
        assertThat(enqueue()).isEqualTo(1);
        BankPendingConfirmAllResponse all = bankPendingService.confirmAll(userId);

        assertThat(all.confirmed()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM installment_series WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT installment_series_id FROM transactions WHERE user_id = ? AND installment_number = 2",
                UUID.class, userId)).isEqualTo(series);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoice_items WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(9);
    }

    @Test
    void chargeAlreadyImportedByTheDialog_doesNotEnter() {
        charge("c1", "PADARIA", "12.00", "POSTED", NEW, null, null, null);
        commitDialog(faturaImportService.previewFromBank(cardId, "open", userId));

        assertThat(enqueue()).isZero();
        assertThat(sentPayloads()).isEmpty();
    }

    @Test
    void sameChargeInTwoSweeps_isQueuedOnce_andCountsInTheSyncHistory() {
        charge("c1", "PADARIA", "12.00", "POSTED", NEW, null, null, null);
        givenItemUpdatedAt(SINCE.plusSeconds(3600));
        bankConnectionService.poll(connectionId);
        givenItemUpdatedAt(SINCE.plusSeconds(7200));
        // O corte da segunda varredura é a sincronização anterior; a Pluggy devolve a mesma compra
        // com createdAt dentro dele, e a chave é que impede a segunda entrada.
        bank.clear();
        charge("c1", "PADARIA", "12.00", "POSTED", SINCE.plusSeconds(3700), null, null, null);
        bankConnectionService.poll(connectionId);

        assertThat(pendingCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT transactions_queued FROM bank_sync_runs WHERE connection_id = ? ORDER BY started_at",
                Integer.class, connectionId)).containsExactly(1, 0);
    }

    @Test
    void pendingCharge_doesNotEnter() {
        charge("c1", "UBER *TRIP", "25.00", "PENDING", NEW, null, null, null);

        assertThat(enqueue()).isZero();
        assertThat(pendingCount()).isZero();
    }

    @Test
    void cardWithoutLink_doesNotEnter() {
        jdbcTemplate.update("UPDATE bank_connection_accounts SET credit_card_id = NULL WHERE connection_id = ?", connectionId);
        charge("c1", "PADARIA", "12.00", "POSTED", NEW, null, null, null);
        givenItemUpdatedAt(SINCE.plusSeconds(3600));

        bankConnectionService.poll(connectionId);

        assertThat(pendingCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT transactions_queued FROM bank_sync_runs WHERE connection_id = ?",
                Integer.class, connectionId)).isZero();
    }

    private int enqueue() {
        clearInvocations(webPushSender);
        return bankPendingService.enqueueCard(userId, cardId, SINCE, Instant.now());
    }

    /** Lançamento da fatura atual (sem billId), como a Pluggy devolve: meia-noite de Brasília. */
    private void charge(String id, String description, String amount, String status, Instant createdAt,
                        Integer number, Integer total, String purchaseDate) {
        bank.add(new PluggyTransaction(id, description, new BigDecimal(amount), "DEBIT", status,
                Instant.parse("2026-11-03T03:00:00Z"),
                new CreditCardMetadata("7866", null, number, total,
                        purchaseDate == null ? null : Instant.parse(purchaseDate + "T00:00:00Z")),
                createdAt));
    }

    private void givenItemUpdatedAt(Instant lastUpdatedAt) {
        when(pluggyClient.getItem(itemId)).thenReturn(Optional.of(new PluggyItem(itemId, "UPDATED", null,
                userId.toString(), lastUpdatedAt, new PluggyItem.Connector("Inter"), null)));
    }

    private void commitDialog(FaturaImportPreviewResponse preview) {
        List<FaturaImportCommitRow> rows = preview.groups().stream()
                .flatMap(group -> group.rows().stream().map(row -> new FaturaImportCommitRow(
                        row.lineNumber(), group.suggestedCreditCardId(), group.cardLast4(), row.externalRef(),
                        row.ordinal(), row.date(), row.description(), row.description(), row.amount(),
                        row.installmentNumber(), row.totalInstallments(), row.suggestedCategoryId(), row.refund())))
                .toList();
        faturaImportService.commit(new FaturaImportCommitRequest(
                InvoiceImportFormat.OPEN_FINANCE, preview.referenceMonth(), accountId, rows), userId);
    }

    private List<JsonNode> sentPayloads() {
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(webPushSender, atLeast(0)).send(any(PushSubscription.class), payloads.capture());
        return payloads.getAllValues().stream().map(JSON::readTree).toList();
    }

    private int pendingCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bank_pending_transactions WHERE user_id = ? AND discarded_at IS NULL", Integer.class, userId);
    }
}
