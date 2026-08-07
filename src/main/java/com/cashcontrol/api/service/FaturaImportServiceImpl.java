package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
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
import com.cashcontrol.api.dto.response.ImportRowError;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.InvoiceCycleCalculator.InvoiceCycleInfo;
import com.cashcontrol.api.service.fatura.FaturaParser;
import com.cashcontrol.api.service.fatura.FaturaRowHasher;
import com.cashcontrol.api.service.fatura.ParsedCardSection;
import com.cashcontrol.api.service.fatura.ParsedFatura;
import com.cashcontrol.api.service.fatura.ParsedFaturaRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FaturaImportServiceImpl implements FaturaImportService {

    private final CreditCardRepository creditCardRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final InstallmentSeriesRepository installmentSeriesRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final CategoryRuleMatcher categoryRuleMatcher;
    private final CreditCardService creditCardService;
    private final TransactionService transactionService;
    private final InvoiceCycleCalculator cycleCalculator;
    private final FaturaRowHasher rowHasher;
    private final List<FaturaParser> parsers;
    private final AppProperties appProperties;

    @Override
    @Transactional(readOnly = true)
    public FaturaImportPreviewResponse preview(MultipartFile file, InvoiceImportFormat format, UUID userId) {
        validateFile(file);

        ParsedFatura fatura = parse(file, format);
        String referenceMonth = referenceMonthOf(fatura.dueDate());
        List<CategoryRule> rules = categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId);

        List<FaturaImportGroupPreview> groups = new ArrayList<>(fatura.cardSections().size());
        int excludedPayments = 0;

        for (ParsedCardSection section : fatura.cardSections()) {
            // Valor positivo na fatura é crédito: pagamento da fatura anterior, estorno.
            // Não é despesa e não pode virar lançamento — some da prévia e só é contado.
            List<ParsedFaturaRow> expenses = section.rows().stream()
                    .filter(row -> row.signedAmount().signum() < 0)
                    .toList();
            excludedPayments += section.rows().size() - expenses.size();

            CreditCard suggested = suggestCard(section.cardLast4(), userId).orElse(null);
            List<String> hashes = rowHasher.hashAll(section.cardLast4(), expenses);
            Set<String> alreadyImported = findAlreadyImported(suggested, referenceMonth, hashes, userId);

            List<FaturaImportPreviewRow> rows = new ArrayList<>(expenses.size());
            for (int i = 0; i < expenses.size(); i++) {
                rows.add(toPreviewRow(expenses.get(i), hashes.get(i), alreadyImported, rules));
            }

            groups.add(new FaturaImportGroupPreview(
                    section.cardLast4(),
                    suggested != null ? suggested.getId() : null,
                    suggested != null ? suggested.getName() : null,
                    rows));
        }

        int totalRows = groups.stream().mapToInt(group -> group.rows().size()).sum();
        int duplicateCount = (int) groups.stream()
                .flatMap(group -> group.rows().stream())
                .filter(FaturaImportPreviewRow::duplicate)
                .count();

        return new FaturaImportPreviewResponse(
                file.getOriginalFilename(),
                format,
                fatura.dueDate(),
                referenceMonth,
                fatura.totalAmount(),
                groups,
                totalRows,
                duplicateCount,
                excludedPayments,
                fatura.errors());
    }

    @Override
    @Transactional
    public FaturaImportResultResponse commit(FaturaImportCommitRequest request, UUID userId) {
        CommitContext context = new CommitContext(
                requireImportableAccount(request.accountId(), userId),
                transactionService.resolvePaymentMethod(PaymentMethodSlug.CREDIT_CARD),
                parseReferenceMonth(request.referenceMonth()),
                loadVisibleCategories(request.rows(), userId),
                userId);

        CommitTally tally = new CommitTally();

        for (Map.Entry<UUID, List<FaturaImportCommitRow>> group : groupByCard(request.rows()).entrySet()) {
            List<FaturaImportCommitRow> rows = group.getValue();

            CreditCard card;
            Invoice invoice;
            try {
                card = requireImportableCard(group.getKey(), userId);
                invoice = resolveInvoice(card, context.referenceMonth());
            } catch (BusinessRuleException | ResourceNotFoundException e) {
                // O cartão inteiro cai, mas os demais grupos do mesmo PDF continuam:
                // uma fatura já paga não pode invalidar a importação do outro cartão.
                rows.forEach(row -> tally.errors.add(new ImportRowError(row.lineNumber(), e.getMessage())));
                continue;
            }

            // Duas fontes de duplicata, como no extrato: o que já está no banco e o que
            // veio repetido no próprio payload. Sem a segunda, o índice único estouraria
            // no flush com um erro que não diz ao usuário qual linha era.
            Set<String> blocked = blockedRefsOf(invoice, context, tally);

            for (FaturaImportCommitRow row : rows) {
                if (!blocked.add(row.externalRef())) {
                    tally.skippedDuplicates++;
                    continue;
                }
                try {
                    importRow(row, card, invoice, context, tally);
                } catch (BusinessRuleException | ResourceNotFoundException e) {
                    tally.errors.add(new ImportRowError(row.lineNumber(), e.getMessage()));
                }
            }
        }

        tally.addedByInvoice.forEach((invoice, added) -> {
            invoice.setTotalAmount(invoice.getTotalAmount().add(added));
            invoiceRepository.save(invoice);
        });

        return new FaturaImportResultResponse(tally.imported, tally.futureInstallments,
                tally.skippedDuplicates, tally.errors.size(), tally.errors);
    }

    // ── Prévia ────────────────────────────────────────────────────────────────

    private FaturaImportPreviewRow toPreviewRow(ParsedFaturaRow row, String externalRef,
                                                Set<String> alreadyImported, List<CategoryRule> rules) {
        Optional<CategoryRule> rule = categoryRuleMatcher.match(rules, row.description());

        return new FaturaImportPreviewRow(
                row.lineNumber(),
                externalRef,
                row.date(),
                truncateDescription(row.description()),
                row.signedAmount().abs(),
                row.installmentNumber(),
                row.totalInstallments(),
                rule.map(r -> r.getCategory().getId()).orElse(null),
                rule.map(r -> r.getCategory().getName()).orElse(null),
                alreadyImported.contains(externalRef));
    }

    /**
     * O cartão cadastrado que casa com a seção do PDF.
     *
     * <p>Só sugere quando o match é inequívoco: dois cartões com os mesmos 4 dígitos
     * (ou nenhum) devolvem vazio e o cliente pede a escolha ao usuário. Arquivados
     * ficam de fora porque não aceitam lançamento novo.
     */
    private Optional<CreditCard> suggestCard(String cardLast4, UUID userId) {
        List<CreditCard> matches = creditCardRepository
                .findAllByUserIdAndLast4DigitsAndDeletedAtIsNull(userId, cardLast4).stream()
                .filter(card -> card.getArchivedAt() == null)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /**
     * Quais linhas já entraram na fatura deste mês.
     *
     * <p>Depende do cartão sugerido: sem cartão não há fatura onde procurar, e nenhuma
     * linha é marcada como duplicata. Trocar o cartão no cliente desatualiza essa marca,
     * mas não abre buraco — a confirmação refaz a checagem contra a fatura de verdade.
     */
    private Set<String> findAlreadyImported(CreditCard card, String referenceMonth,
                                            List<String> hashes, UUID userId) {
        if (card == null || hashes.isEmpty()) {
            return Set.of();
        }
        return invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.getId(), referenceMonth)
                .map(invoice -> Set.copyOf(
                        invoiceItemRepository.findExistingExternalRefs(userId, invoice.getId(), hashes)))
                .orElseGet(Set::of);
    }

    /**
     * O mês de referência da fatura sai do vencimento, não das datas das compras: uma
     * parcela comprada em abril e um almoço de julho estão na mesma fatura. O vencimento
     * é sempre no mês seguinte ao fechamento — mesma convenção do
     * {@link InvoiceCycleCalculator}.
     */
    private String referenceMonthOf(LocalDate dueDate) {
        return cycleCalculator.toReferenceMonth(YearMonth.from(dueDate).minusMonths(1));
    }

    // ── Confirmação ───────────────────────────────────────────────────────────

    /** O que não muda de linha para linha, para não passar cinco parâmetros por método. */
    private record CommitContext(Account account, PaymentMethod paymentMethod, YearMonth referenceMonth,
                                 Map<UUID, Category> categories, UUID userId) {}

    /** Acumuladores da confirmação. */
    private static final class CommitTally {
        private final List<ImportRowError> errors = new ArrayList<>();
        /** Quanto cada fatura tocada cresce, somado no fim para não gravá-la uma vez por linha. */
        private final Map<Invoice, BigDecimal> addedByInvoice = new LinkedHashMap<>();
        private final Map<UUID, Set<String>> blockedRefsByInvoice = new HashMap<>();
        private int imported;
        private int futureInstallments;
        private int skippedDuplicates;
    }

    private Map<UUID, List<FaturaImportCommitRow>> groupByCard(List<FaturaImportCommitRow> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                FaturaImportCommitRow::creditCardId, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * Um lançamento do PDF e as parcelas que ainda faltam dele.
     *
     * <p>Só para frente: a linha "Parcela 04 de 05" gera a 4 (na fatura do PDF) e a 5 (na
     * do mês seguinte). As parcelas 1 a 3 não são criadas — cairiam em faturas que o
     * sistema não importou, e inventar uma fatura de abril contendo só essa parcela
     * mostraria um mês inteiro errado. Quem quiser o histórico importa os PDFs antigos,
     * onde cada parcela vem como linha de verdade.
     */
    private void importRow(FaturaImportCommitRow row, CreditCard card, Invoice invoice,
                           CommitContext context, CommitTally tally) {
        // Resolvida antes de qualquer save: é a última validação que ainda pode falhar, e
        // uma linha rejeitada não pode deixar transação órfã na transação do banco.
        Category category = resolveCategory(row.categoryId(), context.categories());
        String description = truncateDescription(rowHasher.stripInstallmentSuffix(row.description()));

        int number = row.installmentNumber() != null ? row.installmentNumber() : 1;
        int total = row.totalInstallments() != null ? row.totalInstallments() : 1;
        if (number > total) {
            throw new BusinessRuleException(
                    "Parcela " + number + " de " + total + " é inconsistente.");
        }

        InstallmentSeries series = total > 1
                ? newSeries(row, card, category, description, number, total, context)
                : null;

        saveCharge(invoice, row.externalRef(), competenceDateFor(row.date(), number), row.amount(),
                description, card, category, series, number, total, context, tally);
        tally.imported++;

        for (int next = number + 1; next <= total; next++) {
            saveFutureInstallment(row, card, category, description, series, number, next, total, context, tally);
        }
    }

    /**
     * @param baseNumber a parcela que veio no PDF; a distância até ela é a distância entre a
     *                   fatura do arquivo e a fatura que recebe esta parcela
     */
    private void saveFutureInstallment(FaturaImportCommitRow row, CreditCard card, Category category,
                                       String description, InstallmentSeries series, int baseNumber,
                                       int number, int total, CommitContext context, CommitTally tally) {
        Invoice invoice;
        try {
            invoice = resolveInvoice(card, context.referenceMonth().plusMonths((long) number - baseNumber));
        } catch (BusinessRuleException | ResourceNotFoundException e) {
            // A parcela do PDF já entrou; só esta não coube. Reportar é melhor que
            // derrubar a linha inteira e deixar a fatura do mês sem o lançamento.
            tally.errors.add(new ImportRowError(row.lineNumber(),
                    "Parcela " + number + "/" + total + ": " + e.getMessage()));
            return;
        }

        // A chave é a que a linha "Parcela N de T" vai produzir no PDF do mês que vem — é
        // assim que a importação seguinte reconhece esta parcela em vez de duplicá-la. Por
        // isso sai da descrição original: o PDF não sabe nada da que o usuário escolheu.
        String externalRef = rowHasher.hashInstallment(
                row.cardLast4(), row.date(), row.originalDescription(), row.amount(), number, total);
        if (!blockedRefsOf(invoice, context, tally).add(externalRef)) {
            // Já criada por uma importação anterior desta mesma compra. Não conta como
            // duplicata do arquivo: não veio de linha nenhuma do PDF.
            return;
        }

        saveCharge(invoice, externalRef, competenceDateFor(row.date(), number), row.amount(),
                description, card, category, series, number, total, context, tally);
        tally.futureInstallments++;
    }

    /**
     * A despesa de cartão como o sistema a representa: uma {@link Transaction} PENDENTE na
     * conta escolhida e o {@link InvoiceItem} espelho apontando para ela — o mesmo par que
     * {@code CreditCardService.createInvoiceItemForTransaction} monta quando o lançamento
     * vem pela tela.
     *
     * <p>O item não é criado por aquele método porque ele deduz a fatura da data de
     * competência e do dia de fechamento do cartão. Aqui a fatura é a que o PDF diz, e o
     * PDF é a autoridade: se o fechamento cadastrado divergir do real, a dedução jogaria a
     * compra num mês em que o banco não a cobrou.
     */
    private void saveCharge(Invoice invoice, String externalRef, LocalDate competenceDate,
                            BigDecimal amount, String description, CreditCard card, Category category,
                            InstallmentSeries series, int number, int total,
                            CommitContext context, CommitTally tally) {
        Transaction tx = new Transaction();
        tx.setUserId(context.userId());
        tx.setAccount(context.account());
        tx.setType(TransactionType.EXPENSE);
        // PENDENTE de propósito: o saldo da conta soma só transações PAGAS, e quem tira o
        // dinheiro da conta é o pagamento da fatura. PAGA aqui debitaria a compra duas vezes.
        tx.setStatus(TransactionStatus.PENDING);
        tx.setAmount(amount);
        tx.setDescription(description);
        tx.setCompetenceDate(competenceDate);
        tx.setPaymentMethod(context.paymentMethod());
        tx.setCreditCard(card);
        tx.setCategory(category);
        tx.setExternalRef(externalRef);
        if (series != null) {
            tx.setInstallmentSeries(series);
            tx.setInstallmentNumber(number);
            tx.setTotalInstallments(total);
        }
        tx = transactionRepository.save(tx);

        InvoiceItem item = new InvoiceItem();
        item.setUserId(context.userId());
        item.setInvoice(invoice);
        item.setTransaction(tx);
        item.setDescription(description);
        item.setAmount(amount);
        item.setCompetenceDate(competenceDate);
        item.setCategory(category);
        item.setExternalRef(externalRef);
        if (series != null) {
            item.setInstallmentSeries(series);
            item.setInstallmentNumber(number);
            item.setTotalInstallments(total);
        }
        invoiceItemRepository.save(item);

        tally.addedByInvoice.merge(invoice, amount, BigDecimal::add);
    }

    /**
     * A série que agrupa o parcelamento na tela de Parcelamentos.
     *
     * <p>{@code totalInstallments} é o da compra (5 de 5, como está no PDF), mas
     * {@code totalAmount} é só o que ainda será lançado — as parcelas anteriores à do PDF
     * não existem no sistema, e declarar o valor cheio prometeria transações que não vão
     * aparecer.
     */
    private InstallmentSeries newSeries(FaturaImportCommitRow row, CreditCard card, Category category,
                                        String description, int number, int total, CommitContext context) {
        InstallmentSeries series = new InstallmentSeries();
        series.setUserId(context.userId());
        series.setAccount(context.account());
        series.setCreditCard(card);
        series.setPaymentMethod(context.paymentMethod());
        series.setType(TransactionType.EXPENSE);
        series.setDescription(description);
        series.setTotalAmount(row.amount().multiply(BigDecimal.valueOf(total - number + 1L)));
        series.setTotalInstallments(total);
        series.setFirstPaymentDate(competenceDateFor(row.date(), number));
        series.setCategory(category);
        return installmentSeriesRepository.save(series);
    }

    /**
     * A competência da parcela {@code number} de uma compra feita em {@code purchaseDate}.
     *
     * <p>A fatura repete a data da compra em toda parcela — a 4 de 5 de uma compra de abril
     * continua datada de abril. Usar isso como competência empilharia as cinco parcelas no
     * mesmo mês. O deslocamento mensal é o mesmo de {@code InstallmentService}, que data a
     * parcela {@code i} em {@code firstPaymentDate.plusMonths(i)}.
     */
    private LocalDate competenceDateFor(LocalDate purchaseDate, int number) {
        return purchaseDate.plusMonths(number - 1L);
    }

    /**
     * As chaves já ocupadas na fatura, carregadas uma vez por fatura tocada.
     *
     * <p>É o mesmo conjunto para as linhas do PDF e para as parcelas futuras, o que faz uma
     * parcela gerada agora bloquear a mesma parcela vinda de outra linha do arquivo.
     */
    private Set<String> blockedRefsOf(Invoice invoice, CommitContext context, CommitTally tally) {
        return tally.blockedRefsByInvoice.computeIfAbsent(invoice.getId(),
                id -> new HashSet<>(invoiceItemRepository.findAllExternalRefs(context.userId(), id)));
    }

    private Category resolveCategory(UUID categoryId, Map<UUID, Category> categories) {
        if (categoryId == null) {
            return null;
        }
        Category category = categories.get(categoryId);
        if (category == null) {
            throw new ResourceNotFoundException("Category not found: " + categoryId);
        }
        return category;
    }

    private Account requireImportableAccount(UUID accountId, UUID userId) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Não é possível importar lançamentos em uma conta arquivada.");
        }
        return account;
    }

    private CreditCard requireImportableCard(UUID cardId, UUID userId) {
        CreditCard card = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(cardId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit card not found: " + cardId));
        if (card.getArchivedAt() != null) {
            throw new BusinessRuleException(
                    "Cartões de crédito arquivados não podem receber novos lançamentos.");
        }
        return card;
    }

    private Invoice resolveInvoice(CreditCard card, YearMonth referenceMonth) {
        LocalDate closingDate = cycleCalculator.closingDateFor(referenceMonth, card.getClosingDay());
        InvoiceCycleInfo cycleInfo = new InvoiceCycleInfo(
                cycleCalculator.toReferenceMonth(referenceMonth),
                closingDate,
                cycleCalculator.dueDateFor(closingDate, card.getDueDay()));

        Invoice invoice = creditCardService.getOrCreateInvoice(card, cycleInfo);
        if (invoice.getPaidAmount().signum() > 0) {
            // Somar lançamentos depois do pagamento deixaria total e pago inconsistentes,
            // e o saldo rotativo já gerado apontaria para um valor que não existe mais.
            throw new BusinessRuleException(
                    "A fatura de " + invoice.getReferenceMonth() + " do cartão '" + card.getName()
                    + "' já recebeu pagamento e não aceita a importação de novos lançamentos.");
        }
        return invoice;
    }

    // ── Apoio ─────────────────────────────────────────────────────────────────

    /** {@code invoice_items.description} e {@code transactions.description} são VARCHAR(255). */
    private String truncateDescription(String description) {
        return description.length() <= 255 ? description : description.substring(0, 255);
    }

    private YearMonth parseReferenceMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("Mês de referência inválido: '" + value + "'. Use aaaa-mm.");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Envie o arquivo PDF da fatura.");
        }
        long maxBytes = (long) appProperties.getInvoiceImport().getMaxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessRuleException("O arquivo excede o tamanho máximo permitido de "
                                            + appProperties.getInvoiceImport().getMaxFileSizeMb() + " MB.");
        }
    }

    private ParsedFatura parse(MultipartFile file, InvoiceImportFormat format) {
        FaturaParser parser = parsers.stream()
                .filter(p -> p.format() == format)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Formato de fatura não suportado: " + format));

        try (InputStream in = file.getInputStream()) {
            return parser.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Categorias que o usuário pode usar: as dele e as de sistema. Carregadas de uma vez
     * para não fazer uma consulta por linha — e é aqui que uma categoria de outro usuário
     * enviada no payload deixa de ser encontrada.
     */
    private Map<UUID, Category> loadVisibleCategories(List<FaturaImportCommitRow> rows, UUID userId) {
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
