package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.InstallmentSeries;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceItem;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.ReconciliationKind;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.ApplyReconciliationRequest;
import com.cashcontrol.api.dto.response.ApplyReconciliationResult;
import com.cashcontrol.api.dto.response.ImportReconciliationResponse;
import com.cashcontrol.api.dto.response.ReconciliationProposal;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.InstallmentSeriesRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.statement.InvoicePaymentMatcher;
import com.cashcontrol.api.service.statement.StatementHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reconciliação dos lançamentos importados antes das regras atuais do import (D7).
 *
 * <p>A prévia e a aplicação rodam a mesma análise sobre o banco: o cliente só diz quais
 * propostas aprovou, e o servidor aplica as que ainda valem. Um lançamento já convertido não
 * casa mais com nenhuma regra, por isso repetir a aplicação não muda nada.
 */
@Service
@RequiredArgsConstructor
public class ImportReconciliationServiceImpl implements ImportReconciliationService {

    /**
     * Descrições de aplicação/resgate no extrato do Inter. O histórico ("Aplicação",
     * "Resgate"), que o import usa, não fica gravado no lançamento; a descrição é o que sobra.
     */
    private static final Pattern INVESTMENT_DESCRIPTION =
            Pattern.compile("\\b(cdb|lci|lca|tesouro)\\b|renda fixa|porq obj|porquinho");

    private static final DateTimeFormatter DUE_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final InstallmentSeriesRepository installmentSeriesRepository;
    private final AccountService accountService;
    private final CreditCardService creditCardService;
    private final AttachmentService attachmentService;

    @Override
    @Transactional(readOnly = true)
    public ImportReconciliationResponse preview(UUID userId) {
        Analysis analysis = analyze(userId);
        List<ReconciliationProposal> proposals = new ArrayList<>();
        analysis.invoicePayments().forEach((tx, invoice) -> proposals.add(proposal(
                ReconciliationKind.INVOICE_PAYMENT, tx,
                "Vira pagamento da fatura " + invoiceLabel(invoice) + " (sai das despesas)")));
        if (analysis.investmentAccount() != null) {
            analysis.investments().forEach(tx -> proposals.add(proposal(ReconciliationKind.INVESTMENT, tx,
                    (tx.getType() == TransactionType.EXPENSE ? "Vira transferência para " : "Vira transferência de ")
                    + analysis.investmentAccount().getName())));
        }
        analysis.seriesMerges().forEach(merge -> proposals.add(new ReconciliationProposal(
                ReconciliationKind.SERIES_MERGE,
                merge.keeper().getId(),
                merge.keeper().getFirstPaymentDate(),
                merge.keeper().getDescription(),
                merge.amount(),
                merge.keeper().getCreditCard().getName(),
                "Junta " + merge.series().size() + " séries da mesma compra (" + merge.installments()
                + " de " + merge.keeper().getTotalInstallments() + " parcelas no sistema)")));
        return new ImportReconciliationResponse(
                analysis.investmentAccount() == null && !analysis.investments().isEmpty(), proposals);
    }

    @Override
    @Transactional
    public ApplyReconciliationResult apply(ApplyReconciliationRequest request, UUID userId) {
        Set<ApplyReconciliationRequest.Item> approved = new HashSet<>(request.items());
        Analysis analysis = analyze(userId);
        int invoicePayments = 0;
        int investments = 0;
        int seriesMerged = 0;

        for (Map.Entry<Transaction, Invoice> entry : analysis.invoicePayments().entrySet()) {
            if (approved.remove(new ApplyReconciliationRequest.Item(ReconciliationKind.INVOICE_PAYMENT,
                    entry.getKey().getId()))) {
                toInvoicePayment(entry.getKey(), entry.getValue());
                invoicePayments++;
            }
        }
        if (analysis.investmentAccount() != null) {
            for (Transaction tx : analysis.investments()) {
                if (approved.remove(new ApplyReconciliationRequest.Item(ReconciliationKind.INVESTMENT, tx.getId()))) {
                    toTransfer(tx, analysis.investmentAccount());
                    investments++;
                }
            }
        }
        for (SeriesMerge merge : analysis.seriesMerges()) {
            if (approved.remove(new ApplyReconciliationRequest.Item(ReconciliationKind.SERIES_MERGE,
                    merge.keeper().getId()))) {
                merge(merge);
                seriesMerged++;
            }
        }
        return new ApplyReconciliationResult(invoicePayments, investments, seriesMerged, approved.size());
    }

    // ── Análise ───────────────────────────────────────────────────────────────

    private record Analysis(Map<Transaction, Invoice> invoicePayments, List<Transaction> investments,
                            Account investmentAccount, List<SeriesMerge> seriesMerges) {}

    /** Séries de uma mesma compra; a de primeira parcela mais antiga é a que fica. */
    private record SeriesMerge(InstallmentSeries keeper, List<InstallmentSeries> series,
                               List<Transaction> transactions, BigDecimal amount, int installments) {}

    private Analysis analyze(UUID userId) {
        List<Transaction> movements = transactionRepository.findImportedAccountMovements(userId);

        List<Transaction> invoiceCandidates = movements.stream()
                .filter(tx -> tx.getType() == TransactionType.EXPENSE)
                .filter(tx -> StatementHistoryMapper.normalize(tx.getDescription()).contains("fatura"))
                .toList();
        Map<Transaction, Invoice> invoicePayments = matchInvoices(invoiceCandidates, userId);

        List<Transaction> investments = movements.stream()
                .filter(tx -> !invoicePayments.containsKey(tx))
                .filter(tx -> INVESTMENT_DESCRIPTION.matcher(
                        StatementHistoryMapper.normalize(tx.getDescription())).find())
                .toList();

        return new Analysis(invoicePayments, investments, findInvestmentAccount(userId), findSeriesMerges(userId));
    }

    /** Mesmo critério do import: valor dentro da tolerância e vencimento perto da data. */
    private Map<Transaction, Invoice> matchInvoices(List<Transaction> candidates, UUID userId) {
        Map<Transaction, Invoice> matched = new LinkedHashMap<>();
        if (candidates.isEmpty()) {
            return matched;
        }
        LocalDate first = candidates.getFirst().getCompetenceDate();
        LocalDate last = candidates.getLast().getCompetenceDate();
        InvoicePaymentMatcher matcher = new InvoicePaymentMatcher(invoiceRepository.findAllWithCardByUserIdAndDueDateBetween(
                userId, first.minusDays(InvoicePaymentMatcher.DUE_DAYS_BEFORE_PAYMENT),
                last.plusDays(InvoicePaymentMatcher.DUE_DAYS_AFTER_PAYMENT)));
        for (Transaction tx : candidates) {
            Invoice invoice = matcher.claim(tx.getCompetenceDate(), tx.getAmount());
            if (invoice != null && InvoicePaymentMatcher.amountMatches(invoice, tx.getAmount())) {
                matched.put(tx, invoice);
            }
        }
        return matched;
    }

    /** A mesma conta que o import usa: a primeira de investimento ativa, na ordem da tela. */
    private Account findInvestmentAccount(UUID userId) {
        return accountRepository
                .findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId)
                .stream()
                .filter(account -> account.getType() == AccountType.INVESTMENT)
                .findFirst()
                .orElse(null);
    }

    /**
     * Séries de cartão com a mesma descrição e o mesmo total de parcelas, parcelas sem
     * repetição entre elas e a mesma data de compra (o mês da parcela 1, contado de volta a
     * partir de cada parcela, com folga de um mês pelo fechamento). É o rastro de PDFs
     * importados fora de ordem antes de o import procurar a série existente.
     */
    private List<SeriesMerge> findSeriesMerges(UUID userId) {
        Map<String, List<InstallmentSeries>> groups = installmentSeriesRepository.findAllByUserId(userId).stream()
                .filter(series -> series.getCreditCard() != null && !series.isSettled())
                .collect(Collectors.groupingBy(series -> series.getCreditCard().getId() + "|"
                        + StatementHistoryMapper.normalize(series.getDescription()) + "|"
                        + series.getTotalInstallments(), LinkedHashMap::new, Collectors.toList()));
        List<List<InstallmentSeries>> candidates = groups.values().stream().filter(group -> group.size() > 1).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<Transaction>> bySeries = transactionRepository.findAllByInstallmentSeries_IdIn(
                        candidates.stream().flatMap(List::stream).map(InstallmentSeries::getId).toList())
                .stream()
                .filter(tx -> tx.getStatus() != TransactionStatus.CANCELLED && tx.getInstallmentNumber() != null)
                .collect(Collectors.groupingBy(tx -> tx.getInstallmentSeries().getId()));

        List<SeriesMerge> merges = new ArrayList<>();
        for (List<InstallmentSeries> group : candidates) {
            List<Transaction> transactions = group.stream()
                    .flatMap(series -> bySeries.getOrDefault(series.getId(), List.of()).stream())
                    .toList();
            Set<Integer> numbers = new HashSet<>();
            boolean disjoint = transactions.stream().allMatch(tx -> numbers.add(tx.getInstallmentNumber()));
            Set<YearMonth> purchaseMonths = transactions.stream()
                    .map(tx -> YearMonth.from(tx.getCompetenceDate()).minusMonths(tx.getInstallmentNumber() - 1L))
                    .collect(Collectors.toSet());
            boolean samePurchase = !purchaseMonths.isEmpty() && ChronoUnit.MONTHS.between(
                    purchaseMonths.stream().min(Comparator.naturalOrder()).orElseThrow(),
                    purchaseMonths.stream().max(Comparator.naturalOrder()).orElseThrow()) <= 1;
            if (!disjoint || !samePurchase) {
                continue;
            }
            InstallmentSeries keeper = group.stream()
                    .min(Comparator.comparing(InstallmentSeries::getFirstPaymentDate)
                            .thenComparing(InstallmentSeries::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElseThrow();
            BigDecimal amount = transactions.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            merges.add(new SeriesMerge(keeper, group, transactions, amount, numbers.size()));
        }
        return merges;
    }

    // ── Aplicação ─────────────────────────────────────────────────────────────

    /**
     * Troca a despesa pelo pagamento da fatura, pelo mesmo caminho do import. A despesa sai
     * antes (com flush) porque o pagamento herda o {@code external_ref}, único por conta.
     */
    private void toInvoicePayment(Transaction expense, Invoice invoice) {
        if (!invoice.isPaidWithoutTransaction()
            && (invoice.getStatus() == InvoiceStatus.PAID
                || expense.getAmount().compareTo(invoice.getTotalAmount().subtract(invoice.getPaidAmount())) > 0)) {
            // Outro pagamento chegou à fatura depois da prévia; converter criaria saldo negativo nela.
            return;
        }
        Account account = expense.getAccount();
        String externalRef = expense.getExternalRef();
        remove(expense);
        Transaction payment = creditCardService.recordInvoicePayment(
                invoice, account, expense.getAmount(), expense.getCompetenceDate());
        payment.setExternalRef(externalRef);
        // Save explícito: a quitação roda updates em lote que limpam o contexto, e a mudança
        // feita só no objeto se perderia.
        transactionRepository.save(payment);
    }

    private void toTransfer(Transaction tx, Account investmentAccount) {
        Account account = tx.getAccount();
        boolean outgoing = tx.getType() == TransactionType.EXPENSE;
        String externalRef = tx.getExternalRef();
        remove(tx);
        List<Transaction> legs = accountService.transfer(
                outgoing ? account : investmentAccount,
                outgoing ? investmentAccount : account,
                tx.getAmount(), tx.getCompetenceDate(), tx.getDescription());
        Transaction leg = legs.get(outgoing ? 0 : 1);
        leg.setExternalRef(externalRef);
        transactionRepository.save(leg);
    }

    private void remove(Transaction tx) {
        attachmentService.deleteAllForTransactions(List.of(tx.getId()));
        transactionRepository.delete(tx);
        transactionRepository.flush();
    }

    private void merge(SeriesMerge merge) {
        InstallmentSeries keeper = merge.keeper();
        List<InstallmentSeries> others = merge.series().stream()
                .filter(series -> !Objects.equals(series.getId(), keeper.getId()))
                .toList();
        for (InstallmentSeries other : others) {
            for (Transaction tx : transactionRepository.findAllByInstallmentSeries_Id(other.getId())) {
                tx.setInstallmentSeries(keeper);
            }
            for (InvoiceItem item : invoiceItemRepository.findAllByInstallmentSeries_Id(other.getId())) {
                item.setInstallmentSeries(keeper);
            }
            keeper.setTotalAmount(keeper.getTotalAmount().add(other.getTotalAmount()));
        }
        transactionRepository.flush();
        installmentSeriesRepository.deleteAll(others);
    }

    // ── Apoio ─────────────────────────────────────────────────────────────────

    private static ReconciliationProposal proposal(ReconciliationKind kind, Transaction tx, String detail) {
        return new ReconciliationProposal(kind, tx.getId(), tx.getCompetenceDate(), tx.getDescription(),
                tx.getAmount(), tx.getAccount().getName(), detail);
    }

    /** Pelo vencimento, como a tela de faturas identifica a fatura. */
    private static String invoiceLabel(Invoice invoice) {
        return invoice.getCreditCard().getName() + " que vence em " + invoice.getDueDate().format(DUE_DATE);
    }
}
