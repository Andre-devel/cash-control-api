package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRow;
import com.cashcontrol.api.dto.request.PayInvoiceRequest;
import com.cashcontrol.api.dto.request.UpdateInvoiceItemRequest;
import com.cashcontrol.api.dto.response.FaturaImportGroupPreview;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.dto.response.FaturaImportPreviewRow;
import com.cashcontrol.api.dto.response.InvoiceItemResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.InvoiceSummaryResponse;
import com.cashcontrol.api.dto.response.MerchantScopeResponse;
import com.cashcontrol.api.dto.response.UpdateInvoiceItemResponse;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.FaturaImportService;
import com.cashcontrol.api.service.InvoiceManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A tela de gerenciamento de faturas de ponta a ponta contra um Postgres real: edita um item
 * já importado e prova que a próxima importação aprende com essa edição — o mesmo ciclo que a
 * memória de estabelecimento (V27) fecha para o próprio diálogo de import, agora fechado a
 * partir de fora dele.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class InvoiceManagementIntegrationTest {

    @Autowired private FaturaImportService faturaImportService;
    @Autowired private InvoiceManagementService invoiceManagementService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private AccountService accountService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;
    private UUID cardAId;
    private UUID cardBId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "invoice-mgmt-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();

        cardAId = createCard("Inter Titular", FaturaPdfFixture.CARD_A_LAST4);
        cardBId = createCard("Inter Adicional", FaturaPdfFixture.CARD_B_LAST4);
    }

    @Test
    void updateItem_rememberMerchant_prefillsTheNextImportWithTheAlias() {
        commit(preview());
        InvoiceItemResponse lojaDeTeste = itemStartingWith(
                getInvoiceById(cardAId, FaturaPdfFixture.REFERENCE_MONTH), "LOJA DE TESTE");

        invoiceManagementService.updateItem(lojaDeTeste.id(),
                new UpdateInvoiceItemRequest("Loja boa", null, null, true, false), userId);

        FaturaImportPreviewResponse august = faturaImportService.preview(
                nextMonthFixture(), InvoiceImportFormat.INTER_FATURA_PDF, userId);
        FaturaImportPreviewRow row = rowStartingWith(august, "LOJA DE TESTE");
        assertThat(row.suggestedDescription()).isEqualTo("Loja boa");
        // O texto do arquivo continua intacto — o apelido é só a sugestão.
        assertThat(row.description()).startsWith("LOJA DE TESTE");
    }

    @Test
    void updateItem_applyToHistory_updatesTheSameMerchantAcrossInvoicesOfDifferentMonths() {
        commit(preview());
        commit(faturaImportService.preview(nextMonthFixture(), InvoiceImportFormat.INTER_FATURA_PDF, userId));

        InvoiceItemResponse julyItem = itemStartingWith(
                getInvoiceById(cardAId, FaturaPdfFixture.REFERENCE_MONTH), "LOJA DE TESTE");

        UpdateInvoiceItemResponse response = invoiceManagementService.updateItem(julyItem.id(),
                new UpdateInvoiceItemRequest("Loja boa", null, null, false, true), userId);

        assertThat(response.updatedRelatedItems()).isGreaterThan(0);
        InvoiceItemResponse augustItem = itemStartingWith(
                getInvoiceById(cardAId, FaturaPdfFixture.NEXT_REFERENCE_MONTH), "Loja boa");
        assertThat(augustItem.description()).isEqualTo("Loja boa");
    }

    @Test
    void listInvoices_reportsHowManyItemsCameFromImportVersusManualEntry() {
        commit(preview());

        Page<InvoiceSummaryResponse> invoices = invoiceManagementService.listInvoices(cardAId, userId, 0, 20);

        InvoiceSummaryResponse julyInvoice = invoices.getContent().stream()
                .filter(inv -> inv.referenceMonth().equals(FaturaPdfFixture.REFERENCE_MONTH))
                .findFirst().orElseThrow();
        assertThat(julyInvoice.itemCount()).isGreaterThan(0);
        assertThat(julyInvoice.importedItemCount()).isEqualTo(julyInvoice.itemCount());
    }

    @Test
    void getInvoiceById_returnsTheSameShapeAsGetInvoiceByCardAndMonth() {
        commit(preview());
        InvoiceResponse byCardAndMonth = creditCardService.getInvoice(
                cardAId, FaturaPdfFixture.REFERENCE_MONTH, userId, 0, 50);

        InvoiceResponse byId = invoiceManagementService.getInvoiceById(byCardAndMonth.id(), userId, 0, 50);

        assertThat(byId.id()).isEqualTo(byCardAndMonth.id());
        assertThat(byId.items()).hasSameSizeAs(byCardAndMonth.items());
    }

    @Test
    void settleThenReopen_roundTripsWithoutTouchingTheAccountBalance() {
        commit(preview());
        InvoiceResponse invoice = creditCardService.getInvoice(cardAId, FaturaPdfFixture.REFERENCE_MONTH, userId, 0, 1);

        InvoiceResponse settled = invoiceManagementService.settle(invoice.id(), userId);
        assertThat(settled.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(settled.paidWithoutTransaction()).isTrue();
        assertThat(accountService.computeBalance(accountId, userId)).isEqualByComparingTo("0.00");

        InvoiceResponse reopened = invoiceManagementService.reopen(invoice.id(), userId);
        assertThat(reopened.status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(reopened.paidWithoutTransaction()).isFalse();
        assertThat(reopened.paidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void reopen_refusesAnInvoicePaidThroughARealPayment() {
        commit(preview());
        InvoiceResponse invoice = creditCardService.getInvoice(cardAId, FaturaPdfFixture.REFERENCE_MONTH, userId, 0, 1);
        // Precisa estar fechada para aceitar pagamento — CLOSED nunca é produzido pelo sistema
        // (nada transiciona uma fatura para lá hoje), então o teste move o status à mão pela
        // mesma entidade gerenciada, como PartialPaymentTest.setupClosedInvoice já faz.
        var invoiceEntity = invoiceRepository.findByCreditCard_IdAndReferenceMonth(
                cardAId, FaturaPdfFixture.REFERENCE_MONTH).orElseThrow();
        invoiceEntity.setStatus(InvoiceStatus.CLOSED);
        invoiceRepository.save(invoiceEntity);

        creditCardService.payInvoice(invoice.id(),
                new PayInvoiceRequest(invoice.totalAmount(), accountId, null), userId);

        assertThatThrownBy(() -> invoiceManagementService.reopen(invoice.id(), userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Exclua a transação");
    }

    @Test
    void getMerchantScope_countsTheOtherItemsOfTheSameMerchant() {
        commit(preview());
        commit(faturaImportService.preview(nextMonthFixture(), InvoiceImportFormat.INTER_FATURA_PDF, userId));
        InvoiceItemResponse julyItem = itemStartingWith(
                getInvoiceById(cardAId, FaturaPdfFixture.REFERENCE_MONTH), "LOJA DE TESTE");

        MerchantScopeResponse scope = invoiceManagementService.getMerchantScope(julyItem.id(), userId);

        assertThat(scope.relatedItemCount()).isGreaterThan(0);
        assertThat(scope.originalDescription()).startsWith("LOJA DE TESTE");
    }

    // ── apoio ─────────────────────────────────────────────────────────────────

    private UUID createCard(String name, String last4) {
        return creditCardService.createCard(new CreateCardRequest(
                name, CardBrand.MASTERCARD, "Banco Inter", last4,
                new BigDecimal("8400.00"), 28, 7, null), userId).id();
    }

    private FaturaImportPreviewResponse preview() {
        return faturaImportService.preview(fixture(), InvoiceImportFormat.INTER_FATURA_PDF, userId);
    }

    private void commit(FaturaImportPreviewResponse preview) {
        List<FaturaImportCommitRow> approved = new ArrayList<>();
        for (FaturaImportGroupPreview group : preview.groups()) {
            for (FaturaImportPreviewRow row : group.rows()) {
                approved.add(new FaturaImportCommitRow(
                        row.lineNumber(), group.suggestedCreditCardId(), group.cardLast4(),
                        row.externalRef(), row.ordinal(), row.date(), row.description(),
                        row.description(), row.amount(), row.installmentNumber(),
                        row.totalInstallments(), row.suggestedCategoryId()));
            }
        }
        faturaImportService.commit(new FaturaImportCommitRequest(
                InvoiceImportFormat.INTER_FATURA_PDF, preview.referenceMonth(), accountId, approved),
                userId);
    }

    private InvoiceResponse getInvoiceById(UUID cardId, String referenceMonth) {
        InvoiceResponse byCardAndMonth = creditCardService.getInvoice(cardId, referenceMonth, userId, 0, 50);
        return invoiceManagementService.getInvoiceById(byCardAndMonth.id(), userId, 0, 50);
    }

    private InvoiceItemResponse itemStartingWith(InvoiceResponse invoice, String prefix) {
        return invoice.items().stream()
                .filter(item -> item.description().startsWith(prefix))
                .findFirst().orElseThrow();
    }

    private FaturaImportPreviewRow rowStartingWith(FaturaImportPreviewResponse preview, String prefix) {
        return preview.groups().stream()
                .flatMap(group -> group.rows().stream())
                .filter(row -> row.description().startsWith(prefix))
                .findFirst().orElseThrow();
    }

    private MultipartFile fixture() {
        return new MockMultipartFile(
                "file", "fatura-sintetica.pdf", "application/pdf", FaturaPdfFixture.bytes());
    }

    private MultipartFile nextMonthFixture() {
        return new MockMultipartFile("file", "fatura-sintetica-agosto.pdf", "application/pdf",
                FaturaPdfFixture.nextMonthBytes());
    }
}
