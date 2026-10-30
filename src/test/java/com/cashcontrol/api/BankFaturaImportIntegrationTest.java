package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRow;
import com.cashcontrol.api.dto.response.BankBillResponse;
import com.cashcontrol.api.dto.response.FaturaImportGroupPreview;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.dto.response.FaturaImportPreviewRow;
import com.cashcontrol.api.dto.response.FaturaImportResultResponse;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.FaturaImportService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.CreditCardMetadata;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyBill;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Prévia da fatura a partir da Pluggy: seções por final de cartão, parcelas pela data da compra
 * e a cadeia de parcelas estável de um mês para o outro.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {"app.pluggy.client-id=test-client", "app.pluggy.client-secret=test-secret"})
class BankFaturaImportIntegrationTest {

    private static final String JULY = "bill-07";
    private static final String AUGUST = "bill-08";

    @Autowired private FaturaImportService faturaImportService;
    @Autowired private AccountService accountService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private PluggyClient pluggyClient;

    private UUID userId;
    private UUID accountId;
    private UUID cardId;
    private String externalAccountId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) "
                        + "VALUES (?, (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), "
                        + "(SELECT id FROM auth_origins WHERE slug = 'LOCAL'), NOW()) RETURNING id",
                UUID.class, "bank-fatura-" + UUID.randomUUID() + "@example.com");
        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null), userId).id();
        // Fechamento 28 e vencimento 7, como o Inter: a fatura que vence em 07/08 é a de 2026-07.
        cardId = creditCardService.createCard(new CreateCardRequest("Inter", CardBrand.MASTERCARD, "Banco Inter",
                "7866", new BigDecimal("8400.00"), 28, 7, null), userId).id();
        externalAccountId = UUID.randomUUID().toString();
        UUID connectionId = jdbcTemplate.queryForObject(
                "INSERT INTO bank_connections (user_id, provider, item_id, status) VALUES (?, 'PLUGGY', ?, 'UPDATED') RETURNING id",
                UUID.class, userId, UUID.randomUUID().toString());
        jdbcTemplate.update("INSERT INTO bank_connection_accounts (connection_id, external_account_id, kind, name, number, credit_card_id) "
                + "VALUES (?, ?, 'CREDIT', 'GOLD', '7866', ?)", connectionId, externalAccountId, cardId);
        when(pluggyClient.listBills(externalAccountId)).thenReturn(List.of(
                new PluggyBill(JULY, Instant.parse("2026-08-07T00:00:00Z"), new BigDecimal("1790.00")),
                new PluggyBill(AUGUST, Instant.parse("2026-09-07T00:00:00Z"), new BigDecimal("100.00"))));
    }

    @Test
    void listBills_currentOpenFirst_thenClosedNewestFirst() {
        assertThat(faturaImportService.listBankBills(cardId, userId))
                .extracting(BankBillResponse::billId, BankBillResponse::dueDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("open", LocalDate.of(2026, 10, 7)),
                        org.assertj.core.groups.Tuple.tuple(AUGUST, LocalDate.of(2026, 9, 7)),
                        org.assertj.core.groups.Tuple.tuple(JULY, LocalDate.of(2026, 8, 7)));
    }

    @Test
    void preview_groupsByCardNumber_suggestsTheLinkedCard_andDropsThePayment() {
        givenBank(julyBill());

        FaturaImportPreviewResponse preview = faturaImportService.previewFromBank(cardId, JULY, userId);

        assertThat(preview.format()).isEqualTo(InvoiceImportFormat.OPEN_FINANCE);
        assertThat(preview.dueDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(preview.referenceMonth()).isEqualTo("2026-07");
        assertThat(preview.totalAmount()).isEqualByComparingTo("1790.00");
        // O pagamento vem num "cartão" só dele (7914): sai, e a seção some junto.
        assertThat(preview.excludedPaymentsCount()).isEqualTo(1);
        assertThat(preview.groups()).extracting(FaturaImportGroupPreview::cardLast4).containsExactly("7866", "4776");
        assertThat(preview.groups()).extracting(FaturaImportGroupPreview::suggestedCreditCardId)
                .containsOnly(cardId);

        FaturaImportPreviewRow parcel = row(preview, "MERCADOLIVRE");
        assertThat(parcel.date()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(parcel.installmentNumber()).isEqualTo(3);
        assertThat(parcel.totalInstallments()).isEqualTo(5);
        assertThat(parcel.description()).isEqualTo("MERCADOLIVRE MERCADOL EXTREMA BRA");

        FaturaImportPreviewRow refund = row(preview, "AZUL");
        assertThat(refund.refund()).isTrue();
        assertThat(refund.amount()).isEqualByComparingTo("40.00");
        // Pendente e lançamento de outra fatura não entram.
        assertThat(preview.totalRows()).isEqualTo(3);
    }

    @Test
    void nextMonthsParcel_isTheOneAlreadyGenerated_andGetsTheRealAmount() {
        givenBank(julyBill());
        FaturaImportResultResponse july = commit(faturaImportService.previewFromBank(cardId, JULY, userId));
        assertThat(july.imported()).isEqualTo(3);
        assertThat(july.futureInstallments()).isEqualTo(2);

        // Em agosto a parcela 4 chega com outro id na Pluggy e o resto da divisão nos centavos.
        givenBank(card("aug-4", "MERCADOLIVRE MERCADOL  EXTREMA       BRA", "45.97", "DEBIT", "2026-08-10", "4776",
                AUGUST, 4, 5, "2026-05-10"));
        FaturaImportPreviewResponse august = faturaImportService.previewFromBank(cardId, AUGUST, userId);

        FaturaImportPreviewRow parcel = row(august, "MERCADOLIVRE");
        assertThat(parcel.duplicate()).isTrue();
        assertThat(parcel.possibleDuplicate()).isFalse();

        commit(august);
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM invoice_items WHERE user_id = ? AND installment_number = 4",
                BigDecimal.class, userId)).isEqualByComparingTo("45.97");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM invoice_items WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(5);
    }

    @Test
    void sameChargeFromAnotherOrigin_isAPossibleDuplicate() {
        givenBank(julyBill());
        commit(faturaImportService.previewFromBank(cardId, JULY, userId));

        // As mesmas compras com outra descrição: como viriam do PDF, chaves diferentes.
        givenBank(
                card("pdf-1", "Tokio Marine", "99.76", "DEBIT", "2026-07-20", "7866", JULY, null, null, null),
                card("pdf-2", "MERCADOLIVRE*MERCADOL", "45.95", "DEBIT", "2026-07-10", "4776", JULY, 3, 5, "2026-05-10"),
                card("pdf-3", "Outra loja", "99.76", "DEBIT", "2026-07-21", "7866", JULY, null, null, null));
        FaturaImportPreviewResponse preview = faturaImportService.previewFromBank(cardId, JULY, userId);

        assertThat(row(preview, "Tokio").possibleDuplicate()).isTrue();
        assertThat(row(preview, "MERCADOLIVRE").possibleDuplicate()).isTrue();
        // Mesmo valor em outro dia não é o mesmo fato.
        assertThat(row(preview, "Outra").possibleDuplicate()).isFalse();
        assertThat(preview.groups()).flatExtracting(FaturaImportGroupPreview::rows)
                .noneMatch(FaturaImportPreviewRow::duplicate);
    }

    @Test
    void openBill_bringsChargesWithoutBill_pendingIncluded_andClosingLaterDoesNotDuplicate() {
        givenBank(
                openCharge("p1", "PENDING", "EBN CAPCUT             CURITIBA      BRA", "65.90", "2026-09-11"),
                openCharge("p2", "POSTED", "PATREON  MEMBERSHIP    INTERNET      IRL", "37.50", "2026-09-09"),
                card("closed", "LOJA DE AGOSTO", "10.00", "DEBIT", "2026-08-20", "7866", AUGUST, null, null, null));

        FaturaImportPreviewResponse open = faturaImportService.previewFromBank(cardId, "open", userId);

        assertThat(open.dueDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(open.referenceMonth()).isEqualTo("2026-09");
        assertThat(open.totalAmount()).isNull();
        assertThat(open.totalRows()).isEqualTo(2);
        commit(open);

        // A fatura fecha: os mesmos lançamentos voltam com billId e outro status.
        when(pluggyClient.listBills(externalAccountId)).thenReturn(List.of(
                new PluggyBill("bill-09", Instant.parse("2026-10-07T00:00:00Z"), new BigDecimal("103.40"))));
        givenBank(
                card("p1", "EBN CAPCUT             CURITIBA      BRA", "65.90", "DEBIT", "2026-09-11", "7866",
                        "bill-09", null, null, null),
                card("p2", "PATREON  MEMBERSHIP    INTERNET      IRL", "37.50", "DEBIT", "2026-09-09", "7866",
                        "bill-09", null, null, null));

        FaturaImportPreviewResponse closed = faturaImportService.previewFromBank(cardId, "bill-09", userId);

        assertThat(closed.duplicateCount()).isEqualTo(2);
    }

    private static PluggyTransaction openCharge(String id, String status, String description, String amount, String date) {
        return new PluggyTransaction(id, description, new BigDecimal(amount), "DEBIT", status,
                Instant.parse(date + "T03:00:00Z"), new CreditCardMetadata("7866", null, null, null, null));
    }

    @Test
    void cardWithoutLink_isRefused() {
        UUID other = creditCardService.createCard(new CreateCardRequest("Nubank", CardBrand.MASTERCARD, "Nubank",
                "1111", new BigDecimal("1000.00"), 25, 5, null), userId).id();

        assertThatThrownBy(() -> faturaImportService.previewFromBank(other, JULY, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está vinculado");
    }

    private static PluggyTransaction[] julyBill() {
        return new PluggyTransaction[]{
                card("single", "TOKIO MARINE AUTO03D10 SAO PAULO     BRA", "99.76", "DEBIT", "2026-07-20", "7866",
                        JULY, null, null, null),
                // A parcela vem com a data dela na fatura; a da compra está no metadado.
                card("parcel", "MERCADOLIVRE MERCADOL  EXTREMA       BRA", "45.99", "DEBIT", "2026-07-10", "4776",
                        JULY, 3, 5, "2026-05-10"),
                card("refund", "AZUL WE BPP9874012038  BARUERI       BRA", "-40.00", "CREDIT", "2026-07-15", "7866",
                        JULY, null, null, null),
                card("payment", "PAGTO DEBITO AUTOMATICO", "-1500.00", "CREDIT", "2026-07-08", "7914",
                        JULY, null, null, null),
                card("other-bill", "LOJA DE JUNHO", "10.00", "DEBIT", "2026-06-20", "7866", "bill-06", null, null, null),
                new PluggyTransaction("pending", "AINDA ABERTA", new BigDecimal("5.00"), "DEBIT", "PENDING",
                        Instant.parse("2026-07-25T03:00:00Z"),
                        new CreditCardMetadata("7866", null, null, null, null))};
    }

    /** Como a Pluggy devolve um lançamento de cartão do Inter: meia-noite de Brasília. */
    private static PluggyTransaction card(String id, String description, String amount, String type, String date,
                                          String cardNumber, String billId, Integer number, Integer total,
                                          String purchaseDate) {
        return new PluggyTransaction(id, description, new BigDecimal(amount), type, "POSTED",
                Instant.parse(date + "T03:00:00Z"),
                new CreditCardMetadata(cardNumber, billId, number, total,
                        purchaseDate == null ? null : Instant.parse(purchaseDate + "T00:00:00Z")));
    }

    private void givenBank(PluggyTransaction... transactions) {
        when(pluggyClient.listTransactions(externalAccountId)).thenReturn(List.of(transactions));
    }

    private static FaturaImportPreviewRow row(FaturaImportPreviewResponse preview, String prefix) {
        return preview.groups().stream().flatMap(group -> group.rows().stream())
                .filter(row -> row.description().startsWith(prefix)).findFirst().orElseThrow();
    }

    /** Confirma tudo, como o diálogo faz: as já importadas vão junto para corrigir o valor. */
    private FaturaImportResultResponse commit(FaturaImportPreviewResponse preview) {
        List<FaturaImportCommitRow> rows = preview.groups().stream()
                .flatMap(group -> group.rows().stream().map(row -> new FaturaImportCommitRow(
                        row.lineNumber(), group.suggestedCreditCardId(), group.cardLast4(), row.externalRef(),
                        row.ordinal(), row.date(), row.description(), row.description(), row.amount(),
                        row.installmentNumber(), row.totalInstallments(), row.suggestedCategoryId(), row.refund())))
                .toList();
        return faturaImportService.commit(new FaturaImportCommitRequest(
                InvoiceImportFormat.OPEN_FINANCE, preview.referenceMonth(), accountId, rows), userId);
    }
}
