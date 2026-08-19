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
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRow;
import com.cashcontrol.api.dto.request.FaturaImportDuplicateCheckRequest;
import com.cashcontrol.api.dto.response.FaturaImportDuplicateCheckResponse;
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
import com.cashcontrol.api.service.fatura.FaturaRowHasher.RowKey;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final CategorySuggester categorySuggester;
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
        // Uma consulta de histórico para o arquivo inteiro, não uma por linha nem uma por
        // seção de cartão.
        Map<String, CategorySuggester.Suggestion> history = categorySuggester.loadHistory(userId,
                fatura.cardSections().stream().flatMap(section -> section.rows().stream())
                        .map(ParsedFaturaRow::description).toList());

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
            List<RowKey> keys = rowHasher.hashAll(section.cardLast4(), expenses);
            Set<String> alreadyImported = suggested == null
                    ? Set.of()
                    : findAlreadyImported(suggested, referenceMonth,
                            keys.stream().map(RowKey::externalRef).toList(), userId);

            List<FaturaImportPreviewRow> rows = new ArrayList<>(expenses.size());
            for (int i = 0; i < expenses.size(); i++) {
                rows.add(toPreviewRow(expenses.get(i), keys.get(i), alreadyImported, rules, history));
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

    /**
     * Grava o que o usuário aprovou na prévia.
     *
     * <p>A confirmação é organizada por <em>compra</em>, não por linha, porque uma compra
     * parcelada pode aparecer no PDF como várias linhas: o emissor às vezes estorna a compra
     * e relança as três parcelas na mesma fatura. Tratando linha a linha, a linha
     * "Parcela 01 de 03" geraria as parcelas 2 e 3 nos meses seguintes e as linhas
     * "Parcela 02" e "Parcela 03" do próprio arquivo gravariam as mesmas chaves na fatura
     * atual — duas transações com o mesmo {@code external_ref} na mesma conta, o que o
     * índice único recusa no flush.
     *
     * <p>Agrupando, o arquivo manda: só é gerado o que ele não traz.
     */
    @Override
    @Transactional
    public FaturaImportResultResponse commit(FaturaImportCommitRequest request, UUID userId) {
        Account account = requireImportableAccount(request.accountId(), userId);
        YearMonth referenceMonth = parseReferenceMonth(request.referenceMonth());

        CommitTally tally = new CommitTally();
        List<FaturaImportCommitRow> rows = withoutRepeatedRows(request.rows(), tally);

        Map<UUID, List<PurchaseGroup>> purchasesByCard = new LinkedHashMap<>();
        groupByCard(rows).forEach((cardId, cardRows) ->
                purchasesByCard.put(cardId, groupByPurchase(cardRows)));

        CommitContext context = new CommitContext(
                account,
                transactionService.resolvePaymentMethod(PaymentMethodSlug.CREDIT_CARD),
                referenceMonth,
                loadVisibleCategories(rows, userId),
                loadExistingCharges(purchasesByCard, account, userId),
                userId);
        tally.claimed.addAll(context.existing().refs());

        // A fatura do mês de referência de cada cartão — uma por seção do PDF. Só estas são
        // quitadas quando o usuário marca "fatura já paga"; as parcelas futuras vivem em
        // outros meses e continuam abertas.
        List<Invoice> currentInvoices = new ArrayList<>();

        for (Map.Entry<UUID, List<PurchaseGroup>> group : purchasesByCard.entrySet()) {
            CreditCard card;
            Invoice invoice;
            try {
                card = requireImportableCard(group.getKey(), userId);
                invoice = resolveInvoice(card, context.referenceMonth());
            } catch (BusinessRuleException | ResourceNotFoundException e) {
                // O cartão inteiro cai, mas os demais grupos do mesmo PDF continuam:
                // uma fatura já paga não pode invalidar a importação do outro cartão.
                group.getValue().stream().flatMap(purchase -> purchase.rows().stream())
                        .forEach(row -> tally.errors.add(new ImportRowError(row.lineNumber(), e.getMessage())));
                continue;
            }

            currentInvoices.add(invoice);
            for (PurchaseGroup purchase : group.getValue()) {
                importPurchase(purchase, card, invoice, context, tally);
            }
        }

        tally.addedByInvoice.forEach((invoice, added) -> {
            invoice.setTotalAmount(invoice.getTotalAmount().add(added));
            invoiceRepository.save(invoice);
        });

        int markedPaid = request.alreadyPaid() ? markInvoicesPaid(currentInvoices) : 0;

        return new FaturaImportResultResponse(tally.imported, tally.futureInstallments,
                tally.skippedDuplicates, tally.errors.size(), markedPaid, tally.errors);
    }

    /**
     * Quita as faturas do mês, para o caso de importar uma fatura que já foi paga na vida real.
     *
     * <p>Marca só a fatura: {@code paidAmount = totalAmount} e status PAGO. As compras
     * continuam PENDENTES — é o próprio modelo do sistema, em que a fatura é a fonte de
     * verdade do "pago" e o {@code payInvoice} normal também não mexe nas transações. Como
     * o saldo da conta soma apenas transações PAGAS, quitar aqui não movimenta a conta,
     * exatamente o combinado para o histórico já liquidado.
     *
     * <p>O total já foi consolidado antes desta chamada, então {@code getTotalAmount()} é o
     * valor final da fatura. Faturas que já tinham pagamento nem chegam aqui: a
     * {@link #resolveInvoice} recusa importar sobre elas.
     */
    private int markInvoicesPaid(List<Invoice> invoices) {
        for (Invoice invoice : invoices) {
            invoice.setPaidAmount(invoice.getTotalAmount());
            invoice.setStatus(InvoiceStatus.PAID);
            invoiceRepository.save(invoice);
        }
        return invoices.size();
    }

    // ── Prévia ────────────────────────────────────────────────────────────────

    private FaturaImportPreviewRow toPreviewRow(ParsedFaturaRow row, RowKey key, Set<String> alreadyImported,
                                                List<CategoryRule> rules, Map<String, CategorySuggester.Suggestion> history) {
        CategorySuggester.Suggestion suggestion = categorySuggester.suggest(row.description(), rules, history);

        return new FaturaImportPreviewRow(
                row.lineNumber(),
                key.externalRef(),
                key.ordinal(),
                row.date(),
                truncateDescription(row.description()),
                row.signedAmount().abs(),
                row.installmentNumber(),
                row.totalInstallments(),
                MerchantKey.of(row.description()),
                suggestion.categoryId(),
                suggestion.categoryName(),
                suggestion.subcategoryId(),
                suggestion.subcategoryName(),
                suggestion.source(),
                alreadyImported.contains(key.externalRef()));
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
     * Quais linhas já entraram na fatura deste mês, para o cartão informado.
     *
     * <p>Depende do cartão: é a fatura dele que diz o que já foi importado. Na prévia
     * só existe cartão quando os 4 dígitos do PDF casaram com um cadastrado; quando o
     * usuário escolhe o cartão à mão, é o {@link #checkDuplicates} que refaz esta
     * mesma consulta com o cartão escolhido.
     */
    private Set<String> findAlreadyImported(CreditCard card, String referenceMonth,
                                            List<String> refs, UUID userId) {
        if (refs.isEmpty()) {
            return Set.of();
        }
        return invoiceRepository.findByCreditCard_IdAndReferenceMonth(card.getId(), referenceMonth)
                .map(invoice -> Set.copyOf(
                        invoiceItemRepository.findExistingExternalRefs(userId, invoice.getId(), refs)))
                .orElseGet(Set::of);
    }

    /**
     * A marcação de duplicatas de uma seção, refeita contra o cartão que o usuário
     * escolheu no cliente.
     *
     * <p>Não grava nada e não guarda estado: recebe os {@code externalRef} que a prévia
     * devolveu e responde quais deles já estão na fatura do cartão. O cartão é validado
     * como na confirmação — arquivado não recebe lançamento, e um cartão de outro usuário
     * simplesmente não existe.
     */
    @Override
    @Transactional(readOnly = true)
    public FaturaImportDuplicateCheckResponse checkDuplicates(FaturaImportDuplicateCheckRequest request, UUID userId) {
        CreditCard card = requireImportableCard(request.creditCardId(), userId);
        // Só valida o formato: a consulta usa a string, que é como o mês é gravado.
        parseReferenceMonth(request.referenceMonth());

        Set<String> duplicates = findAlreadyImported(
                card, request.referenceMonth(), request.externalRefs(), userId);

        // Devolvidos na ordem em que o cliente perguntou, para uma resposta estável.
        return new FaturaImportDuplicateCheckResponse(
                request.externalRefs().stream().distinct().filter(duplicates::contains).toList());
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

    /** O que não muda de linha para linha, para não passar sete parâmetros por método. */
    private record CommitContext(Account account, PaymentMethod paymentMethod, YearMonth referenceMonth,
                                 Map<UUID, Category> categories, ExistingCharges existing, UUID userId) {}

    /**
     * As cobranças que já ocupam alguma das chaves que esta importação vai tocar.
     *
     * <p>Carregadas de uma vez, antes de gravar qualquer coisa, e com o mesmo alcance do
     * índice único que protegem: a conta inteira para as transações, o usuário inteiro para
     * os itens de fatura. Checar por fatura — como era antes — deixa passar a chave que já
     * existe em outro mês.
     *
     * @param transactions transação por {@code external_ref}
     * @param items        item de fatura por {@code external_ref}. Pode existir sem a
     *                     transação: excluir um lançamento de cartão desvincula o item e
     *                     mantém a chave nele
     */
    private record ExistingCharges(Map<String, Transaction> transactions, Map<String, InvoiceItem> items) {

        static ExistingCharges empty() {
            return new ExistingCharges(Map.of(), Map.of());
        }

        /** A união das duas fontes: é o conjunto de chaves que não pode ser gravado de novo. */
        Set<String> refs() {
            Set<String> all = new HashSet<>(transactions.keySet());
            all.addAll(items.keySet());
            return all;
        }
    }

    /**
     * As linhas do arquivo que descrevem a mesma compra parcelada, e as parcelas que faltam.
     *
     * @param rows        as linhas aprovadas dessa compra, na ordem do arquivo
     * @param total       o total de parcelas declarado pelo PDF
     * @param lastNumber  a maior parcela que o arquivo traz. As seguintes é que são geradas,
     *                    e o deslocamento delas conta a partir daqui — não a partir de cada
     *                    linha, ou a fatura que já contém as parcelas 1 a 3 geraria a 4 três
     *                    vezes, em três meses diferentes
     */
    private record PurchaseGroup(List<FaturaImportCommitRow> rows, int total, int lastNumber) {

        static PurchaseGroup of(List<FaturaImportCommitRow> rows) {
            int total = rows.stream().mapToInt(row -> numberOf(row.totalInstallments())).max().orElse(1);
            int last = rows.stream().mapToInt(row -> numberOf(row.installmentNumber())).max().orElse(1);
            return new PurchaseGroup(rows, total, last);
        }

        private static int numberOf(Integer value) {
            return value != null ? value : 1;
        }

        /** A linha de maior parcela: é dela que saem descrição e valor das que serão geradas. */
        FaturaImportCommitRow representative() {
            return rows.stream()
                    .max(Comparator.comparingInt(row -> numberOf(row.installmentNumber())))
                    .orElseThrow();
        }
    }

    /** Acumuladores da confirmação. */
    private static final class CommitTally {
        private final List<ImportRowError> errors = new ArrayList<>();
        /** Quanto cada fatura tocada varia, somado no fim para não gravá-la uma vez por linha. */
        private final Map<Invoice, BigDecimal> addedByInvoice = new LinkedHashMap<>();
        /** Chaves já ocupadas: as que vieram do banco e as que esta confirmação gravou. */
        private final Set<String> claimed = new HashSet<>();
        private int imported;
        private int futureInstallments;
        private int skippedDuplicates;
    }

    /**
     * O mesmo {@code externalRef} duas vezes no payload é a mesma linha, não duas.
     *
     * <p>Descartar aqui, antes do agrupamento, evita que a repetição infle o total da série
     * e conte a compra duas vezes.
     */
    private List<FaturaImportCommitRow> withoutRepeatedRows(List<FaturaImportCommitRow> rows,
                                                            CommitTally tally) {
        Map<String, FaturaImportCommitRow> unique = new LinkedHashMap<>();
        for (FaturaImportCommitRow row : rows) {
            if (unique.putIfAbsent(row.externalRef(), row) != null) {
                tally.skippedDuplicates++;
            }
        }
        return List.copyOf(unique.values());
    }

    private Map<UUID, List<FaturaImportCommitRow>> groupByCard(List<FaturaImportCommitRow> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                FaturaImportCommitRow::creditCardId, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * Junta as linhas que são parcelas da mesma compra.
     *
     * <p>A chave é tudo que a identidade da linha tem menos a posição da parcela — cartão,
     * data da compra, descrição normalizada, total de parcelas e o ordinal. O ordinal vem da
     * prévia justamente para isto: sem ele, duas compras parceladas no mesmo dia, no mesmo
     * estabelecimento e com o mesmo número de parcelas cairiam no mesmo grupo.
     */
    private List<PurchaseGroup> groupByPurchase(List<FaturaImportCommitRow> rows) {
        Map<String, List<FaturaImportCommitRow>> byPurchase = new LinkedHashMap<>();
        for (FaturaImportCommitRow row : rows) {
            byPurchase.computeIfAbsent(purchaseKeyOf(row), key -> new ArrayList<>()).add(row);
        }
        return byPurchase.values().stream().map(PurchaseGroup::of).toList();
    }

    private String purchaseKeyOf(FaturaImportCommitRow row) {
        if (row.totalInstallments() == null || row.totalInstallments() <= 1) {
            // Compra à vista não tem irmãs: a própria chave da linha isola o grupo.
            return row.externalRef();
        }
        return row.cardLast4()
               + "|" + row.date()
               + "|" + rowHasher.normalizedDescription(row.originalDescription())
               + "|" + row.totalInstallments()
               + "|#" + row.ordinal();
    }

    /**
     * A chave que a parcela {@code number} desta compra terá — a mesma que a linha
     * "Parcela N de T" vai produzir no PDF do mês que vem.
     */
    private String generatedRefOf(PurchaseGroup purchase, int number) {
        FaturaImportCommitRow row = purchase.representative();
        return rowHasher.hashInstallment(row.cardLast4(), row.date(), row.originalDescription(),
                number, purchase.total(), row.ordinal());
    }

    private ExistingCharges loadExistingCharges(Map<UUID, List<PurchaseGroup>> purchasesByCard,
                                                Account account, UUID userId) {
        Set<String> refs = new LinkedHashSet<>();
        for (List<PurchaseGroup> purchases : purchasesByCard.values()) {
            for (PurchaseGroup purchase : purchases) {
                purchase.rows().forEach(row -> refs.add(row.externalRef()));
                for (int number = purchase.lastNumber() + 1; number <= purchase.total(); number++) {
                    refs.add(generatedRefOf(purchase, number));
                }
            }
        }
        if (refs.isEmpty()) {
            return ExistingCharges.empty();
        }
        return new ExistingCharges(
                byExternalRef(transactionRepository.findAllByExternalRefIn(userId, account.getId(), refs),
                        Transaction::getExternalRef),
                byExternalRef(invoiceItemRepository.findAllByExternalRefIn(userId, refs),
                        InvoiceItem::getExternalRef));
    }

    private <T> Map<String, T> byExternalRef(List<T> values, Function<T, String> refOf) {
        return values.stream().collect(Collectors.toMap(refOf, Function.identity(), (a, b) -> a));
    }

    /**
     * Grava uma compra: as linhas que o arquivo traz e só as parcelas que ele não traz.
     *
     * <p>Não há backfill: a linha "Parcela 04 de 05" gera a 5, nunca as 1 a 3. Elas cairiam
     * em faturas que o sistema não importou, e inventar uma fatura de abril contendo só essa
     * parcela mostraria um mês inteiro errado. Quem quiser o histórico importa os PDFs
     * antigos, onde cada parcela vem como linha de verdade.
     */
    private void importPurchase(PurchaseGroup purchase, CreditCard card, Invoice invoice,
                                CommitContext context, CommitTally tally) {
        SeriesHolder series = new SeriesHolder(purchase, card, context);
        boolean anyImported = false;

        for (FaturaImportCommitRow row : purchase.rows()) {
            try {
                anyImported |= importRow(row, purchase, series, card, invoice, context, tally);
            } catch (BusinessRuleException | ResourceNotFoundException e) {
                tally.errors.add(new ImportRowError(row.lineNumber(), e.getMessage()));
            }
        }

        // Sem nenhuma linha nova, a compra já estava no sistema e as parcelas seguintes dela
        // também — gerá-las de novo só produziria trabalho que o `claimed` descartaria.
        if (!anyImported) {
            return;
        }
        for (int number = purchase.lastNumber() + 1; number <= purchase.total(); number++) {
            try {
                saveFutureInstallment(purchase, series, number, card, context, tally);
            } catch (BusinessRuleException | ResourceNotFoundException e) {
                tally.errors.add(new ImportRowError(
                        purchase.representative().lineNumber(), e.getMessage()));
            }
        }
    }

    /** @return true quando a linha virou lançamento novo */
    private boolean importRow(FaturaImportCommitRow row, PurchaseGroup purchase, SeriesHolder series,
                              CreditCard card, Invoice invoice, CommitContext context, CommitTally tally) {
        // Resolvida antes de qualquer save: é a última validação que ainda pode falhar, e
        // uma linha rejeitada não pode deixar transação órfã na transação do banco.
        Category category = resolveCategory(row.categoryId(), context.categories());

        int number = row.installmentNumber() != null ? row.installmentNumber() : 1;
        if (number > purchase.total()) {
            throw new BusinessRuleException(
                    "Parcela " + number + " de " + purchase.total() + " é inconsistente.");
        }

        if (!tally.claimed.add(row.externalRef())) {
            reconcileAmount(row, context, tally);
            tally.skippedDuplicates++;
            return false;
        }

        String description = truncateDescription(rowHasher.stripInstallmentSuffix(row.description()));
        saveCharge(invoice, row.externalRef(), competenceDateFor(row.date(), context.referenceMonth(), card),
                row.amount(), description, card, category,
                series.resolve(category, description), number, purchase.total(), context, tally);
        tally.imported++;
        return true;
    }

    /**
     * A parcela {@code number}, que o arquivo não traz, na fatura que vai recebê-la.
     *
     * <p>O valor é o da última parcela do arquivo — é a melhor estimativa disponível, e o
     * emissor costuma deixar o resto da divisão na primeira parcela, não nas seguintes.
     * Quando o PDF do mês que vem chegar com o valor real, {@link #reconcileAmount} ajusta.
     */
    private void saveFutureInstallment(PurchaseGroup purchase, SeriesHolder series, int number,
                                       CreditCard card, CommitContext context, CommitTally tally) {
        FaturaImportCommitRow row = purchase.representative();
        String externalRef = generatedRefOf(purchase, number);
        if (!tally.claimed.add(externalRef)) {
            // Já criada por uma importação anterior desta mesma compra. Não conta como
            // duplicata do arquivo: não veio de linha nenhuma dele.
            return;
        }

        YearMonth targetMonth = context.referenceMonth().plusMonths((long) number - purchase.lastNumber());
        Invoice invoice;
        try {
            invoice = resolveInvoice(card, targetMonth);
        } catch (BusinessRuleException | ResourceNotFoundException e) {
            // As linhas do PDF já entraram; só esta parcela não coube. Reportar é melhor que
            // derrubar a compra inteira e deixar a fatura do mês sem o lançamento.
            tally.errors.add(new ImportRowError(row.lineNumber(),
                    "Parcela " + number + "/" + purchase.total() + ": " + e.getMessage()));
            return;
        }

        Category category = resolveCategory(row.categoryId(), context.categories());
        String description = truncateDescription(rowHasher.stripInstallmentSuffix(row.description()));
        saveCharge(invoice, externalRef, competenceDateFor(row.date(), targetMonth, card), row.amount(),
                description, card, category, series.resolve(category, description),
                number, purchase.total(), context, tally);
        tally.futureInstallments++;
    }

    /**
     * Ajusta o valor de uma cobrança que já existe para o que o PDF está dizendo.
     *
     * <p>O caso que importa é a parcela criada por estimativa no mês anterior: o emissor
     * deixa o resto da divisão na primeira parcela — 48,28 + 48,26 + 48,26 —, e a parcela 2
     * nasceu valendo 48,28. Agora o PDF traz o valor real dela, e o PDF é a autoridade.
     *
     * <p>Só o valor. Descrição e categoria podem ter sido editadas pelo usuário depois da
     * importação, e sobrescrevê-las apagaria a escolha dele.
     */
    private void reconcileAmount(FaturaImportCommitRow row, CommitContext context, CommitTally tally) {
        Transaction tx = context.existing().transactions().get(row.externalRef());
        if (tx != null && tx.getAmount().compareTo(row.amount()) != 0) {
            tx.setAmount(row.amount());
            transactionRepository.save(tx);
        }

        InvoiceItem item = context.existing().items().get(row.externalRef());
        if (item == null || item.getAmount().compareTo(row.amount()) == 0) {
            return;
        }
        BigDecimal delta = row.amount().subtract(item.getAmount());
        item.setAmount(row.amount());
        invoiceItemRepository.save(item);
        if (item.getCancelledAt() == null) {
            // Um item cancelado já foi subtraído do total da fatura; somar a diferença nele
            // devolveria à fatura um valor que ela não cobra mais.
            tally.addedByInvoice.merge(item.getInvoice(), delta, BigDecimal::add);
        }
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
     * A série que agrupa o parcelamento na tela de Parcelamentos — uma por compra, criada na
     * primeira parcela que de fato for gravada.
     *
     * <p>Uma por compra, e não uma por linha: quando o emissor lança as três parcelas na
     * mesma fatura, criar uma série por linha mostraria três parcelamentos de uma parcela
     * cada no lugar de um de três.
     *
     * <p>Preguiçosa porque uma compra cujas linhas são todas duplicatas não pode deixar uma
     * série vazia para trás.
     */
    private final class SeriesHolder {

        private final PurchaseGroup purchase;
        private final CreditCard card;
        private final CommitContext context;
        private InstallmentSeries series;

        private SeriesHolder(PurchaseGroup purchase, CreditCard card, CommitContext context) {
            this.purchase = purchase;
            this.card = card;
            this.context = context;
        }

        private InstallmentSeries resolve(Category category, String description) {
            if (purchase.total() <= 1) {
                return null;
            }
            if (series == null) {
                series = create(category, description);
            }
            return series;
        }

        /**
         * {@code totalInstallments} é o da compra (5 de 5, como está no PDF), mas
         * {@code totalAmount} é só o que esta importação vai lançar — as parcelas anteriores
         * às do arquivo não existem no sistema, e declarar o valor cheio prometeria
         * transações que não vão aparecer.
         */
        private InstallmentSeries create(Category category, String description) {
            FaturaImportCommitRow first = purchase.rows().getFirst();
            BigDecimal fromFile = purchase.rows().stream()
                    .map(FaturaImportCommitRow::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal generated = purchase.representative().amount()
                    .multiply(BigDecimal.valueOf((long) purchase.total() - purchase.lastNumber()));

            InstallmentSeries created = new InstallmentSeries();
            created.setUserId(context.userId());
            created.setAccount(context.account());
            created.setCreditCard(card);
            created.setPaymentMethod(context.paymentMethod());
            created.setType(TransactionType.EXPENSE);
            created.setDescription(description);
            created.setTotalAmount(fromFile.add(generated));
            created.setTotalInstallments(purchase.total());
            created.setFirstPaymentDate(
                    competenceDateFor(first.date(), context.referenceMonth(), card));
            created.setCategory(category);
            return installmentSeriesRepository.save(created);
        }
    }

    /**
     * A competência de uma cobrança que o PDF colocou na fatura {@code invoiceMonth}.
     *
     * <p>A fatura repete a data da compra em toda parcela — a 4 de 5 de uma compra de abril
     * continua datada de abril —, então a competência tem de ser deslocada. O deslocamento
     * sai da <em>fatura de destino</em>, não do número da parcela: quando o emissor lança as
     * três parcelas na mesma fatura, contar pelo número jogaria a terceira dois meses à
     * frente do mês em que ela está sendo cobrada.
     *
     * <p>O dia é o da compra, e o mês é escolhido para que
     * {@link InvoiceCycleCalculator#calculateForCharge} devolva exatamente
     * {@code invoiceMonth}. Sem isso, qualquer edição posterior do lançamento faria
     * {@code syncInvoiceItemForTransaction} recalcular o ciclo pela competência e mudar o
     * item de fatura.
     */
    private LocalDate competenceDateFor(LocalDate purchaseDate, YearMonth invoiceMonth, CreditCard card) {
        YearMonth chargeMonth = purchaseDate.getDayOfMonth() <= card.getClosingDay()
                ? invoiceMonth
                : invoiceMonth.minusMonths(1);
        return chargeMonth.atDay(Math.min(purchaseDate.getDayOfMonth(), chargeMonth.lengthOfMonth()));
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
