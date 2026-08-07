package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.CreateCategoryRuleRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRow;
import com.cashcontrol.api.dto.response.CategoryResponse;
import com.cashcontrol.api.dto.response.FaturaImportGroupPreview;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.dto.response.FaturaImportPreviewRow;
import com.cashcontrol.api.dto.response.FaturaImportResultResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.CategoryService;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.FaturaImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

/**
 * Importação de fatura de ponta a ponta contra um Postgres real, a partir de um PDF
 * de verdade: é aqui que o índice único de {@code external_ref} em
 * {@code invoice_items} e o comportamento de reimportação são de fato exercidos.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class FaturaImportIntegrationTest {

    @Autowired private FaturaImportService faturaImportService;
    @Autowired private CreditCardService creditCardService;
    @Autowired private AccountService accountService;
    @Autowired private CategoryService categoryService;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private TransactionRepository transactionRepository;
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
                "fatura-import-" + UUID.randomUUID() + "@example.com");

        accountId = accountService.createAccount(
                new CreateAccountRequest("Conta Inter", AccountType.CHECKING, "BRL", null, 0, null),
                userId).id();

        // Fechamento 28 e vencimento 7: a fatura de 2026-07 vence em 07/08/2026, que é
        // exatamente o vencimento impresso no PDF sintético.
        cardAId = createCard("Inter Titular", FaturaPdfFixture.CARD_A_LAST4);
        cardBId = createCard("Inter Adicional", FaturaPdfFixture.CARD_B_LAST4);
    }

    @Test
    void preview_groupsTheChargesByCardAndSuggestsTheRegisteredCards() {
        FaturaImportPreviewResponse preview = preview();

        assertThat(preview.referenceMonth()).isEqualTo(FaturaPdfFixture.REFERENCE_MONTH);
        assertThat(preview.groups()).hasSize(2);
        assertThat(preview.groups()).extracting(FaturaImportGroupPreview::suggestedCreditCardId)
                .containsExactly(cardAId, cardBId);
        // Três despesas; o pagamento de valor positivo fica de fora.
        assertThat(preview.totalRows()).isEqualTo(3);
        assertThat(preview.excludedPaymentsCount()).isEqualTo(1);
        assertThat(preview.duplicateCount()).isZero();
        assertThat(preview.errors()).isEmpty();
    }

    @Test
    void previewThenCommit_landsEachCardsChargesOnItsOwnInvoice() {
        FaturaImportResultResponse result = commit(preview());

        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.failed()).isZero();

        assertThat(invoiceOf(cardAId).totalAmount()).isEqualByComparingTo(FaturaPdfFixture.CARD_A_TOTAL);
        assertThat(invoiceOf(cardBId).totalAmount()).isEqualByComparingTo(FaturaPdfFixture.CARD_B_TOTAL);
        assertThat(invoiceOf(cardAId).items()).hasSize(2);
        assertThat(invoiceOf(cardBId).items()).hasSize(1);
    }

    @Test
    void commit_opensTheInvoiceOfTheReferenceMonthWhenItDoesNotExistYet() {
        // O PDF de julho pode chegar antes de qualquer lançamento do mês ter sido
        // registrado no sistema.
        assertThat(invoiceRepository.findByCreditCard_IdAndReferenceMonth(
                cardAId, FaturaPdfFixture.REFERENCE_MONTH)).isEmpty();

        commit(preview());

        assertThat(invoiceOf(cardAId).dueDate()).isEqualTo("2026-08-07");
        assertThat(invoiceOf(cardAId).closingDate()).isEqualTo("2026-07-28");
    }

    @Test
    void reimportingTheSameFatura_isANoOp() {
        commit(preview());

        // Segunda passada do mesmo PDF: a prévia já sabe que tudo entrou...
        FaturaImportPreviewResponse second = preview();
        assertThat(second.duplicateCount()).isEqualTo(3);

        // ...e a confirmação, mesmo mandando tudo de novo, não grava nada.
        FaturaImportResultResponse result = commit(second);
        assertThat(result.imported()).isZero();
        assertThat(result.skippedDuplicates()).isEqualTo(3);
        assertThat(invoiceOf(cardAId).totalAmount()).isEqualByComparingTo(FaturaPdfFixture.CARD_A_TOTAL);
        assertThat(invoiceOf(cardAId).items()).hasSize(2);
    }

    @Test
    void importedItems_keepTheChargeDateAndTheInstallmentPosition() {
        commit(preview());

        assertThat(invoiceOf(cardBId).items()).singleElement().satisfies(item -> {
            // O sufixo saiu da descrição: a posição da parcela virou coluna própria.
            assertThat(item.description()).isEqualTo("OUTRA LOJA");
            assertThat(item.competenceDate()).isEqualTo("2026-07-24");
            assertThat(item.installmentNumber()).isEqualTo(1);
            assertThat(item.totalInstallments()).isEqualTo(10);
        });
    }

    /**
     * A despesa de cartão passa a existir como transação, do mesmo jeito que existiria se
     * tivesse sido lançada pela tela — é o que faz a compra aparecer na lista de transações
     * e o parcelamento na tela de Parcelamentos.
     */
    @Test
    void commit_createsPendingCreditCardTransactionsLinkedToTheInvoiceItems() {
        commit(preview());

        List<Transaction> transactions = transactionRepository.findAll().stream()
                .filter(tx -> tx.getUserId().equals(userId))
                .toList();

        assertThat(transactions).isNotEmpty().allSatisfy(tx -> {
            assertThat(tx.getType()).isEqualTo(TransactionType.EXPENSE);
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(tx.getCreditCard()).isNotNull();
            assertThat(tx.getAccount().getId()).isEqualTo(accountId);
        });
        assertThat(invoiceOf(cardAId).items())
                .allSatisfy(item -> assertThat(item.transactionId()).isNotNull());
    }

    /** Pendente não move saldo: quem tira dinheiro da conta é o pagamento da fatura. */
    @Test
    void commit_doesNotMoveTheAccountBalance() {
        commit(preview());

        assertThat(accountService.computeBalance(accountId, userId)).isEqualByComparingTo("0.00");
    }

    @Test
    void commit_generatesTheRemainingInstallmentsOnTheFollowingInvoices() {
        FaturaImportResultResponse result = commit(preview());

        // Parcela 5 de 5 do cartão A e parcelas 2 a 10 de 10 do cartão B.
        assertThat(result.futureInstallments()).isEqualTo(10);

        InvoiceResponse august = creditCardService.getInvoice(
                cardAId, FaturaPdfFixture.NEXT_REFERENCE_MONTH, userId, 0, 50);
        assertThat(august.totalAmount()).isEqualByComparingTo("55.19");
        assertThat(august.items()).singleElement().satisfies(item -> {
            assertThat(item.description()).isEqualTo("LOJA DE TESTE");
            assertThat(item.installmentNumber()).isEqualTo(5);
        });
    }

    /** Sem backfill: a parcela 4 de 5 não recria as parcelas 1 a 3 em faturas passadas. */
    @Test
    void commit_neverCreatesTheInstallmentsBeforeTheOneInThePdf() {
        commit(preview());

        assertThat(invoiceRepository.findAll().stream()
                .filter(invoice -> invoice.getUserId().equals(userId))
                .map(invoice -> invoice.getReferenceMonth()))
                .allSatisfy(month -> assertThat(month)
                        .isGreaterThanOrEqualTo(FaturaPdfFixture.REFERENCE_MONTH));
    }

    /**
     * O caso que fecha o ciclo do uso mensal: importar a fatura de agosto encontra a
     * parcela 5 que a importação de julho já tinha gerado, em vez de duplicá-la.
     */
    @Test
    void importingTheNextFatura_recognisesTheInstallmentItHadAlreadyGenerated() {
        commit(preview());

        FaturaImportPreviewResponse august = faturaImportService.preview(
                nextMonthFixture(), InvoiceImportFormat.INTER_FATURA_PDF, userId);

        assertThat(august.referenceMonth()).isEqualTo(FaturaPdfFixture.NEXT_REFERENCE_MONTH);
        assertThat(august.duplicateCount()).isEqualTo(1);
        assertThat(august.groups().getFirst().rows())
                .filteredOn(row -> row.installmentNumber() != null)
                .singleElement()
                .satisfies(row -> assertThat(row.duplicate()).isTrue());

        FaturaImportResultResponse result = commitRows(august, august.groups());

        // Só a compra nova entra; a parcela já estava lá e o total bate com o PDF.
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isEqualTo(1);
        assertThat(creditCardService.getInvoice(
                cardAId, FaturaPdfFixture.NEXT_REFERENCE_MONTH, userId, 0, 50).totalAmount())
                .isEqualByComparingTo("97.19");
    }

    @Test
    void preview_appliesTheUserCategoryRules_andCommitPersistsThem() {
        CategoryResponse food = categoryService.listCategories(userId, false, false).stream()
                .filter(category -> category.name().equals("Alimentação"))
                .findFirst()
                .orElseThrow();
        categoryService.createRule(
                new CreateCategoryRuleRequest("loja de teste", food.id(), null, null, 0), userId);

        FaturaImportPreviewResponse preview = preview();
        assertThat(preview.groups().getFirst().rows().getFirst().suggestedCategoryId())
                .isEqualTo(food.id());

        commit(preview);

        assertThat(invoiceOf(cardAId).items())
                .filteredOn(item -> item.description().startsWith("LOJA DE TESTE"))
                .singleElement()
                .satisfies(item -> assertThat(item.categoryId()).isEqualTo(food.id()));
    }

    @Test
    void commit_ofOneGroupOnly_leavesTheOtherCardImportable() {
        FaturaImportPreviewResponse preview = preview();
        FaturaImportGroupPreview first = preview.groups().getFirst();

        assertThat(commitRows(preview, List.of(first)).imported()).isEqualTo(2);

        // O segundo cartão continua sem duplicata na próxima passada.
        FaturaImportPreviewResponse second = preview();
        assertThat(second.groups().getFirst().rows()).allMatch(FaturaImportPreviewRow::duplicate);
        assertThat(second.groups().get(1).rows()).noneMatch(FaturaImportPreviewRow::duplicate);
    }

    @Test
    void preview_suggestsNothingWhenNoRegisteredCardMatches() {
        UUID otherUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, account_status_id, auth_origin_id, credentials_updated_at) " +
                "VALUES (?, " +
                "  (SELECT id FROM account_statuses WHERE slug = 'ACTIVE'), " +
                "  (SELECT id FROM auth_origins WHERE slug = 'LOCAL'), " +
                "  NOW()) " +
                "RETURNING id",
                UUID.class,
                "fatura-import-outro-" + UUID.randomUUID() + "@example.com");

        // Os cartões do PDF são de outro usuário: nada casa, e o cliente é quem terá de
        // pedir a escolha do cartão.
        FaturaImportPreviewResponse preview = faturaImportService.preview(
                fixture(), InvoiceImportFormat.INTER_FATURA_PDF, otherUserId);

        assertThat(preview.groups()).extracting(FaturaImportGroupPreview::suggestedCreditCardId)
                .containsOnlyNulls();
        assertThat(preview.totalRows()).isEqualTo(3);
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

    private FaturaImportResultResponse commit(FaturaImportPreviewResponse preview) {
        return commitRows(preview, preview.groups());
    }

    private FaturaImportResultResponse commitRows(FaturaImportPreviewResponse preview,
                                                  List<FaturaImportGroupPreview> groups) {
        List<FaturaImportCommitRow> approved = new ArrayList<>();
        for (FaturaImportGroupPreview group : groups) {
            for (FaturaImportPreviewRow row : group.rows()) {
                approved.add(new FaturaImportCommitRow(
                        row.lineNumber(), group.suggestedCreditCardId(), group.cardLast4(),
                        row.externalRef(), row.date(), row.description(), row.description(),
                        row.amount(), row.installmentNumber(), row.totalInstallments(),
                        row.suggestedCategoryId()));
            }
        }
        return faturaImportService.commit(new FaturaImportCommitRequest(
                InvoiceImportFormat.INTER_FATURA_PDF, preview.referenceMonth(), accountId, approved),
                userId);
    }

    private InvoiceResponse invoiceOf(UUID cardId) {
        return creditCardService.getInvoice(cardId, FaturaPdfFixture.REFERENCE_MONTH, userId, 0, 50);
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
