package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.CardBrand;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.MerchantAlias;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRow;
import com.cashcontrol.api.dto.response.FaturaImportGroupPreview;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.dto.response.FaturaImportPreviewRow;
import com.cashcontrol.api.dto.response.FaturaImportResultResponse;
import com.cashcontrol.api.dto.response.SuggestionSource;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.MerchantAliasRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.CategoryRuleMatcher;
import com.cashcontrol.api.service.CategorySuggester;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.FaturaImportServiceImpl;
import com.cashcontrol.api.service.InvoiceCycleCalculator;
import com.cashcontrol.api.service.MerchantAliasService;
import com.cashcontrol.api.service.InvoiceCycleCalculator.InvoiceCycleInfo;
import com.cashcontrol.api.service.TransactionService;
import com.cashcontrol.api.service.fatura.FaturaParser;
import com.cashcontrol.api.service.fatura.FaturaRowHasher;
import com.cashcontrol.api.service.fatura.ParsedCardSection;
import com.cashcontrol.api.service.fatura.ParsedFatura;
import com.cashcontrol.api.service.fatura.ParsedFaturaRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FaturaImportServiceTest {

    private static final String REFERENCE_MONTH = "2026-07";

    @Mock private CreditCardRepository creditCardRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceItemRepository invoiceItemRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private InstallmentSeriesRepository installmentSeriesRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryRuleRepository categoryRuleRepository;
    @Mock private MerchantAliasRepository merchantAliasRepository;
    @Mock private CreditCardService creditCardService;
    @Mock private TransactionService transactionService;

    private FaturaImportServiceImpl service;
    private StubFaturaParser parser;

    private UUID userId;
    private Account account;
    private PaymentMethod creditCardMethod;
    private CreditCard cardA;
    private CreditCard cardB;
    private Invoice invoiceA;
    private Invoice invoiceB;
    /** Uma fatura por cartão e mês de referência, criada sob demanda como no serviço real. */
    private Map<String, Invoice> invoices;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        account = account();
        creditCardMethod = new PaymentMethod();
        cardA = card("Inter Titular", "1234");
        cardB = card("Inter Adicional", "5678");
        invoices = new HashMap<>();
        invoiceA = invoice(cardA, REFERENCE_MONTH);
        invoiceB = invoice(cardB, REFERENCE_MONTH);
        parser = new StubFaturaParser();

        // Hasher, matcher e calculadora de ciclo são lógica pura: usá-los de verdade
        // testa a integração entre eles, que é o que o import de fato faz.
        service = new FaturaImportServiceImpl(
                creditCardRepository,
                invoiceRepository,
                invoiceItemRepository,
                accountRepository,
                transactionRepository,
                installmentSeriesRepository,
                categoryRepository,
                categoryRuleRepository,
                new CategorySuggester(transactionRepository, new CategoryRuleMatcher()),
                new MerchantAliasService(merchantAliasRepository),
                creditCardService,
                transactionService,
                new InvoiceCycleCalculator(),
                new FaturaRowHasher(),
                List.of(parser),
                new AppProperties());

        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of());
        when(creditCardRepository.findAllByUserIdAndLast4DigitsAndDeletedAtIsNull(userId, "1234"))
                .thenReturn(List.of(cardA));
        when(creditCardRepository.findAllByUserIdAndLast4DigitsAndDeletedAtIsNull(userId, "5678"))
                .thenReturn(List.of(cardB));
        when(invoiceRepository.findByCreditCard_IdAndReferenceMonth(any(), any()))
                .thenReturn(Optional.empty());
        when(invoiceItemRepository.findExistingExternalRefs(any(), any(), anyList()))
                .thenReturn(List.of());
        when(invoiceItemRepository.findAllByExternalRefIn(any(), any())).thenReturn(List.of());
        when(transactionRepository.findAllByExternalRefIn(any(), any(), any())).thenReturn(List.of());
        when(creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(cardA.getId(), userId))
                .thenReturn(Optional.of(cardA));
        when(creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(cardB.getId(), userId))
                .thenReturn(Optional.of(cardB));
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(account.getId(), userId))
                .thenReturn(Optional.of(account));
        when(transactionService.resolvePaymentMethod(any())).thenReturn(creditCardMethod);
        // O serviço reatribui o retorno do save; devolver null quebraria o vínculo entre
        // a transação e o item de fatura.
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));
        when(installmentSeriesRepository.save(any(InstallmentSeries.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(creditCardService.getOrCreateInvoice(any(), any())).thenAnswer(call -> {
            CreditCard card = call.getArgument(0);
            InvoiceCycleInfo cycle = call.getArgument(1);
            return invoice(card, cycle.referenceMonth());
        });
    }

    // ── preview ───────────────────────────────────────────────────────────────

    @Test
    void preview_splitsTheFaturaIntoOneGroupPerCardSection() {
        FaturaImportPreviewResponse preview = preview();

        assertThat(preview.groups()).extracting(FaturaImportGroupPreview::cardLast4)
                .containsExactly("1234", "5678");
        assertThat(preview.dueDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(preview.referenceMonth()).isEqualTo(REFERENCE_MONTH);
    }

    /** O vencimento é sempre no mês seguinte ao fechamento — é o que define a competência. */
    @Test
    void preview_derivesTheReferenceMonthFromTheDueDate() {
        parser.dueDate = LocalDate.of(2027, 1, 10);

        assertThat(preview().referenceMonth()).isEqualTo("2026-12");
    }

    @Test
    void preview_suggestsTheCardWhoseLast4DigitsMatchTheSection() {
        FaturaImportPreviewResponse preview = preview();

        assertThat(preview.groups().getFirst().suggestedCreditCardId()).isEqualTo(cardA.getId());
        assertThat(preview.groups().getFirst().suggestedCreditCardName()).isEqualTo("Inter Titular");
        assertThat(preview.groups().get(1).suggestedCreditCardId()).isEqualTo(cardB.getId());
    }

    @Test
    void preview_suggestsNothingWhenTwoCardsShareTheSameLast4Digits() {
        when(creditCardRepository.findAllByUserIdAndLast4DigitsAndDeletedAtIsNull(userId, "1234"))
                .thenReturn(List.of(cardA, card("Outro", "1234")));

        // Sugerir o cartão errado é pior do que não sugerir: o usuário escolhe.
        assertThat(preview().groups().getFirst().suggestedCreditCardId()).isNull();
    }

    @Test
    void preview_ignoresArchivedCardsWhenSuggesting() {
        cardA.setArchivedAt(Instant.now());

        assertThat(preview().groups().getFirst().suggestedCreditCardId()).isNull();
    }

    @Test
    void preview_dropsCreditsAndCountsThem() {
        FaturaImportPreviewResponse preview = preview();

        // O pagamento da fatura anterior tem valor positivo: não é despesa e não vira
        // lançamento, mas o usuário precisa saber que ele foi visto e descartado.
        assertThat(preview.excludedPaymentsCount()).isEqualTo(1);
        assertThat(preview.totalRows()).isEqualTo(3);
        assertThat(preview.groups().getFirst().rows()).extracting(FaturaImportPreviewRow::description)
                .doesNotContain("PAGTO DEBITO AUTOMATICO");
    }

    @Test
    void preview_reportsAmountsAsPositiveValues() {
        assertThat(preview().groups().getFirst().rows())
                .allSatisfy(row -> assertThat(row.amount()).isPositive());
    }

    @Test
    void preview_carriesTheInstallmentSuffixOfTheRow() {
        FaturaImportPreviewRow row = preview().groups().getFirst().rows().getFirst();

        assertThat(row.installmentNumber()).isEqualTo(4);
        assertThat(row.totalInstallments()).isEqualTo(5);
    }

    @Test
    void preview_appliesTheUserCategoryRules() {
        Category food = category("Alimentação");
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(rule("loja de teste", food)));

        FaturaImportPreviewRow row = preview().groups().getFirst().rows().getFirst();

        assertThat(row.suggestedCategoryId()).isEqualTo(food.getId());
        assertThat(row.suggestedCategoryName()).isEqualTo("Alimentação");
        assertThat(row.suggestionSource()).isEqualTo(SuggestionSource.RULE);
    }

    @Test
    void preview_suggestsFromHistoryWhenNoRuleMatchesTheRow() {
        Category market = category("Mercado");
        when(transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(eq(userId), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"loja de teste", market.getId(), market.getName(), null, null, 3L}));

        FaturaImportPreviewRow row = preview().groups().getFirst().rows().getFirst();

        assertThat(row.suggestedCategoryId()).isEqualTo(market.getId());
        assertThat(row.suggestionSource()).isEqualTo(SuggestionSource.HISTORY);
    }

    @Test
    void preview_prefersTheRuleOverTheHistoryForTheSameRow() {
        Category food = category("Alimentação");
        Category market = category("Mercado");
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(rule("loja de teste", food)));
        when(transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(eq(userId), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"loja de teste", market.getId(), market.getName(), null, null, 3L}));

        FaturaImportPreviewRow row = preview().groups().getFirst().rows().getFirst();

        assertThat(row.suggestedCategoryId()).isEqualTo(food.getId());
        assertThat(row.suggestionSource()).isEqualTo(SuggestionSource.RULE);
    }

    @Test
    void preview_leavesTheSuggestionAsNoneWhenNeitherARuleNorHistoryMatch() {
        FaturaImportPreviewRow row = preview().groups().getFirst().rows().getFirst();

        assertThat(row.suggestedCategoryId()).isNull();
        assertThat(row.suggestionSource()).isEqualTo(SuggestionSource.NONE);
    }

    @Test
    void preview_exposesTheMerchantKeyOfEachRow() {
        FaturaImportPreviewRow row = preview().groups().getFirst().rows().getFirst();

        // "LOJA DE TESTE (Parcela 04 de 05)" reduzida à identidade do estabelecimento.
        assertThat(row.merchantKey()).isEqualTo(com.cashcontrol.api.service.MerchantKey.of(row.description()));
        assertThat(row.merchantKey()).isEqualTo("loja de teste");
    }

    @Test
    void preview_prefillsTheDescriptionTheUserChoseForThatMerchantBefore() {
        MerchantAlias alias = new MerchantAlias();
        alias.setUserId(userId);
        alias.setMerchantKey("loja de teste");
        alias.setDisplayName("Loja boa");
        alias.setUpdatedAt(Instant.now());
        when(merchantAliasRepository.findAllByUserId(userId)).thenReturn(List.of(alias));

        FaturaImportPreviewRow row = preview().groups().getFirst().rows().getFirst();

        // A descrição continua sendo a do arquivo: as duas vêm juntas para que a tela possa
        // pré-preencher o apelido e ainda assim mostrar o original.
        assertThat(row.suggestedDescription()).isEqualTo("Loja boa");
        assertThat(row.description()).isEqualTo("LOJA DE TESTE (Parcela 04 de 05)");
    }

    @Test
    void preview_leavesTheDescriptionSuggestionEmptyForAMerchantNeverRenamed() {
        when(merchantAliasRepository.findAllByUserId(userId)).thenReturn(List.of());

        assertThat(preview().groups().getFirst().rows().getFirst().suggestedDescription()).isNull();
    }

    @Test
    void commit_remembersTheDescriptionTheUserRewroteOnTheRow() {
        List<FaturaImportCommitRow> rows = commitRowsFromPreview();
        FaturaImportCommitRow first = rows.getFirst();
        rows.set(0, new FaturaImportCommitRow(
                first.lineNumber(), first.creditCardId(), first.cardLast4(), first.externalRef(),
                first.ordinal(), first.date(), "Loja boa", first.originalDescription(),
                first.amount(), first.installmentNumber(), first.totalInstallments(), null));

        commit(rows);

        ArgumentCaptor<MerchantAlias> saved = ArgumentCaptor.forClass(MerchantAlias.class);
        verify(merchantAliasRepository).save(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Loja boa");
        // A chave vem da descrição do PDF, não da que o usuário escreveu — é ela que volta
        // igual no mês que vem.
        assertThat(saved.getValue().getMerchantKey()).isEqualTo("loja de teste");
    }

    @Test
    void commit_doesNotRememberAnythingForRowsLeftAsTheyCame() {
        commit(commitRowsFromPreview());

        verify(merchantAliasRepository, never()).save(any());
    }

    @Test
    void preview_flagsRowsAlreadyImportedIntoThatMonthsInvoice() {
        when(invoiceRepository.findByCreditCard_IdAndReferenceMonth(cardA.getId(), REFERENCE_MONTH))
                .thenReturn(Optional.of(invoiceA));
        List<String> hashes = hashesOfFirstGroup();
        when(invoiceItemRepository.findExistingExternalRefs(userId, invoiceA.getId(), hashes))
                .thenReturn(List.of(hashes.getFirst()));

        FaturaImportPreviewResponse preview = preview();

        assertThat(preview.duplicateCount()).isEqualTo(1);
        assertThat(preview.groups().getFirst().rows().getFirst().duplicate()).isTrue();
        assertThat(preview.groups().getFirst().rows().get(1).duplicate()).isFalse();
    }

    @Test
    void preview_marksNothingAsDuplicateWhenTheInvoiceDoesNotExistYet() {
        assertThat(preview().duplicateCount()).isZero();
        verify(invoiceItemRepository, never()).findExistingExternalRefs(any(), any(), anyList());
    }

    @Test
    void preview_rejectsAnEmptyFile() {
        MultipartFile empty = new MockMultipartFile("file", "fatura.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.preview(empty, InvoiceImportFormat.INTER_FATURA_PDF, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Envie o arquivo PDF");
    }

    @Test
    void preview_rejectsAFileOverTheSizeLimit() {
        AppProperties properties = new AppProperties();
        properties.getInvoiceImport().setMaxFileSizeMb(1);
        ReflectionTestUtils.setField(service, "appProperties", properties);
        MultipartFile huge = new MockMultipartFile(
                "file", "fatura.pdf", "application/pdf", new byte[2 * 1024 * 1024]);

        assertThatThrownBy(() -> service.preview(huge, InvoiceImportFormat.INTER_FATURA_PDF, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("tamanho máximo");
    }

    // ── commit ────────────────────────────────────────────────────────────────

    @Test
    void commit_putsEachRowOfThePdfOnTheInvoiceOfItsOwnCard() {
        FaturaImportResultResponse result = commit(commitRowsFromPreview());

        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.failed()).isZero();

        List<InvoiceItem> onTheFatura = itemsOf(REFERENCE_MONTH);
        assertThat(onTheFatura).filteredOn(item -> item.getInvoice().equals(invoiceA)).hasSize(2);
        assertThat(onTheFatura).filteredOn(item -> item.getInvoice().equals(invoiceB)).hasSize(1);
    }

    /**
     * O ponto da mudança: a compra de cartão passa a existir como transação, do mesmo jeito
     * que existiria se tivesse sido lançada pela tela — e o item de fatura aponta para ela.
     */
    @Test
    void commit_createsATransactionForEachRow_andLinksTheInvoiceItemToIt() {
        commit(commitRowsFromPreview());

        Transaction tx = capturedTransactions().getFirst();
        assertThat(tx.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.getAccount()).isEqualTo(account);
        assertThat(tx.getCreditCard()).isEqualTo(cardA);
        assertThat(tx.getPaymentMethod()).isEqualTo(creditCardMethod);
        assertThat(tx.getUserId()).isEqualTo(userId);

        assertThat(captureSavedItems()).allSatisfy(
                item -> assertThat(item.getTransaction()).isNotNull());
    }

    /**
     * PENDENTE, não PAGA: o saldo da conta soma só transações pagas, e quem tira o dinheiro
     * da conta é o pagamento da fatura. PAGA aqui debitaria a compra duas vezes.
     */
    @Test
    void commit_leavesTheTransactionsPending_soTheAccountBalanceOnlyMovesOnPayment() {
        commit(commitRowsFromPreview());

        assertThat(capturedTransactions()).allSatisfy(tx -> {
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(tx.getPaymentDate()).isNull();
        });
    }

    @Test
    void commit_recordsTheInstallmentPositionAndTheExternalRef() {
        commit(commitRowsFromPreview());

        InvoiceItem first = captureSavedItems().getFirst();
        assertThat(first.getInstallmentNumber()).isEqualTo(4);
        assertThat(first.getTotalInstallments()).isEqualTo(5);
        assertThat(first.getExternalRef()).hasSize(64);
    }

    @Test
    void commit_dropsTheInstallmentSuffixFromTheDescription() {
        commit(commitRowsFromPreview());

        // A posição virou coluna; manter o sufixo faria toda parcela gerada mentir a
        // própria posição ("Parcela 04 de 05" na parcela 5).
        assertThat(capturedTransactions()).extracting(Transaction::getDescription)
                .contains("LOJA DE TESTE")
                .doesNotContain("LOJA DE TESTE (Parcela 04 de 05)");
    }

    // ── commit: parcelamento ──────────────────────────────────────────────────

    @Test
    void commit_createsASeriesForAParceledRow_butNotForAPlainOne() {
        commit(commitRowsFromPreview());

        // Duas linhas parceladas no PDF: "Parcela 04 de 05" e "Parcela 01 de 10".
        verify(installmentSeriesRepository, org.mockito.Mockito.times(2))
                .save(any(InstallmentSeries.class));
        assertThat(capturedTransactions())
                .filteredOn(tx -> tx.getDescription().equals("ASSINATURA MENSAL"))
                .allSatisfy(tx -> {
                    assertThat(tx.getInstallmentSeries()).isNull();
                    assertThat(tx.getInstallmentNumber()).isNull();
                });
    }

    /**
     * A série declara o total da compra (5 de 5, como no PDF) mas só o valor que ainda vai
     * ser lançado: as parcelas anteriores à do PDF não existem no sistema.
     */
    @Test
    void commit_seriesCoversOnlyTheInstallmentsStillToCome() {
        commit(commitRowsFromPreview());

        InstallmentSeries series = capturedSeries().getFirst();
        assertThat(series.getTotalInstallments()).isEqualTo(5);
        assertThat(series.getTotalAmount()).isEqualByComparingTo("110.38"); // 55,19 × 2
        assertThat(series.getFirstPaymentDate()).isEqualTo(LocalDate.of(2026, 7, 4));
        assertThat(series.getCreditCard()).isEqualTo(cardA);
        assertThat(series.getAccount()).isEqualTo(account);
    }

    @Test
    void commit_generatesTheRemainingInstallmentsOnTheFollowingInvoices() {
        FaturaImportResultResponse result = commit(commitRowsFromPreview());

        // A parcela 5 de 5 e as parcelas 2 a 10 de 10.
        assertThat(result.futureInstallments()).isEqualTo(10);
        assertThat(itemsOf("2026-08")).extracting(InvoiceItem::getInstallmentNumber)
                .containsExactlyInAnyOrder(5, 2);
        assertThat(itemsOf("2027-04")).singleElement()
                .satisfies(item -> assertThat(item.getInstallmentNumber()).isEqualTo(10));
    }

    /** Sem backfill: a parcela 4 de 5 não recria as parcelas 1 a 3 em faturas passadas. */
    @Test
    void commit_neverTouchesTheInvoicesBeforeTheOneInThePdf() {
        commit(commitRowsFromPreview());

        assertThat(captureSavedItems())
                .allSatisfy(item -> assertThat(item.getInvoice().getReferenceMonth())
                        .isGreaterThanOrEqualTo(REFERENCE_MONTH));
    }

    /**
     * A garantia que fecha o ciclo: a parcela 5 gerada agora nasce com a chave que a linha
     * "Parcela 05 de 05" vai produzir quando o PDF do mês seguinte for importado.
     */
    @Test
    void commit_generatedInstallmentCarriesTheKeyOfTheRowThatWillComeNextMonth() {
        commit(commitRowsFromPreview());

        String expected = new FaturaRowHasher().hashInstallment("1234", LocalDate.of(2026, 4, 4),
                "LOJA DE TESTE (Parcela 05 de 05)", 5, 5, 0);

        assertThat(itemsOf("2026-08"))
                .filteredOn(item -> item.getInstallmentNumber() == 5)
                .singleElement()
                .satisfies(item -> assertThat(item.getExternalRef()).isEqualTo(expected));
    }

    @Test
    void commit_shiftsTheCompetenceOfEachInstallmentByOneMonth() {
        commit(commitRowsFromPreview());

        // A fatura repete a data da compra em toda parcela; usá-la crua empilharia as cinco
        // no mesmo mês. A parcela 4 de uma compra de 04/04 tem competência 04/07.
        assertThat(captureSavedItems())
                .filteredOn(item -> item.getExternalRef().equals(
                        commitRowsFromPreview().getFirst().externalRef()))
                .singleElement()
                .satisfies(item -> assertThat(item.getCompetenceDate())
                        .isEqualTo(LocalDate.of(2026, 7, 4)));
    }

    @Test
    void commit_keepsTheChargeDateOnARowThatIsNotParceled() {
        commit(commitRowsFromPreview());

        assertThat(capturedTransactions())
                .filteredOn(tx -> tx.getDescription().equals("ASSINATURA MENSAL"))
                .singleElement()
                .satisfies(tx -> assertThat(tx.getCompetenceDate())
                        .isEqualTo(LocalDate.of(2026, 7, 15)));
    }

    /**
     * O usuário pode reescrever "SHOPEE *LarkSpComercio" para "Fone de ouvido" na prévia. A
     * descrição nova vai para o lançamento, mas a chave da parcela seguinte tem de continuar
     * saindo da original — o PDF do mês que vem não sabe nada do nome que ele escolheu.
     */
    @Test
    void commit_derivesTheNextInstallmentKeyFromTheOriginalDescription_notTheEditedOne() {
        FaturaImportCommitRow parceled = commitRowsFromPreview().getFirst();
        FaturaImportCommitRow renamed = new FaturaImportCommitRow(
                parceled.lineNumber(), parceled.creditCardId(), parceled.cardLast4(),
                parceled.externalRef(), parceled.ordinal(), parceled.date(), "Fone de ouvido",
                parceled.originalDescription(), parceled.amount(),
                parceled.installmentNumber(), parceled.totalInstallments(), null);

        commit(List.of(renamed));

        String expected = new FaturaRowHasher().hashInstallment("1234", LocalDate.of(2026, 4, 4),
                "LOJA DE TESTE (Parcela 05 de 05)", 5, 5, 0);

        assertThat(itemsOf("2026-08")).singleElement().satisfies(item -> {
            assertThat(item.getExternalRef()).isEqualTo(expected);
            // O nome escolhido vale para as duas parcelas: é o mesmo lançamento.
            assertThat(item.getDescription()).isEqualTo("Fone de ouvido");
        });
    }

    @Test
    void commit_skipsAGeneratedInstallmentThatAnEarlierImportAlreadyCreated() {
        String alreadyThere = new FaturaRowHasher().hashInstallment("1234", LocalDate.of(2026, 4, 4),
                "LOJA DE TESTE (Parcela 05 de 05)", 5, 5, 0);
        when(transactionRepository.findAllByExternalRefIn(eq(userId), eq(account.getId()), any()))
                .thenReturn(List.of(existingCharge(alreadyThere, "55.19")));

        FaturaImportResultResponse result = commit(List.of(commitRowsFromPreview().getFirst()));

        // A linha do PDF entra; a parcela 5 não é recriada. E não conta como duplicata do
        // arquivo, porque não veio de linha nenhuma dele.
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.futureInstallments()).isZero();
        assertThat(result.skippedDuplicates()).isZero();
    }

    // ── commit: a compra cujas parcelas caem todas na mesma fatura ────────────

    /**
     * O caso que motivou o agrupamento por compra. O emissor estorna a compra e relança as
     * três parcelas na fatura do mês — todas com a data da compra e o mesmo valor:
     *
     * <pre>
     * 10 de abr. 2026   EBN *TikTok Shop (Parcela 01 de 03)      R$ 41,94
     * 10 de abr. 2026   EBN *TikTok Shop                       + R$ 125,82   (estorno)
     * 10 de abr. 2026   EBN *TikTok Shop (Parcela 02 de 03)      R$ 41,94
     * 10 de abr. 2026   EBN *TikTok Shop (Parcela 03 de 03)      R$ 41,94
     * </pre>
     *
     * <p>Tratando linha a linha, a "Parcela 01 de 03" gerava as parcelas 2 e 3 nos meses
     * seguintes com exatamente as chaves que as outras duas linhas do arquivo produzem — duas
     * transações com o mesmo {@code external_ref} na mesma conta, recusadas pelo índice único
     * no flush, com um 500 que derrubava a importação inteira.
     */
    @Test
    void commit_doesNotGenerateAnInstallmentThatTheFileItselfBrings() {
        parser.sections = List.of(new ParsedCardSection("1234", installmentsOfTheSamePurchase()));

        FaturaImportResultResponse result = commit(commitRowsFromPreview());

        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.futureInstallments()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(capturedTransactions()).extracting(Transaction::getExternalRef).doesNotHaveDuplicates();
    }

    /** E as três ficam na fatura do PDF, que é quem cobra — não espalhadas por três meses. */
    @Test
    void commit_keepsEveryInstallmentOfTheFileOnTheInvoiceThePdfDescribes() {
        parser.sections = List.of(new ParsedCardSection("1234", installmentsOfTheSamePurchase()));

        commit(commitRowsFromPreview());

        assertThat(captureSavedItems())
                .hasSize(3)
                .allSatisfy(item -> {
                    assertThat(item.getInvoice().getReferenceMonth()).isEqualTo(REFERENCE_MONTH);
                    // A competência acompanha a fatura de destino, não o número da parcela:
                    // 07/2026 é o mês que está cobrando as três.
                    assertThat(item.getCompetenceDate()).isEqualTo(LocalDate.of(2026, 7, 10));
                })
                .extracting(InvoiceItem::getInstallmentNumber)
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    /**
     * Uma série, não três. Uma por linha mostraria três parcelamentos de uma parcela cada na
     * tela de Parcelamentos, no lugar de um de três.
     */
    @Test
    void commit_createsOneSeriesForAPurchaseThatCameAsSeveralRows() {
        parser.sections = List.of(new ParsedCardSection("1234", installmentsOfTheSamePurchase()));

        commit(commitRowsFromPreview());

        assertThat(capturedSeries()).singleElement().satisfies(series -> {
            assertThat(series.getTotalInstallments()).isEqualTo(3);
            assertThat(series.getTotalAmount()).isEqualByComparingTo("125.82"); // 41,94 × 3
        });
        assertThat(capturedTransactions())
                .allSatisfy(tx -> assertThat(tx.getInstallmentSeries()).isNotNull());
    }

    /**
     * Duas compras iguais no mesmo dia continuam sendo duas: o ordinal que a prévia devolveu
     * é o que as separa, agora que o valor saiu da identidade das parceladas.
     */
    @Test
    void commit_separatesTwoPurchasesThatDifferOnlyByAmount() {
        parser.sections = List.of(new ParsedCardSection("1234", List.of(
                new ParsedFaturaRow(1, LocalDate.of(2026, 3, 2),
                        "MERCADOLIVRE*2PRODUTO (Parcela 02 de 03)", new BigDecimal("-85.11"), 2, 3),
                new ParsedFaturaRow(2, LocalDate.of(2026, 3, 2),
                        "MERCADOLIVRE*2PRODUTO (Parcela 02 de 03)", new BigDecimal("-70.74"), 2, 3))));

        FaturaImportResultResponse result = commit(commitRowsFromPreview());

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skippedDuplicates()).isZero();
        // Uma parcela 3 para cada compra, com chaves diferentes.
        assertThat(result.futureInstallments()).isEqualTo(2);
        assertThat(capturedTransactions()).extracting(Transaction::getExternalRef).doesNotHaveDuplicates();
        assertThat(capturedSeries()).hasSize(2);
    }

    // ── commit: cobrança que já existe na conta ───────────────────────────────

    /**
     * O alcance da checagem é a conta, igual ao do índice único de {@code transactions} — e
     * não a fatura. Uma parcela gerada por uma importação anterior mora na fatura do mês
     * dela; procurar só na fatura que o PDF descreve deixava a chave passar e o insert
     * estourava no flush.
     */
    @Test
    void commit_skipsAChargeThatAlreadyExistsOnAnotherInvoiceOfTheSameAccount() {
        List<FaturaImportCommitRow> rows = commitRowsFromPreview();
        Transaction elsewhere = existingCharge(rows.getFirst().externalRef(), "55.19");
        when(transactionRepository.findAllByExternalRefIn(eq(userId), eq(account.getId()), any()))
                .thenReturn(List.of(elsewhere));
        when(invoiceItemRepository.findAllByExternalRefIn(eq(userId), any()))
                .thenReturn(List.of(existingItem(elsewhere, invoiceB)));

        FaturaImportResultResponse result = commit(rows);

        assertThat(result.failed()).isZero();
        assertThat(result.skippedDuplicates()).isEqualTo(1);
        assertThat(capturedTransactions()).extracting(Transaction::getExternalRef)
                .doesNotContain(rows.getFirst().externalRef());
    }

    /**
     * A parcela estimada no mês passado recebe o valor real quando o PDF chega com ele. O
     * emissor deixa o resto da divisão na primeira parcela — 48,28 e depois 48,26 —, então a
     * estimativa nasce alguns centavos acima.
     */
    @Test
    void commit_correctsTheAmountOfAnInstallmentThatAnEarlierImportEstimated() {
        parser.sections = List.of(new ParsedCardSection("1234", List.of(
                new ParsedFaturaRow(1, LocalDate.of(2026, 3, 30),
                        "EBN *TikTok Shop (Parcela 02 de 03)", new BigDecimal("-48.26"), 2, 3))));
        List<FaturaImportCommitRow> rows = commitRowsFromPreview();
        Transaction estimated = existingCharge(rows.getFirst().externalRef(), "48.28");
        InvoiceItem estimatedItem = existingItem(estimated, invoiceA);
        invoiceA.setTotalAmount(new BigDecimal("100.00"));
        when(transactionRepository.findAllByExternalRefIn(eq(userId), eq(account.getId()), any()))
                .thenReturn(List.of(estimated));
        when(invoiceItemRepository.findAllByExternalRefIn(eq(userId), any()))
                .thenReturn(List.of(estimatedItem));

        FaturaImportResultResponse result = commit(rows);

        assertThat(result.skippedDuplicates()).isEqualTo(1);
        assertThat(estimated.getAmount()).isEqualByComparingTo("48.26");
        assertThat(estimatedItem.getAmount()).isEqualByComparingTo("48.26");
        // A fatura que a estava cobrando encolhe os mesmos dois centavos.
        assertThat(invoiceA.getTotalAmount()).isEqualByComparingTo("99.98");
    }

    /** As três linhas de uma mesma compra, todas na fatura do PDF, como o emissor as lançou. */
    private List<ParsedFaturaRow> installmentsOfTheSamePurchase() {
        return List.of(
                new ParsedFaturaRow(1, LocalDate.of(2026, 7, 10),
                        "EBN *TikTok Shop (Parcela 01 de 03)", new BigDecimal("-41.94"), 1, 3),
                new ParsedFaturaRow(2, LocalDate.of(2026, 7, 10),
                        "EBN *TikTok Shop", new BigDecimal("125.82"), null, null),
                new ParsedFaturaRow(3, LocalDate.of(2026, 7, 10),
                        "EBN *TikTok Shop (Parcela 02 de 03)", new BigDecimal("-41.94"), 2, 3),
                new ParsedFaturaRow(4, LocalDate.of(2026, 7, 10),
                        "EBN *TikTok Shop (Parcela 03 de 03)", new BigDecimal("-41.94"), 3, 3));
    }

    @Test
    void commit_addsTheImportedAmountToEachInvoiceTotal() {
        invoiceA.setTotalAmount(new BigDecimal("10.00"));

        commit(commitRowsFromPreview());

        // 10,00 já lançados + 55,19 + 110,00 do PDF.
        assertThat(invoiceA.getTotalAmount()).isEqualByComparingTo("175.19");
        assertThat(invoiceB.getTotalAmount()).isEqualByComparingTo("336.81");
        verify(invoiceRepository).save(invoiceA);
        verify(invoiceRepository).save(invoiceB);
    }

    @Test
    void commit_skipsRowsAlreadyOnTheInvoice() {
        List<FaturaImportCommitRow> rows = commitRowsFromPreview();
        when(transactionRepository.findAllByExternalRefIn(eq(userId), eq(account.getId()), any()))
                .thenReturn(List.of(existingCharge(rows.getFirst().externalRef(), "55.19")));

        FaturaImportResultResponse result = commit(rows);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skippedDuplicates()).isEqualTo(1);
        assertThat(invoiceA.getTotalAmount()).isEqualByComparingTo("110.00");
    }

    @Test
    void commit_skipsARowRepeatedInsideThePayloadItself() {
        List<FaturaImportCommitRow> rows = new ArrayList<>(commitRowsFromPreview());
        rows.add(rows.getFirst());

        // Sem essa checagem o índice único estouraria no flush, com um erro que não diz
        // ao usuário qual linha era.
        FaturaImportResultResponse result = commit(rows);

        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.skippedDuplicates()).isEqualTo(1);
    }

    @Test
    void commit_importsIntoAPartiallyPaidInvoiceWithoutInventingPayment() {
        invoiceA.setPaidAmount(new BigDecimal("50.00"));
        invoiceA.setStatus(InvoiceStatus.PARTIAL);

        FaturaImportResultResponse result = commit(commitRowsFromPreview());

        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.failed()).isZero();
        // O que falta pagar é real: o total cresce e o pago fica onde estava.
        assertThat(invoiceA.getTotalAmount()).isEqualByComparingTo("165.19");
        assertThat(invoiceA.getPaidAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void commit_keepsAFullyPaidInvoiceSettledWhenNewRowsArrive() {
        // Importar o mês seguinte de um histórico já quitado não pode deixar a fatura
        // PAGA devendo a diferença.
        invoiceA.setTotalAmount(new BigDecimal("10.00"));
        invoiceA.setPaidAmount(new BigDecimal("10.00"));
        invoiceA.setStatus(InvoiceStatus.PAID);

        FaturaImportResultResponse result = commit(commitRowsFromPreview());

        assertThat(result.failed()).isZero();
        assertThat(invoiceA.getTotalAmount()).isEqualByComparingTo("175.19");
        assertThat(invoiceA.getPaidAmount()).isEqualByComparingTo("175.19");
        assertThat(invoiceA.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void commit_refusesAnArchivedCard() {
        // Arquivado depois da prévia: o usuário pode ter arquivado o cartão entre uma
        // etapa e outra, e a confirmação é quem tem de barrar.
        List<FaturaImportCommitRow> rows = commitRowsFromPreview();
        cardA.setArchivedAt(Instant.now());

        FaturaImportResultResponse result = commit(rows);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.errors()).extracting(error -> error.message())
                .allSatisfy(message -> assertThat(message).contains("arquivados"));
    }

    @Test
    void commit_refusesACardThatIsNotTheUsers() {
        UUID foreignCardId = UUID.randomUUID();
        when(creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(foreignCardId, userId))
                .thenReturn(Optional.empty());

        FaturaImportResultResponse result = commit(List.of(
                commitRow(foreignCardId, "ref-1", "Compra alheia", "10.00")));

        assertThat(result.imported()).isZero();
        assertThat(result.errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).contains("Credit card not found"));
    }

    @Test
    void commit_refusesACategoryTheUserCannotSee() {
        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of());

        FaturaImportCommitRow row = new FaturaImportCommitRow(
                1, cardA.getId(), "1234", "ref-1", 0, LocalDate.of(2026, 7, 15), "Compra", "Compra",
                new BigDecimal("10.00"), null, null, UUID.randomUUID());

        FaturaImportResultResponse result = commit(List.of(row));

        assertThat(result.imported()).isZero();
        assertThat(result.errors()).singleElement()
                .satisfies(error -> assertThat(error.message()).contains("Category not found"));
        // Rejeitada antes de qualquer gravação: uma linha inválida não pode deixar
        // transação órfã na mesma transação de banco.
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void commit_rejectsAMalformedReferenceMonth() {
        FaturaImportCommitRequest request = new FaturaImportCommitRequest(
                InvoiceImportFormat.INTER_FATURA_PDF, "julho/2026", account.getId(),
                commitRowsFromPreview());

        assertThatThrownBy(() -> service.commit(request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Mês de referência inválido");
    }

    @Test
    void commit_rejectsAnArchivedAccount() {
        account.setArchivedAt(Instant.now());

        // Diferente do cartão, a conta derruba o arquivo inteiro: ela é uma só para todos
        // os cartões, então não há o que continuar.
        assertThatThrownBy(() -> commit(commitRowsFromPreview()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("conta arquivada");
    }

    @Test
    void commit_rejectsAnAccountThatIsNotTheUsers() {
        UUID foreign = UUID.randomUUID();
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(foreign, userId))
                .thenReturn(Optional.empty());

        FaturaImportCommitRequest request = new FaturaImportCommitRequest(
                InvoiceImportFormat.INTER_FATURA_PDF, REFERENCE_MONTH, foreign,
                commitRowsFromPreview());

        assertThatThrownBy(() -> service.commit(request, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── apoio ─────────────────────────────────────────────────────────────────

    private FaturaImportPreviewResponse preview() {
        return service.preview(pdf(), InvoiceImportFormat.INTER_FATURA_PDF, userId);
    }

    private FaturaImportResultResponse commit(List<FaturaImportCommitRow> rows) {
        return service.commit(new FaturaImportCommitRequest(
                InvoiceImportFormat.INTER_FATURA_PDF, REFERENCE_MONTH, account.getId(), rows), userId);
    }

    /** A prévia é a fonte das linhas da confirmação, como no cliente real. */
    private List<FaturaImportCommitRow> commitRowsFromPreview() {
        FaturaImportPreviewResponse preview = preview();
        List<FaturaImportCommitRow> rows = new ArrayList<>();
        for (FaturaImportGroupPreview group : preview.groups()) {
            for (FaturaImportPreviewRow row : group.rows()) {
                rows.add(new FaturaImportCommitRow(
                        row.lineNumber(), group.suggestedCreditCardId(), group.cardLast4(),
                        row.externalRef(), row.ordinal(), row.date(), row.description(),
                        row.description(), row.amount(), row.installmentNumber(),
                        row.totalInstallments(), null));
            }
        }
        return rows;
    }

    private FaturaImportCommitRow commitRow(UUID cardId, String externalRef, String description, String amount) {
        return new FaturaImportCommitRow(1, cardId, "1234", externalRef, 0, LocalDate.of(2026, 7, 15),
                description, description, new BigDecimal(amount), null, null, null);
    }

    private List<String> hashesOfFirstGroup() {
        return new FaturaRowHasher().hashAll("1234", parser.parse(null).cardSections().getFirst().rows()
                .stream().filter(row -> row.signedAmount().signum() < 0).toList())
                .stream().map(FaturaRowHasher.RowKey::externalRef).toList();
    }

    /** Uma cobrança que já ocupa a chave na conta, como se viesse de uma importação anterior. */
    private Transaction existingCharge(String externalRef, String amount) {
        Transaction tx = new Transaction();
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setExternalRef(externalRef);
        tx.setAmount(new BigDecimal(amount));
        return tx;
    }

    private InvoiceItem existingItem(Transaction tx, Invoice invoice) {
        InvoiceItem item = new InvoiceItem();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        item.setUserId(userId);
        item.setInvoice(invoice);
        item.setTransaction(tx);
        item.setExternalRef(tx.getExternalRef());
        item.setAmount(tx.getAmount());
        return item;
    }

    private List<InvoiceItem> captureSavedItems() {
        ArgumentCaptor<InvoiceItem> captor = ArgumentCaptor.forClass(InvoiceItem.class);
        verify(invoiceItemRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    /** Os itens gravados na fatura de um mês, sejam eles linha do PDF ou parcela gerada. */
    private List<InvoiceItem> itemsOf(String referenceMonth) {
        return captureSavedItems().stream()
                .filter(item -> item.getInvoice().getReferenceMonth().equals(referenceMonth))
                .toList();
    }

    private List<Transaction> capturedTransactions() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private List<InstallmentSeries> capturedSeries() {
        ArgumentCaptor<InstallmentSeries> captor = ArgumentCaptor.forClass(InstallmentSeries.class);
        verify(installmentSeriesRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private MultipartFile pdf() {
        return new MockMultipartFile("file", "fatura.pdf", "application/pdf", "%PDF-fake".getBytes());
    }

    private CreditCard card(String name, String last4) {
        CreditCard card = new CreditCard();
        ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
        card.setUserId(userId);
        card.setName(name);
        card.setBrand(CardBrand.MASTERCARD);
        card.setLast4Digits(last4);
        card.setCreditLimit(new BigDecimal("5000.00"));
        card.setClosingDay(28);
        card.setDueDay(7);
        return card;
    }

    private Account account() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setUserId(userId);
        account.setName("Conta Corrente");
        return account;
    }

    /**
     * Memoizada por cartão e mês: a confirmação pede a fatura de vários meses para gerar as
     * parcelas seguintes, e devolver uma instância nova a cada chamada faria o total da
     * mesma fatura ser somado em objetos diferentes.
     */
    private Invoice invoice(CreditCard card, String referenceMonth) {
        return invoices.computeIfAbsent(card.getId() + "|" + referenceMonth, key -> {
            Invoice invoice = new Invoice();
            ReflectionTestUtils.setField(invoice, "id", UUID.randomUUID());
            invoice.setUserId(userId);
            invoice.setCreditCard(card);
            invoice.setStatus(InvoiceStatus.OPEN);
            invoice.setReferenceMonth(referenceMonth);
            invoice.setClosingDate(LocalDate.of(2026, 7, 28));
            invoice.setDueDate(LocalDate.of(2026, 8, 7));
            return invoice;
        });
    }

    private Category category(String name) {
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        category.setName(name);
        return category;
    }

    private CategoryRule rule(String pattern, Category category) {
        CategoryRule rule = new CategoryRule();
        rule.setPattern(pattern);
        rule.setCategory(category);
        return rule;
    }

    /**
     * Devolve sempre a mesma fatura lida, com duas seções de cartão e uma linha de
     * crédito. Assim o teste do serviço não depende de um PDF binário — a leitura do
     * PDF é coberta em {@link InterFaturaPdfParserTest}.
     */
    private static final class StubFaturaParser implements FaturaParser {

        private LocalDate dueDate = LocalDate.of(2026, 8, 7);

        /** Null usa a fatura padrão; um teste que precisa de outra forma substitui as seções. */
        private List<ParsedCardSection> sections;

        @Override
        public InvoiceImportFormat format() {
            return InvoiceImportFormat.INTER_FATURA_PDF;
        }

        @Override
        public ParsedFatura parse(InputStream in) {
            return new ParsedFatura(dueDate, new BigDecimal("502.00"),
                    sections != null ? sections : defaultSections(), List.of());
        }

        private List<ParsedCardSection> defaultSections() {
            return List.of(
                    new ParsedCardSection("1234", List.of(
                            new ParsedFaturaRow(6, LocalDate.of(2026, 4, 4),
                                    "LOJA DE TESTE (Parcela 04 de 05)", new BigDecimal("-55.19"), 4, 5),
                            new ParsedFaturaRow(7, LocalDate.of(2026, 7, 7),
                                    "PAGTO DEBITO AUTOMATICO", new BigDecimal("500.00"), null, null),
                            new ParsedFaturaRow(8, LocalDate.of(2026, 7, 15),
                                    "ASSINATURA MENSAL", new BigDecimal("-110.00"), null, null))),
                    new ParsedCardSection("5678", List.of(
                            new ParsedFaturaRow(12, LocalDate.of(2026, 7, 24),
                                    "OUTRA LOJA (Parcela 01 de 10)", new BigDecimal("-336.81"), 1, 10))));
        }
    }
}
