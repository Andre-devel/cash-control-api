package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
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
import com.cashcontrol.api.dto.request.ManualAdjustmentRequest;
import com.cashcontrol.api.dto.response.ImportInvoiceOption;
import com.cashcontrol.api.dto.response.ImportPreviewResponse;
import com.cashcontrol.api.dto.response.ImportPreviewRow;
import com.cashcontrol.api.dto.response.ImportResultResponse;
import com.cashcontrol.api.dto.response.ImportRowError;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.statement.ParsedStatement;
import com.cashcontrol.api.service.statement.ParsedStatementRow;
import com.cashcontrol.api.service.statement.StatementHistoryMapper;
import com.cashcontrol.api.service.statement.StatementParser;
import com.cashcontrol.api.service.statement.InvoicePaymentMatcher;
import com.cashcontrol.api.service.statement.StatementRowHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
     * Tipos que viram uma transação simples na conta importada. TRANSFER e INVOICE_PAYMENT
     * também entram, mas pelos fluxos próprios ({@link #importTransfer},
     * {@link #importInvoicePayment}); MANUAL_ADJUSTMENT nunca.
     */
    private static final Set<TransactionType> IMPORTABLE_TYPES =
            Set.of(TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.REFUND);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final InvoiceRepository invoiceRepository;
    private final AccountService accountService;
    private final CreditCardService creditCardService;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CategorySuggester categorySuggester;
    private final MerchantAliasService merchantAliasService;
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
        CategorySuggester.History history = categorySuggester.loadHistory(
                userId, statement.rows().stream().map(ParsedStatementRow::description).toList());
        MerchantAliasService.Aliases aliases = merchantAliasService.load(userId);
        Account investmentAccount = findInvestmentAccount(account, userId);
        InvoicePaymentMatcher invoices = new InvoicePaymentMatcher(loadInvoiceCandidates(statement.rows(), userId));

        List<ImportPreviewRow> rows = new ArrayList<>(statement.rows().size());
        for (int i = 0; i < statement.rows().size(); i++) {
            rows.add(toPreviewRow(statement.rows().get(i), hashes.get(i), alreadyImported, rules, history, aliases,
                    investmentAccount, invoices));
        }

        int duplicateCount = (int) rows.stream().filter(ImportPreviewRow::duplicate).count();
        int warningCount = (int) rows.stream()
                .filter(row -> row.unknownHistory() || row.invoiceAmountMismatch() || row.invoicePaymentUnmatched())
                .count();

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
                statement.errors(),
                investmentAccount == null && rows.stream().anyMatch(ImportPreviewRow::investment),
                rows.stream().anyMatch(row -> !row.duplicate() && row.date().isBefore(createdOn(account))));
    }

    private static LocalDate createdOn(Account account) {
        return account.getCreatedAt() != null
                ? LocalDate.ofInstant(account.getCreatedAt(), ZoneId.systemDefault())
                : LocalDate.now();
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
        int importedByOwnFlow = 0;
        BigDecimal netChange = BigDecimal.ZERO;
        LocalDate earliest = null;

        for (ImportCommitRow row : request.rows()) {
            // Antes do desvio de duplicata de propósito: o nome que o usuário deixou na
            // linha é decisão dele sobre o estabelecimento, tenha a linha virado lançamento
            // novo ou não.
            merchantAliasService.remember(userId, originalDescriptionOf(row), row.description());
            if (!blocked.add(row.externalRef())) {
                skippedDuplicates++;
                continue;
            }
            try {
                switch (row.type()) {
                    case TRANSFER -> importTransfer(row, account, userId);
                    case INVOICE_PAYMENT -> importInvoicePayment(row, account, userId);
                    default -> toSave.add(toTransaction(row, account, userId, paymentMethods, categories));
                }
                if (row.type() == TransactionType.TRANSFER || row.type() == TransactionType.INVOICE_PAYMENT) {
                    importedByOwnFlow++;
                }
                netChange = netChange.add(balanceEffect(row));
                earliest = earliest == null || row.date().isBefore(earliest) ? row.date() : earliest;
            } catch (BusinessRuleException | ResourceNotFoundException e) {
                errors.add(new ImportRowError(row.lineNumber(), e.getMessage()));
            }
        }

        transactionRepository.saveAll(toSave);

        if (Boolean.TRUE.equals(request.keepCurrentBalance()) && netChange.signum() != 0) {
            // O histórico importado já está no saldo da conta: compensa-o antes do primeiro
            // lançamento, para o saldo atual (e o de cada dia depois disso) ficar como estava.
            accountService.manualAdjustment(account.getId(), new ManualAdjustmentRequest(
                    netChange.negate(), "Saldo de abertura (importação de extrato)", earliest.minusDays(1)), userId);
        }

        return new ImportResultResponse(toSave.size() + importedByOwnFlow, skippedDuplicates, errors.size(), errors);
    }

    // ── Prévia ────────────────────────────────────────────────────────────────

    private ImportPreviewRow toPreviewRow(ParsedStatementRow row, String externalRef, Set<String> alreadyImported,
                                          List<CategoryRule> rules, CategorySuggester.History history,
                                          MerchantAliasService.Aliases aliases, Account investmentAccount,
                                          InvoicePaymentMatcher invoices) {
        StatementHistoryMapper.Mapping mapping = historyMapper.map(row.rawHistory(), row.signedAmount());
        CategorySuggester.Suggestion suggestion = categorySuggester.suggest(row.description(), rules, history);
        String alias = merchantAliasService.suggest(row.description(), aliases);
        BigDecimal amount = row.signedAmount().abs();

        // Aplicação, resgate e pagamento de fatura movem dinheiro sem ser gasto nem renda.
        // Como despesa/receita inflariam o dashboard (e a fatura contaria em dobro, já que as
        // compras dela são despesas). Sem conta de investimento ou fatura que case, fica o
        // que o histórico diz — e o usuário troca na prévia.
        TransactionType type = mapping.type();
        boolean outgoing = row.signedAmount().signum() < 0;
        Account transferAccount = null;
        Invoice invoice = null;
        boolean invoiceAmountMismatch = false;
        boolean invoicePaymentUnmatched = false;
        if (mapping.investment() && investmentAccount != null) {
            type = TransactionType.TRANSFER;
            transferAccount = investmentAccount;
        } else if (type == TransactionType.EXPENSE && looksLikeInvoicePayment(row.description())) {
            invoice = invoices.claim(row.date(), amount);
            invoicePaymentUnmatched = invoice == null;
            if (invoice != null) {
                // Valor fora da tolerância é sinal de dado errado na fatura ou de outro
                // pagamento; casar mesmo assim deixaria a fatura PARCIAL e criaria um
                // rotativo que talvez não exista. Fica a sugestão, a decisão é do usuário.
                invoiceAmountMismatch = !InvoicePaymentMatcher.amountMatches(invoice, amount);
                if (!invoiceAmountMismatch) {
                    type = TransactionType.INVOICE_PAYMENT;
                }
            }
        }
        List<ImportInvoiceOption> invoiceOptions = outgoing
                ? invoices.optionsFor(row.date()).stream().map(StatementImportServiceImpl::toInvoiceOption).toList()
                : List.of();

        return new ImportPreviewRow(
                row.lineNumber(),
                externalRef,
                row.date(),
                truncateDescription(row.description()),
                row.rawHistory().trim(),
                amount,
                type,
                mapping.paymentMethod(),
                MerchantKey.of(row.description()),
                alias != null ? truncateDescription(alias) : null,
                suggestion.categoryId(),
                suggestion.categoryName(),
                suggestion.subcategoryId(),
                suggestion.subcategoryName(),
                suggestion.source(),
                alreadyImported.contains(externalRef),
                mapping.unknownHistory(),
                outgoing,
                transferAccount != null ? transferAccount.getId() : null,
                transferAccount != null ? transferAccount.getName() : null,
                invoice != null ? invoice.getId() : null,
                invoice != null ? invoiceLabel(invoice) : null,
                invoiceAmountMismatch,
                invoiceOptions,
                mapping.investment(),
                invoicePaymentUnmatched);
    }

    private static ImportInvoiceOption toInvoiceOption(Invoice invoice) {
        return new ImportInvoiceOption(invoice.getId(), invoiceLabel(invoice), invoice.getTotalAmount(),
                invoice.getDueDate());
    }

    private static boolean looksLikeInvoicePayment(String description) {
        return StatementHistoryMapper.normalize(description).contains("fatura");
    }

    private static String invoiceLabel(Invoice invoice) {
        return invoice.getCreditCard().getName() + " · " + invoice.getReferenceMonth();
    }

    /**
     * A primeira conta de investimento ativa do usuário, na ordem da tela de contas. Com mais
     * de uma, a escolha é dessa ordem; o usuário que quiser outra muda a ordem das contas.
     */
    private Account findInvestmentAccount(Account importedAccount, UUID userId) {
        return accountRepository
                .findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId)
                .stream()
                .filter(candidate -> candidate.getType() == AccountType.INVESTMENT)
                .filter(candidate -> !candidate.getId().equals(importedAccount.getId()))
                .findFirst()
                .orElse(null);
    }

    private List<Invoice> loadInvoiceCandidates(List<ParsedStatementRow> rows, UUID userId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        LocalDate first = rows.stream().map(ParsedStatementRow::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate last = rows.stream().map(ParsedStatementRow::date).max(LocalDate::compareTo).orElseThrow();
        return invoiceRepository.findAllWithCardByUserIdAndDueDateBetween(userId,
                first.minusDays(InvoicePaymentMatcher.DUE_DAYS_BEFORE_PAYMENT), last.plusDays(InvoicePaymentMatcher.DUE_DAYS_AFTER_PAYMENT));
    }

    /**
     * A descrição como o arquivo a trouxe.
     *
     * <p>O campo é opcional no payload: um cliente que não o envie está dizendo, na prática,
     * que não renomeou nada — e tratar a descrição enviada como original faz
     * {@code MerchantAliasService.remember} não gravar apelido nenhum, que é o que se quer.
     */
    private static String originalDescriptionOf(ImportCommitRow row) {
        return row.originalDescription() != null ? row.originalDescription() : row.description();
    }

    /** {@code transactions.description} é VARCHAR(255); extratos raramente chegam perto, mas não é garantido. */
    private String truncateDescription(String description) {
        return description.length() <= 255 ? description : description.substring(0, 255);
    }

    /** Quanto a linha muda o saldo da conta importada. */
    private static BigDecimal balanceEffect(ImportCommitRow row) {
        return switch (row.type()) {
            case INCOME, REFUND -> row.amount();
            case TRANSFER -> Boolean.TRUE.equals(row.outgoing()) ? row.amount().negate() : row.amount();
            default -> row.amount().negate();
        };
    }

    // ── Confirmação ───────────────────────────────────────────────────────────

    private Transaction toTransaction(ImportCommitRow row, Account account, UUID userId,
                                      Map<PaymentMethodSlug, PaymentMethod> paymentMethods,
                                      Map<UUID, Category> categories) {
        if (!IMPORTABLE_TYPES.contains(row.type())) {
            throw new BusinessRuleException(
                    "Tipo não permitido na importação: " + row.type() + ".");
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

    /** Aplicação ou resgate: as duas pernas, com a da conta importada levando o externalRef. */
    private void importTransfer(ImportCommitRow row, Account account, UUID userId) {
        if (row.transferAccountId() == null || row.outgoing() == null) {
            throw new BusinessRuleException("Transferência sem a conta do outro lado.");
        }
        Account counterpart = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(row.transferAccountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + row.transferAccountId()));

        boolean outgoing = row.outgoing();
        List<Transaction> legs = accountService.transfer(
                outgoing ? account : counterpart,
                outgoing ? counterpart : account,
                row.amount(), row.date(), truncateDescription(row.description()));
        Transaction leg = legs.get(outgoing ? 0 : 1);
        leg.setExternalRef(row.externalRef());
        transactionRepository.save(leg);
    }

    /**
     * Valida aqui o que {@code payInvoice} validaria: {@code CreditCardService} é
     * transacional, e uma exceção atravessando o proxy dele marcaria a importação inteira
     * para rollback, em vez de só reportar a linha.
     */
    private void importInvoicePayment(ImportCommitRow row, Account account, UUID userId) {
        if (row.invoiceId() == null) {
            throw new BusinessRuleException("Pagamento de fatura sem a fatura.");
        }
        Invoice invoice = invoiceRepository.findByIdAndUserId(row.invoiceId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + row.invoiceId()));

        if (!invoice.isPaidWithoutTransaction()) {
            if (invoice.getStatus() == InvoiceStatus.PAID) {
                throw new BusinessRuleException("A fatura " + invoiceLabel(invoice) + " já está paga.");
            }
            BigDecimal remaining = invoice.getTotalAmount().subtract(invoice.getPaidAmount());
            if (row.amount().compareTo(remaining) > 0) {
                throw new BusinessRuleException("O valor é maior que o restante da fatura " + invoiceLabel(invoice)
                                                + " (" + remaining.toPlainString() + ").");
            }
        }

        Transaction tx = creditCardService.recordInvoicePayment(invoice, account, row.amount(), row.date());
        tx.setExternalRef(row.externalRef());
        // Save explícito: a quitação roda updates em lote que limpam o contexto de persistência,
        // e a chave posta só no objeto não chegaria ao banco — reimportar duplicaria o pagamento.
        transactionRepository.save(tx);
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
