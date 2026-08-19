package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.MerchantAlias;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.ImportCommitRequest;
import com.cashcontrol.api.dto.request.ImportCommitRow;
import com.cashcontrol.api.dto.response.ImportPreviewResponse;
import com.cashcontrol.api.dto.response.ImportPreviewRow;
import com.cashcontrol.api.dto.response.ImportResultResponse;
import com.cashcontrol.api.dto.response.SuggestionSource;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.MerchantAliasRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.CategoryRuleMatcher;
import com.cashcontrol.api.service.CategorySuggester;
import com.cashcontrol.api.service.MerchantAliasService;
import com.cashcontrol.api.service.StatementImportServiceImpl;
import com.cashcontrol.api.service.statement.InterCsvStatementParser;
import com.cashcontrol.api.service.statement.StatementHistoryMapper;
import com.cashcontrol.api.service.statement.StatementRowHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementImportServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryRuleRepository categoryRuleRepository;
    @Mock private MerchantAliasRepository merchantAliasRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;

    private StatementImportServiceImpl service;

    private UUID userId;
    private UUID accountId;
    private Account account;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setName("Conta Corrente");
        account.setType(AccountType.CHECKING);
        account.setCurrencyCode("BRL");

        // Parser, mapeador, hasher e matcher são lógica pura sem dependência: usá-los
        // de verdade testa a integração entre eles, que é justamente o que o import faz.
        service = new StatementImportServiceImpl(
                transactionRepository,
                accountRepository,
                categoryRepository,
                categoryRuleRepository,
                paymentMethodRepository,
                new CategorySuggester(transactionRepository, new CategoryRuleMatcher()),
                new MerchantAliasService(merchantAliasRepository),
                new StatementHistoryMapper(),
                new StatementRowHasher(),
                List.of(new InterCsvStatementParser()),
                new AppProperties());
    }

    // ── preview ───────────────────────────────────────────────────────────────

    @Test
    void preview_classifiesEveryRow_andReportsUnreadableOnes() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(preview.fileName()).isEqualTo("extrato-inter.csv");
        assertThat(preview.format()).isEqualTo(StatementFormat.INTER_CSV);
        assertThat(preview.sourceAccountLabel()).isEqualTo("123456789");
        assertThat(preview.periodStart()).isEqualTo(LocalDate.of(2024, 8, 6));
        assertThat(preview.totalRows()).isEqualTo(17);
        assertThat(preview.importableCount()).isEqualTo(17);
        assertThat(preview.duplicateCount()).isZero();
        assertThat(preview.errors()).hasSize(2);
    }

    @Test
    void preview_mapsHistoryToTypeAndPaymentMethod() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        ImportPreviewRow pixSent = rowWithDescription(preview, "Pix Marketplace");
        assertThat(pixSent.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(pixSent.paymentMethod()).isEqualTo(PaymentMethodSlug.PIX);
        // O valor sai do arquivo com sinal e chega ao domínio sempre positivo.
        assertThat(pixSent.amount()).isEqualByComparingTo("144.06");
        assertThat(pixSent.unknownHistory()).isFalse();
    }

    @Test
    void preview_flagsUnknownHistoryForReview() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(preview.warningCount()).isEqualTo(1);
        assertThat(rowWithDescription(preview, "Tarifa Cesta B"))
                .satisfies(row -> {
                    assertThat(row.unknownHistory()).isTrue();
                    assertThat(row.type()).isEqualTo(TransactionType.INCOME);
                    assertThat(row.paymentMethod()).isEqualTo(PaymentMethodSlug.OTHER);
                });
    }

    @Test
    void preview_suggestsCategoryFromUserRules() {
        givenAccountFound();
        givenNoExistingImports();

        Category food = category("Alimentação");
        CategoryRule rule = new CategoryRule();
        rule.setUserId(userId);
        rule.setPattern("Cafe Do Ponto");
        rule.setCategory(food);
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(rule));

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(preview.rows())
                .filteredOn(row -> row.description().startsWith("Cafe Do Ponto"))
                .isNotEmpty()
                .allSatisfy(row -> {
                    assertThat(row.suggestedCategoryId()).isEqualTo(food.getId());
                    assertThat(row.suggestedCategoryName()).isEqualTo("Alimentação");
                });
        assertThat(rowWithDescription(preview, "Pix Marketplace").suggestedCategoryId()).isNull();
    }

    @Test
    void preview_suggestsFromHistoryWhenNoRuleMatchesTheRow() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();
        Category market = category("Mercado");
        when(transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(eq(userId), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"pix marketplace", market.getId(), market.getName(), null, null, 2L}));

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        ImportPreviewRow row = rowWithDescription(preview, "Pix Marketplace");
        assertThat(row.suggestedCategoryId()).isEqualTo(market.getId());
        assertThat(row.suggestionSource()).isEqualTo(SuggestionSource.HISTORY);
    }

    @Test
    void preview_prefersTheRuleOverTheHistoryForTheSameRow() {
        givenAccountFound();
        givenNoExistingImports();
        Category food = category("Alimentação");
        Category market = category("Mercado");
        CategoryRule rule = new CategoryRule();
        rule.setUserId(userId);
        rule.setPattern("Cafe Do Ponto");
        rule.setCategory(food);
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(List.of(rule));
        when(transactionRepository.findCategoryHistoryByMerchantKeysOrTokenPattern(eq(userId), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{"cafe do ponto", market.getId(), market.getName(), null, null, 5L}));

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(preview.rows())
                .filteredOn(row -> row.description().startsWith("Cafe Do Ponto"))
                .isNotEmpty()
                .allSatisfy(row -> {
                    assertThat(row.suggestedCategoryId()).isEqualTo(food.getId());
                    assertThat(row.suggestionSource()).isEqualTo(SuggestionSource.RULE);
                });
    }

    @Test
    void preview_leavesTheSuggestionAsNoneWhenNeitherARuleNorHistoryMatch() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(rowWithDescription(preview, "Pix Marketplace")).satisfies(row -> {
            assertThat(row.suggestedCategoryId()).isNull();
            assertThat(row.suggestionSource()).isEqualTo(SuggestionSource.NONE);
        });
    }

    @Test
    void preview_exposesTheMerchantKeyOfEachRow() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        ImportPreviewRow row = rowWithDescription(preview, "Pix Marketplace");
        assertThat(row.merchantKey()).isEqualTo(com.cashcontrol.api.service.MerchantKey.of(row.description()));
    }

    @Test
    void preview_prefillsTheDescriptionTheUserChoseForThatMerchantBefore() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();
        MerchantAlias alias = new MerchantAlias();
        alias.setUserId(userId);
        alias.setMerchantKey(com.cashcontrol.api.service.MerchantKey.of("Pix Marketplace"));
        alias.setDisplayName("Marketplace - assinatura");
        alias.setUpdatedAt(Instant.now());
        when(merchantAliasRepository.findAllByUserId(userId)).thenReturn(List.of(alias));

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        ImportPreviewRow row = rowWithDescription(preview, "Pix Marketplace");
        // O texto do arquivo continua na descrição; o apelido vem ao lado, para a tela poder
        // mostrar os dois.
        assertThat(row.suggestedDescription()).isEqualTo("Marketplace - assinatura");
        assertThat(row.description()).isEqualTo("Pix Marketplace");
    }

    @Test
    void preview_leavesTheDescriptionSuggestionEmptyForAMerchantNeverRenamed() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();
        when(merchantAliasRepository.findAllByUserId(userId)).thenReturn(List.of());

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(rowWithDescription(preview, "Pix Marketplace").suggestedDescription()).isNull();
    }

    @Test
    void commit_remembersTheDescriptionTheUserRewroteOnTheRow() {
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();

        service.commit(request(new ImportCommitRow(1, "ref-1", LocalDate.parse("2026-08-04"),
                "Marketplace - assinatura", "Pix Marketplace", new BigDecimal("144.06"),
                TransactionType.EXPENSE, PaymentMethodSlug.PIX, null)), userId);

        ArgumentCaptor<MerchantAlias> saved = ArgumentCaptor.forClass(MerchantAlias.class);
        verify(merchantAliasRepository).save(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Marketplace - assinatura");
        assertThat(saved.getValue().getMerchantKey())
                .isEqualTo(com.cashcontrol.api.service.MerchantKey.of("Pix Marketplace"));
    }

    @Test
    void commit_withoutTheOriginalDescriptionRemembersNothing() {
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();

        // Cliente antigo, que não manda o campo: sem saber o que a descrição substitui, a
        // única leitura segura é que nada foi renomeado.
        service.commit(request(new ImportCommitRow(1, "ref-1", LocalDate.parse("2026-08-04"),
                "Marketplace - assinatura", null, new BigDecimal("144.06"),
                TransactionType.EXPENSE, PaymentMethodSlug.PIX, null)), userId);

        verify(merchantAliasRepository, never()).save(any());
    }

    @Test
    void preview_marksRowsAlreadyImportedInThisAccount() {
        givenAccountFound();
        givenNoCategoryRules();

        ArgumentCaptor<List<String>> refs = ArgumentCaptor.captor();
        when(transactionRepository.findExistingExternalRefs(eq(userId), eq(accountId), anyList()))
                .thenAnswer(inv -> {
                    List<String> asked = inv.getArgument(2);
                    return List.of(asked.get(0), asked.get(1));
                });

        ImportPreviewResponse preview = service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        assertThat(preview.duplicateCount()).isEqualTo(2);
        assertThat(preview.importableCount()).isEqualTo(preview.totalRows() - 2);
        assertThat(preview.rows().get(0).duplicate()).isTrue();
        assertThat(preview.rows().get(2).duplicate()).isFalse();

        // Uma consulta para o arquivo inteiro, não uma por linha.
        verify(transactionRepository).findExistingExternalRefs(eq(userId), eq(accountId), refs.capture());
        assertThat(refs.getValue()).hasSize(17);
    }

    @Test
    void preview_persistsNothing() {
        givenAccountFound();
        givenNoExistingImports();
        givenNoCategoryRules();

        service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId);

        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).saveAll(anyList());
    }

    @Test
    void preview_accountOfAnotherUser_isNotFound() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void preview_archivedAccount_isRejected() {
        account.setArchivedAt(Instant.now());
        givenAccountFound();

        assertThatThrownBy(() -> service.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("arquivada");
    }

    @Test
    void preview_emptyFile_isRejected() {
        givenAccountFound();
        MultipartFile empty = new MockMultipartFile("file", "vazio.csv", "text/csv", new byte[0]);

        assertThatThrownBy(() -> service.preview(empty, StatementFormat.INTER_CSV, accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Envie um arquivo");
    }

    @Test
    void preview_fileAboveTheSizeLimit_isRejected() {
        givenAccountFound();
        AppProperties properties = new AppProperties();
        properties.getStatementImport().setMaxFileSizeMb(1);

        MultipartFile huge = new MockMultipartFile("file", "grande.csv", "text/csv", new byte[2 * 1024 * 1024]);

        assertThatThrownBy(() -> serviceWith(properties)
                .preview(huge, StatementFormat.INTER_CSV, accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("tamanho máximo");
    }

    @Test
    void preview_aboveTheRowLimit_isRejected() {
        givenAccountFound();
        AppProperties properties = new AppProperties();
        properties.getStatementImport().setMaxRows(5);

        assertThatThrownBy(() -> serviceWith(properties)
                .preview(fixture(), StatementFormat.INTER_CSV, accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("períodos menores");
    }

    @Test
    void preview_unsupportedFormat_isRejected() {
        givenAccountFound();
        StatementImportServiceImpl withoutParsers = new StatementImportServiceImpl(
                transactionRepository, accountRepository, categoryRepository, categoryRuleRepository,
                paymentMethodRepository, new CategorySuggester(transactionRepository, new CategoryRuleMatcher()),
                new MerchantAliasService(merchantAliasRepository),
                new StatementHistoryMapper(), new StatementRowHasher(), List.of(), new AppProperties());

        assertThatThrownBy(() -> withoutParsers.preview(fixture(), StatementFormat.INTER_CSV, accountId, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não suportado");
    }

    // ── commit ────────────────────────────────────────────────────────────────

    @Test
    void commit_savesApprovedRowsAsPaidTransactions() {
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();

        ImportResultResponse result = service.commit(request(
                commitRow("ref-1", "2026-08-04", "Pix Marketplace", "144.06",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, null)), userId);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isZero();
        assertThat(result.failed()).isZero();

        Transaction saved = captureSaved().get(0);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAccount()).isSameAs(account);
        assertThat(saved.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(saved.getAmount()).isEqualByComparingTo("144.06");
        assertThat(saved.getExternalRef()).isEqualTo("ref-1");
        // Extrato é fato consumado: competência e pagamento na data do lançamento.
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PAID);
        assertThat(saved.getCompetenceDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(saved.getPaymentDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(saved.getPaymentMethod().getSlug()).isEqualTo(PaymentMethodSlug.PIX);
    }

    @Test
    void commit_skipsRowsAlreadyImported() {
        givenAccountFound();
        givenPaymentMethods();
        when(transactionRepository.findExistingExternalRefs(eq(userId), eq(accountId), anyList()))
                .thenReturn(List.of("ref-1"));

        ImportResultResponse result = service.commit(request(
                commitRow("ref-1", "2026-08-04", "Já importada", "10.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, null),
                commitRow("ref-2", "2026-08-04", "Nova", "20.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, null)), userId);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isEqualTo(1);
        assertThat(captureSaved()).singleElement()
                .satisfies(tx -> assertThat(tx.getExternalRef()).isEqualTo("ref-2"));
    }

    @Test
    void commit_skipsRowsRepeatedWithinThePayload() {
        // Sem isto, o índice único estouraria no flush com um erro que não diz
        // ao usuário qual linha era.
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();

        ImportResultResponse result = service.commit(request(
                commitRow("ref-1", "2026-08-04", "Uma", "10.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, null),
                commitRow("ref-1", "2026-08-04", "A mesma de novo", "10.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, null)), userId);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isEqualTo(1);
    }

    @Test
    void commit_rejectsTransferAndManualAdjustment_perRow() {
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();

        ImportResultResponse result = service.commit(request(
                commitRow("ref-1", "2026-08-04", "Transferência", "10.00",
                        TransactionType.TRANSFER, PaymentMethodSlug.BANK_TRANSFER, null),
                commitRow("ref-2", "2026-08-04", "Válida", "20.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, null)), userId);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("Tipo não permitido"));
    }

    @Test
    void commit_rejectsCreditCardPaymentMethod_perRow() {
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();

        ImportResultResponse result = service.commit(request(
                commitRow("ref-1", "2026-08-04", "Fatura", "10.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.CREDIT_CARD, null)), userId);

        assertThat(result.imported()).isZero();
        assertThat(result.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("cartão de crédito"));
    }

    @Test
    void commit_rejectsCategoryTheUserCannotSee_perRow() {
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();
        when(categoryRepository.findAllSystemCategories()).thenReturn(Collections.emptyList());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(Collections.emptyList());

        ImportResultResponse result = service.commit(request(
                commitRow("ref-1", "2026-08-04", "Alguma", "10.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, UUID.randomUUID())), userId);

        assertThat(result.imported()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("Category not found"));
    }

    @Test
    void commit_appliesVisibleCategory() {
        givenAccountFound();
        givenNoExistingImports();
        givenPaymentMethods();
        Category food = category("Alimentação");
        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of(food));
        when(categoryRepository.findAllByUserId(userId)).thenReturn(Collections.emptyList());

        service.commit(request(
                commitRow("ref-1", "2026-08-04", "Padaria", "10.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, food.getId())), userId);

        assertThat(captureSaved()).singleElement()
                .satisfies(tx -> assertThat(tx.getCategory()).isSameAs(food));
    }

    @Test
    void commit_archivedAccount_isRejected() {
        account.setArchivedAt(Instant.now());
        givenAccountFound();

        ImportCommitRequest request = request(commitRow("ref-1", "2026-08-04", "Alguma", "10.00",
                TransactionType.EXPENSE, PaymentMethodSlug.PIX, null));

        assertThatThrownBy(() -> service.commit(request, userId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("arquivada");
    }

    @Test
    void commit_paymentMethodNotSeeded_isRejectedPerRow() {
        givenAccountFound();
        givenNoExistingImports();
        when(paymentMethodRepository.findAll()).thenReturn(Collections.emptyList());

        ImportResultResponse result = service.commit(request(
                commitRow("ref-1", "2026-08-04", "Alguma", "10.00",
                        TransactionType.EXPENSE, PaymentMethodSlug.PIX, null)), userId);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("Forma de pagamento não cadastrada"));
    }

    // ── apoio ─────────────────────────────────────────────────────────────────

    private void givenAccountFound() {
        when(accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId))
                .thenReturn(Optional.of(account));
    }

    private void givenNoExistingImports() {
        when(transactionRepository.findExistingExternalRefs(eq(userId), eq(accountId), anyList()))
                .thenReturn(Collections.emptyList());
    }

    private void givenNoCategoryRules() {
        when(categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId))
                .thenReturn(Collections.emptyList());
    }

    private void givenPaymentMethods() {
        when(paymentMethodRepository.findAll()).thenReturn(
                List.of(paymentMethod(PaymentMethodSlug.PIX), paymentMethod(PaymentMethodSlug.BANK_TRANSFER)));
    }

    @SuppressWarnings("unchecked")
    private List<Transaction> captureSaved() {
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.captor();
        verify(transactionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private ImportPreviewRow rowWithDescription(ImportPreviewResponse preview, String description) {
        return preview.rows().stream()
                .filter(row -> row.description().equals(description))
                .findFirst()
                .orElseThrow(() -> new AssertionError("linha não encontrada: " + description));
    }

    private ImportCommitRequest request(ImportCommitRow... rows) {
        return new ImportCommitRequest(accountId, StatementFormat.INTER_CSV, List.of(rows));
    }

    private ImportCommitRow commitRow(String externalRef, String date, String description, String amount,
                                      TransactionType type, PaymentMethodSlug paymentMethod, UUID categoryId) {
        return new ImportCommitRow(1, externalRef, LocalDate.parse(date), description, description,
                new BigDecimal(amount), type, paymentMethod, categoryId);
    }

    private Category category(String name) {
        Category category = new Category();
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        category.setName(name);
        return category;
    }

    private PaymentMethod paymentMethod(PaymentMethodSlug slug) {
        PaymentMethod method = new PaymentMethod();
        ReflectionTestUtils.setField(method, "id", UUID.randomUUID());
        method.setName(slug.name());
        method.setSlug(slug);
        return method;
    }

    private MultipartFile fixture() {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/extrato-inter.csv")) {
            return new MockMultipartFile("file", "extrato-inter.csv", "text/csv", in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mesmo serviço, com limites diferentes dos padrões. */
    private StatementImportServiceImpl serviceWith(AppProperties properties) {
        return new StatementImportServiceImpl(
                transactionRepository,
                accountRepository,
                categoryRepository,
                categoryRuleRepository,
                paymentMethodRepository,
                new CategorySuggester(transactionRepository, new CategoryRuleMatcher()),
                new MerchantAliasService(merchantAliasRepository),
                new StatementHistoryMapper(),
                new StatementRowHasher(),
                List.of(new InterCsvStatementParser()),
                properties);
    }
}
