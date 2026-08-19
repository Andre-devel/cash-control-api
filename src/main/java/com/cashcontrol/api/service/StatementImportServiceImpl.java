package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
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
import com.cashcontrol.api.dto.response.ImportRowError;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.statement.ParsedStatement;
import com.cashcontrol.api.service.statement.ParsedStatementRow;
import com.cashcontrol.api.service.statement.StatementHistoryMapper;
import com.cashcontrol.api.service.statement.StatementParser;
import com.cashcontrol.api.service.statement.StatementRowHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatementImportServiceImpl implements StatementImportService {

    /**
     * Tipos que um extrato de conta corrente pode gerar. TRANSFER e
     * MANUAL_ADJUSTMENT têm endpoints próprios e semântica de valor com sinal —
     * não podem entrar por aqui nem que o cliente peça.
     */
    private static final Set<TransactionType> IMPORTABLE_TYPES =
            Set.of(TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.REFUND);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CategorySuggester categorySuggester;
    private final StatementHistoryMapper historyMapper;
    private final StatementRowHasher rowHasher;
    private final List<StatementParser> parsers;
    private final AppProperties appProperties;

    @Override
    @Transactional(readOnly = true)
    public ImportPreviewResponse preview(MultipartFile file, StatementFormat format, UUID accountId, UUID userId) {
        Account account = requireImportableAccount(accountId, userId);
        validateFile(file);

        ParsedStatement statement = parse(file, format);
        if (statement.rows().size() > appProperties.getStatementImport().getMaxRows()) {
            throw new BusinessRuleException(
                    "O arquivo tem " + statement.rows().size() + " lançamentos e o limite é "
                    + appProperties.getStatementImport().getMaxRows() + ". Exporte o extrato em períodos menores.");
        }

        List<String> hashes = rowHasher.hashAll(statement.rows());
        Set<String> alreadyImported = hashes.isEmpty()
                ? Set.of()
                : new HashSet<>(transactionRepository.findExistingExternalRefs(userId, account.getId(), hashes));

        List<CategoryRule> rules = categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId);
        Map<String, CategorySuggester.Suggestion> history = categorySuggester.loadHistory(
                userId, statement.rows().stream().map(ParsedStatementRow::description).toList());

        List<ImportPreviewRow> rows = new ArrayList<>(statement.rows().size());
        for (int i = 0; i < statement.rows().size(); i++) {
            rows.add(toPreviewRow(statement.rows().get(i), hashes.get(i), alreadyImported, rules, history));
        }

        int duplicateCount = (int) rows.stream().filter(ImportPreviewRow::duplicate).count();
        int warningCount = (int) rows.stream().filter(ImportPreviewRow::unknownHistory).count();

        return new ImportPreviewResponse(
                file.getOriginalFilename(),
                format,
                statement.sourceAccountLabel(),
                statement.periodStart(),
                statement.periodEnd(),
                rows.size(),
                rows.size() - duplicateCount,
                duplicateCount,
                warningCount,
                rows,
                statement.errors());
    }

    @Override
    @Transactional
    public ImportResultResponse commit(ImportCommitRequest request, UUID userId) {
        Account account = requireImportableAccount(request.accountId(), userId);

        if (request.rows().size() > appProperties.getStatementImport().getMaxRows()) {
            throw new BusinessRuleException(
                    "Limite de " + appProperties.getStatementImport().getMaxRows()
                    + " lançamentos por importação excedido.");
        }

        // Duas fontes de duplicata: o que já está no banco e o que veio repetido no
        // próprio payload. Sem a segunda, o índice único estouraria no flush com um
        // erro que não diz ao usuário qual linha era.
        Set<String> blocked = new HashSet<>(transactionRepository.findExistingExternalRefs(
                userId, account.getId(), request.rows().stream().map(ImportCommitRow::externalRef).toList()));

        Map<PaymentMethodSlug, PaymentMethod> paymentMethods = loadPaymentMethods();
        Map<UUID, Category> categories = loadVisibleCategories(request.rows(), userId);

        List<Transaction> toSave = new ArrayList<>();
        List<ImportRowError> errors = new ArrayList<>();
        int skippedDuplicates = 0;

        for (ImportCommitRow row : request.rows()) {
            if (!blocked.add(row.externalRef())) {
                skippedDuplicates++;
                continue;
            }
            try {
                toSave.add(toTransaction(row, account, userId, paymentMethods, categories));
            } catch (BusinessRuleException | ResourceNotFoundException e) {
                errors.add(new ImportRowError(row.lineNumber(), e.getMessage()));
            }
        }

        transactionRepository.saveAll(toSave);

        return new ImportResultResponse(toSave.size(), skippedDuplicates, errors.size(), errors);
    }

    // ── Prévia ────────────────────────────────────────────────────────────────

    private ImportPreviewRow toPreviewRow(ParsedStatementRow row, String externalRef, Set<String> alreadyImported,
                                          List<CategoryRule> rules, Map<String, CategorySuggester.Suggestion> history) {
        StatementHistoryMapper.Mapping mapping = historyMapper.map(row.rawHistory(), row.signedAmount());
        CategorySuggester.Suggestion suggestion = categorySuggester.suggest(row.description(), rules, history);

        return new ImportPreviewRow(
                row.lineNumber(),
                externalRef,
                row.date(),
                truncateDescription(row.description()),
                row.rawHistory().trim(),
                row.signedAmount().abs(),
                mapping.type(),
                mapping.paymentMethod(),
                MerchantKey.of(row.description()),
                suggestion.categoryId(),
                suggestion.categoryName(),
                suggestion.subcategoryId(),
                suggestion.subcategoryName(),
                suggestion.source(),
                alreadyImported.contains(externalRef),
                mapping.unknownHistory());
    }

    /** {@code transactions.description} é VARCHAR(255); extratos raramente chegam perto, mas não é garantido. */
    private String truncateDescription(String description) {
        return description.length() <= 255 ? description : description.substring(0, 255);
    }

    // ── Confirmação ───────────────────────────────────────────────────────────

    private Transaction toTransaction(ImportCommitRow row, Account account, UUID userId,
                                      Map<PaymentMethodSlug, PaymentMethod> paymentMethods,
                                      Map<UUID, Category> categories) {
        if (!IMPORTABLE_TYPES.contains(row.type())) {
            throw new BusinessRuleException(
                    "Tipo não permitido na importação: " + row.type() + ". Use INCOME, EXPENSE ou REFUND.");
        }
        if (row.paymentMethod() == PaymentMethodSlug.CREDIT_CARD) {
            throw new BusinessRuleException(
                    "Extrato de conta corrente não gera lançamento de cartão de crédito.");
        }

        PaymentMethod paymentMethod = paymentMethods.get(row.paymentMethod());
        if (paymentMethod == null) {
            throw new BusinessRuleException("Forma de pagamento não cadastrada: " + row.paymentMethod());
        }

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(row.type());
        tx.setAmount(row.amount());
        tx.setDescription(truncateDescription(row.description()));
        // Extrato só traz fato consumado: a data do lançamento é competência e pagamento.
        tx.setStatus(TransactionStatus.PAID);
        tx.setCompetenceDate(row.date());
        tx.setPaymentDate(row.date());
        tx.setPaymentMethod(paymentMethod);
        tx.setExternalRef(row.externalRef());

        if (row.categoryId() != null) {
            Category category = categories.get(row.categoryId());
            if (category == null) {
                throw new ResourceNotFoundException("Category not found: " + row.categoryId());
            }
            tx.setCategory(category);
        }

        return tx;
    }

    // ── Apoio ─────────────────────────────────────────────────────────────────

    private Account requireImportableAccount(UUID accountId, UUID userId) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Não é possível importar lançamentos em uma conta arquivada.");
        }
        return account;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Envie um arquivo de extrato.");
        }
        long maxBytes = (long) appProperties.getStatementImport().getMaxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessRuleException("O arquivo excede o tamanho máximo permitido de "
                                            + appProperties.getStatementImport().getMaxFileSizeMb() + " MB.");
        }
    }

    private ParsedStatement parse(MultipartFile file, StatementFormat format) {
        StatementParser parser = parsers.stream()
                .filter(p -> p.format() == format)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Formato de extrato não suportado: " + format));

        try (InputStream in = file.getInputStream()) {
            return parser.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<PaymentMethodSlug, PaymentMethod> loadPaymentMethods() {
        Map<PaymentMethodSlug, PaymentMethod> bySlug = new EnumMap<>(PaymentMethodSlug.class);
        for (PaymentMethod method : paymentMethodRepository.findAll()) {
            bySlug.put(method.getSlug(), method);
        }
        return bySlug;
    }

    /**
     * Categorias que o usuário pode usar: as dele e as de sistema. Carregadas de uma vez
     * para não fazer uma consulta por linha — e é aqui que uma categoria de outro usuário
     * enviada no payload deixa de ser encontrada.
     */
    private Map<UUID, Category> loadVisibleCategories(List<ImportCommitRow> rows, UUID userId) {
        boolean anyCategory = rows.stream().anyMatch(row -> row.categoryId() != null);
        if (!anyCategory) {
            return Map.of();
        }
        List<Category> visible = new ArrayList<>(categoryRepository.findAllSystemCategories());
        visible.addAll(categoryRepository.findAllByUserId(userId));
        return visible.stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (a, b) -> a));
    }
}
