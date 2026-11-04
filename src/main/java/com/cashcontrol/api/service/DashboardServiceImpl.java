package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.AccountScope;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.ChartGranularity;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.BalanceProjectionResponse;
import com.cashcontrol.api.dto.response.CategoryPieChartResponse;
import com.cashcontrol.api.dto.response.LargestExpenseResponse;
import com.cashcontrol.api.dto.response.MonthlyBarChartResponse;
import com.cashcontrol.api.dto.response.MonthlyComparisonResponse;
import com.cashcontrol.api.dto.response.NetWorthEvolutionResponse;
import com.cashcontrol.api.dto.response.OverviewMetricsResponse;
import com.cashcontrol.api.dto.response.RecentTransactionResponse;
import com.cashcontrol.api.dto.response.SubscriptionsResponse;
import com.cashcontrol.api.dto.response.UpcomingBillResponse;
import com.cashcontrol.api.dto.response.UpcomingInvoiceResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.RecurrenceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.investment.InvestmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final List<InvoiceStatus> UPCOMING_INVOICE_STATUSES =
            List.of(InvoiceStatus.CLOSED, InvoiceStatus.PARTIAL, InvoiceStatus.OVERDUE);
    private static final List<TransactionStatus> UPCOMING_BILL_STATUSES =
            List.of(TransactionStatus.PENDING, TransactionStatus.OVERDUE);

    private final TransactionRepository transactionRepository;
    private final InvoiceRepository invoiceRepository;
    private final CategoryRepository categoryRepository;
    private final AppProperties appProperties;
    private final BalanceProjectionCalculator balanceProjectionCalculator;
    private final InvestmentService investmentService;
    private final RecurrenceRepository recurrenceRepository;
    private final RecurrenceService recurrenceService;
    private final AccountRepository accountRepository;
    private final CreditCardRepository creditCardRepository;

    @Override
    public OverviewMetricsResponse getOverviewMetrics(UUID userId, AccountScope scope) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        String currentMonth = today.format(MONTH_FORMATTER);

        BigDecimal totalBalance = safe(transactionRepository.sumTotalBalanceExcludingType(userId, AccountType.INVESTMENT, scope));

        BigDecimal monthlyIncome = safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                userId, TransactionType.INCOME, monthStart, monthEnd, null, scope));
        BigDecimal monthlyExpenses = netExpenses(userId, monthStart, monthEnd, scope);
        BigDecimal monthlySavings = monthlyIncome.subtract(monthlyExpenses);
        BigDecimal cashFlow = monthlySavings;

        // Base dos comparativos dos cards: o mês anterior inteiro, mesmas regras do atual.
        LocalDate previousStart = monthStart.minusMonths(1);
        LocalDate previousEnd = monthStart.minusDays(1);
        // Patrimônio com as aplicações pelo valor de resgate, hoje e no fim do mês anterior pelo
        // mesmo critério — senão a variação mistura custo com valor de mercado.
        Map<LocalDate, BigDecimal> unrealized = investmentService.unrealizedNetYield(
                userId, List.of(today, previousEnd), today, scope);
        BigDecimal netWorth = safe(transactionRepository.sumTotalNetWorth(userId, scope)).add(unrealized.get(today));
        BigDecimal previousNetWorth = safe(transactionRepository.sumNetWorthUpTo(userId, previousEnd, scope))
                .add(unrealized.get(previousEnd));
        BigDecimal previousIncome = safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                userId, TransactionType.INCOME, previousStart, previousEnd, null, scope));
        BigDecimal previousExpenses = netExpenses(userId, previousStart, previousEnd, scope);

        InvestmentService.DashboardFigures investments = investmentService.getDashboardFigures(userId, today, scope);

        return new OverviewMetricsResponse(
                totalBalance, netWorth, monthlyIncome, monthlyExpenses,
                monthlySavings, cashFlow, currentMonth,
                previousNetWorth, previousIncome, previousExpenses,
                investments.gross(), investments.net(), investments.yieldMonth(),
                safe(invoiceRepository.sumUnpaidRemainingByUserId(userId, scope)));
    }

    @Override
    public CategoryPieChartResponse getCategoryPieChart(
            UUID userId, LocalDate from, LocalDate to, UUID accountId, TransactionType type, AccountScope scope) {

        TransactionType effectiveType = type != null ? type : TransactionType.EXPENSE;

        List<Object[]> rows = effectiveType == TransactionType.EXPENSE
                ? netExpenseBreakdown(userId, from, to, accountId, scope)
                : transactionRepository.findCategoryBreakdown(userId, effectiveType, from, to, accountId, scope);
        Map<UUID, String> categoryNames = categoryNamesOf(rows);
        BigDecimal uncategorized = safe(transactionRepository.sumUncategorized(userId, effectiveType, from, to, accountId, scope));
        if (effectiveType == TransactionType.EXPENSE) {
            uncategorized = uncategorized.subtract(
                    safe(transactionRepository.sumUncategorized(userId, TransactionType.REFUND, from, to, accountId, scope)))
                    .max(BigDecimal.ZERO);
        }

        BigDecimal total = rows.stream()
                .map(r -> (BigDecimal) r[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(uncategorized);

        List<CategoryPieChartResponse.Entry> entries = new ArrayList<>();
        for (Object[] row : rows) {
            UUID categoryId = (UUID) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            String name = categoryNames.getOrDefault(categoryId, "Desconhecida");
            BigDecimal pct = total.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : amount.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            entries.add(new CategoryPieChartResponse.Entry(categoryId, name, amount, pct));
        }

        if (uncategorized.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = total.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : uncategorized.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            entries.add(new CategoryPieChartResponse.Entry(null, "Sem categoria", uncategorized, pct));
        }

        return new CategoryPieChartResponse(entries, total);
    }

    @Override
    public MonthlyBarChartResponse getMonthlyBarChart(UUID userId, int months, UUID accountId, AccountScope scope) {
        int effectiveMonths = Math.min(months, 24);
        LocalDate today = LocalDate.now();
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate from = today.minusMonths(effectiveMonths - 1L).withDayOfMonth(1);

        List<Object[]> rows = transactionRepository.findMonthlyIncomeExpense(
                userId, from, to, accountId, scope != null ? scope.name() : null);

        Map<String, BigDecimal> incomeMap = new HashMap<>();
        Map<String, BigDecimal> expenseMap = new HashMap<>();
        for (Object[] row : rows) {
            String month = (String) row[0];
            String txType = (String) row[1];
            BigDecimal amount = toBigDecimal(row[2]);
            if ("INCOME".equals(txType)) {
                incomeMap.merge(month, amount, BigDecimal::add);
            } else if ("EXPENSE".equals(txType)) {
                expenseMap.merge(month, amount, BigDecimal::add);
            } else if ("REFUND".equals(txType)) {
                expenseMap.merge(month, amount.negate(), BigDecimal::add);
            }
        }

        List<MonthlyBarChartResponse.Entry> entries = new ArrayList<>();
        YearMonth cursor = YearMonth.from(from);
        YearMonth end = YearMonth.from(to);
        while (!cursor.isAfter(end)) {
            String label = cursor.format(MONTH_FORMATTER);
            BigDecimal income = incomeMap.getOrDefault(label, BigDecimal.ZERO);
            BigDecimal expense = expenseMap.getOrDefault(label, BigDecimal.ZERO);
            entries.add(new MonthlyBarChartResponse.Entry(label, income, expense, income.subtract(expense)));
            cursor = cursor.plusMonths(1);
        }

        return new MonthlyBarChartResponse(entries);
    }

    @Override
    public NetWorthEvolutionResponse getNetWorthEvolution(
            UUID userId, LocalDate from, LocalDate to, ChartGranularity granularity, AccountScope scope) {

        ChartGranularity effectiveGranularity = granularity != null ? granularity : ChartGranularity.MONTHLY;

        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = advance(cursor, effectiveGranularity)) {
            dates.add(cursor);
        }
        if (dates.isEmpty() || !dates.getLast().equals(to)) {
            dates.add(to);
        }

        Map<LocalDate, BigDecimal> unrealized = investmentService.unrealizedNetYield(userId, dates, LocalDate.now(), scope);
        List<NetWorthEvolutionResponse.Snapshot> snapshots = dates.stream()
                .map(date -> new NetWorthEvolutionResponse.Snapshot(date,
                        safe(transactionRepository.sumNetWorthUpTo(userId, date, scope)).add(unrealized.get(date))))
                .toList();

        return new NetWorthEvolutionResponse(snapshots);
    }

    @Override
    public MonthlyComparisonResponse getMonthlyComparison(UUID userId, String month1, String month2, AccountScope scope) {
        MonthlyComparisonResponse.MonthMetrics m1 = buildMonthMetrics(userId, month1, scope);
        MonthlyComparisonResponse.MonthMetrics m2 = buildMonthMetrics(userId, month2, scope);

        BigDecimal incomeDelta = m2.income().subtract(m1.income());
        BigDecimal expensesDelta = m2.expenses().subtract(m1.expenses());
        BigDecimal savingsDelta = m2.savings().subtract(m1.savings());

        BigDecimal incomePct = percentChange(m1.income(), m2.income());
        BigDecimal expensesPct = percentChange(m1.expenses(), m2.expenses());
        BigDecimal savingsPct = percentChange(m1.savings(), m2.savings());

        return new MonthlyComparisonResponse(m1, m2,
                new MonthlyComparisonResponse.Delta(
                        incomeDelta, expensesDelta, savingsDelta,
                        incomePct, expensesPct, savingsPct));
    }

    @Override
    public List<UpcomingBillResponse> getUpcomingBills(UUID userId, int daysAhead, AccountScope scope) {
        int effectiveDays = daysAhead > 0 ? daysAhead : appProperties.getDashboard().getUpcomingBillsDays();
        int maxResults = appProperties.getDashboard().getUpcomingBillsMaxResults();
        LocalDate deadline = LocalDate.now().plusDays(effectiveDays);

        List<Transaction> transactions = transactionRepository.findUpcomingBills(
                userId, UPCOMING_BILL_STATUSES, deadline, scope, PageRequest.of(0, maxResults));

        return transactions.stream()
                .map(t -> new UpcomingBillResponse(
                        t.getId(),
                        t.getAmount(),
                        t.getDescription(),
                        t.getAccount().getName(),
                        t.getCategory() != null ? t.getCategory().getName() : null,
                        t.getPaymentDate() != null ? t.getPaymentDate() : t.getCompetenceDate(),
                        t.getStatus()))
                .toList();
    }

    @Override
    public List<UpcomingInvoiceResponse> getUpcomingInvoices(UUID userId, int daysAhead, AccountScope scope) {
        int effectiveDays = daysAhead > 0 ? daysAhead : appProperties.getDashboard().getUpcomingBillsDays();
        LocalDate deadline = LocalDate.now().plusDays(effectiveDays);

        List<Invoice> invoices = invoiceRepository
                .findAllByUserIdAndDueDateLessThanEqualAndStatusIn(userId, deadline, UPCOMING_INVOICE_STATUSES);

        return invoices.stream()
                .filter(inv -> scope == null || inv.getCreditCard().getScope() == scope)
                .sorted((a, b) -> a.getDueDate().compareTo(b.getDueDate()))
                .map(inv -> {
                    BigDecimal remaining = inv.getTotalAmount().subtract(inv.getPaidAmount());
                    return new UpcomingInvoiceResponse(
                            inv.getId(),
                            inv.getCreditCard().getName(),
                            inv.getTotalAmount(),
                            inv.getPaidAmount(),
                            remaining,
                            inv.getDueDate(),
                            inv.getStatus());
                })
                .toList();
    }

    @Override
    public List<LargestExpenseResponse> getLargestExpenses(UUID userId, LocalDate from, LocalDate to, int limit, AccountScope scope) {
        int effectiveLimit = limit > 0 ? limit : 5;
        LocalDate effectiveTo = to != null ? to : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);

        List<Transaction> transactions = transactionRepository.findLargestExpenses(
                userId, effectiveFrom, effectiveTo, scope, PageRequest.of(0, effectiveLimit));

        return transactions.stream()
                .map(t -> new LargestExpenseResponse(
                        t.getId(),
                        t.amountNetOfReimbursement(),
                        t.getDescription(),
                        t.getCategory() != null ? t.getCategory().getName() : null,
                        t.getAccount().getName(),
                        t.getPaymentDate()))
                .toList();
    }

    @Override
    public List<RecentTransactionResponse> getRecentTransactions(UUID userId, int limit, AccountScope scope) {
        int effectiveLimit = limit > 0 ? limit : 10;

        // Só o que já aconteceu: parcelas e recorrências futuras não são "recentes".
        List<Transaction> transactions = transactionRepository.findRecentTransactions(
                userId, LocalDate.now(), scope, PageRequest.of(0, effectiveLimit));

        return transactions.stream()
                .map(t -> new RecentTransactionResponse(
                        t.getId(),
                        t.getAmount(),
                        t.getDescription(),
                        t.getType(),
                        t.getStatus(),
                        t.getAccount().getName(),
                        t.getCategory() != null ? t.getCategory().getName() : null,
                        t.getCompetenceDate()))
                .toList();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Despesa do período já descontados os estornos — da conta (Pix devolvido, cashback) e do
     * cartão. O estorno conta no mês em que aconteceu, não no da compra que ele desfaz.
     */
    @Override
    public BalanceProjectionResponse getBalanceProjection(UUID userId, int days, AccountScope scope) {
        AppProperties.Dashboard props = appProperties.getDashboard();
        int effectiveDays = days > 0 ? Math.min(days, props.getProjectionMaxDays()) : props.getProjectionDefaultDays();
        return balanceProjectionCalculator.project(userId, LocalDate.now(), effectiveDays, scope);
    }

    private BigDecimal netExpenses(UUID userId, LocalDate from, LocalDate to, AccountScope scope) {
        return safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                        userId, TransactionType.EXPENSE, from, to, null, scope))
                .subtract(safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                        userId, TransactionType.REFUND, from, to, null, scope)));
    }

    /**
     * Despesa por categoria menos os estornos da mesma categoria, no formato de
     * {@code findCategoryBreakdown}: [categoryId, valor], do maior para o menor. Categoria que
     * o estorno zerou (ou que só teve estorno) sai da lista — o gráfico é de gasto.
     */
    private List<Object[]> netExpenseBreakdown(UUID userId, LocalDate from, LocalDate to, UUID accountId,
                                               AccountScope scope) {
        Map<UUID, BigDecimal> net = new HashMap<>();
        for (Object[] row : transactionRepository.findCategoryBreakdown(
                userId, TransactionType.EXPENSE, from, to, accountId, scope)) {
            net.merge((UUID) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        for (Object[] row : transactionRepository.findCategoryBreakdown(
                userId, TransactionType.REFUND, from, to, accountId, scope)) {
            net.computeIfPresent((UUID) row[0], (id, amount) -> amount.subtract((BigDecimal) row[1]));
        }
        return net.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                .map(entry -> new Object[]{entry.getKey(), entry.getValue()})
                .toList();
    }

    private MonthlyComparisonResponse.MonthMetrics buildMonthMetrics(UUID userId, String month, AccountScope scope) {
        YearMonth ym = YearMonth.parse(month, MONTH_FORMATTER);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        BigDecimal income = safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                userId, TransactionType.INCOME, from, to, null, scope));
        BigDecimal expenses = netExpenses(userId, from, to, scope);
        BigDecimal savings = income.subtract(expenses);

        List<Object[]> rows = netExpenseBreakdown(userId, from, to, null, scope);
        Map<UUID, String> categoryNames = categoryNamesOf(rows);
        BigDecimal total = rows.stream().map(r -> (BigDecimal) r[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<MonthlyComparisonResponse.CategoryEntry> breakdown = rows.stream()
                .map(row -> {
                    UUID catId = (UUID) row[0];
                    BigDecimal amount = (BigDecimal) row[1];
                    String name = categoryNames.getOrDefault(catId, "Desconhecida");
                    BigDecimal pct = total.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : amount.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
                    return new MonthlyComparisonResponse.CategoryEntry(catId, name, amount, pct);
                })
                .toList();

        return new MonthlyComparisonResponse.MonthMetrics(month, income, expenses, savings, breakdown);
    }

    private LocalDate advance(LocalDate date, ChartGranularity granularity) {
        return switch (granularity) {
            case DAILY -> date.plusDays(1);
            case WEEKLY -> date.plusWeeks(1);
            case MONTHLY -> date.plusMonths(1).withDayOfMonth(1);
        };
    }

    private BigDecimal percentChange(BigDecimal oldVal, BigDecimal newVal) {
        if (oldVal.compareTo(BigDecimal.ZERO) == 0) {
            return newVal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : null;
        }
        return newVal.subtract(oldVal)
                .multiply(BigDecimal.valueOf(100))
                .divide(oldVal.abs(), 2, RoundingMode.HALF_UP);
    }

    /** Nomes das categorias de um breakdown numa consulta só, em vez de uma por linha. */
    private Map<UUID, String> categoryNamesOf(List<Object[]> rows) {
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, String> names = new HashMap<>();
        categoryRepository.findAllById(ids).forEach(c -> names.put(c.getId(), c.getName()));
        return names;
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(Object obj) {
        if (obj instanceof BigDecimal bd) return bd;
        if (obj instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    @Override
    public SubscriptionsResponse getSubscriptions(UUID userId, AccountScope scope) {
        List<SubscriptionsResponse.Item> items = new ArrayList<>();
        recurrenceRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .filter(rule -> scope == null || rule.scope() == scope)
                .filter(rule -> rule.getStatus() == RecurrenceStatus.ACTIVE
                        && rule.getType() == TransactionType.EXPENSE
                        && rule.getFrequency() == RecurrenceFrequency.MONTHLY)
                .map(rule -> new SubscriptionsResponse.Item(rule.getDescription(), rule.getAmount(),
                        SubscriptionsResponse.Source.RECURRENCE))
                .forEach(items::add);
        Set<UUID> outOfScope = outOfScopeOwners(userId, scope);
        recurrenceService.listSuggestions(userId).stream()
                .filter(suggestion -> !outOfScope.contains(suggestion.creditCardId() != null
                        ? suggestion.creditCardId() : suggestion.accountId()))
                .map(suggestion -> new SubscriptionsResponse.Item(suggestion.description(), suggestion.amount(),
                        SubscriptionsResponse.Source.SUGGESTION))
                .forEach(items::add);
        BigDecimal total = items.stream()
                .map(SubscriptionsResponse.Item::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new SubscriptionsResponse(total, items);
    }

    /** Ids das contas e dos cartões fora do escopo pedido; vazio quando {@code scope} é nulo. */
    private Set<UUID> outOfScopeOwners(UUID userId, AccountScope scope) {
        Set<UUID> ids = new HashSet<>();
        if (scope == null) {
            return ids;
        }
        accountRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .filter(account -> account.getScope() != scope)
                .forEach(account -> ids.add(account.getId()));
        creditCardRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .filter(card -> card.getScope() != scope)
                .forEach(card -> ids.add(card.getId()));
        return ids;
    }
}
