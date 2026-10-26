package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.ChartGranularity;
import com.cashcontrol.api.domain.entity.Invoice;
import com.cashcontrol.api.domain.entity.InvoiceStatus;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.CategoryPieChartResponse;
import com.cashcontrol.api.dto.response.LargestExpenseResponse;
import com.cashcontrol.api.dto.response.MonthlyBarChartResponse;
import com.cashcontrol.api.dto.response.MonthlyComparisonResponse;
import com.cashcontrol.api.dto.response.NetWorthEvolutionResponse;
import com.cashcontrol.api.dto.response.OverviewMetricsResponse;
import com.cashcontrol.api.dto.response.RecentTransactionResponse;
import com.cashcontrol.api.dto.response.UpcomingBillResponse;
import com.cashcontrol.api.dto.response.UpcomingInvoiceResponse;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.InvoiceRepository;
import com.cashcontrol.api.repository.TransactionRepository;
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
import java.util.List;
import java.util.Map;
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

    @Override
    public OverviewMetricsResponse getOverviewMetrics(UUID userId) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        String currentMonth = today.format(MONTH_FORMATTER);

        BigDecimal totalBalance = safe(transactionRepository.sumTotalBalanceExcludingType(userId, AccountType.INVESTMENT));
        BigDecimal netWorth = safe(transactionRepository.sumTotalNetWorth(userId));
        BigDecimal monthlyIncome = safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                userId, TransactionType.INCOME, monthStart, monthEnd, null));
        BigDecimal monthlyExpenses = netExpenses(userId, monthStart, monthEnd);
        BigDecimal monthlySavings = monthlyIncome.subtract(monthlyExpenses);
        BigDecimal cashFlow = monthlySavings;

        return new OverviewMetricsResponse(
                totalBalance, netWorth, monthlyIncome, monthlyExpenses,
                monthlySavings, cashFlow, currentMonth);
    }

    @Override
    public CategoryPieChartResponse getCategoryPieChart(
            UUID userId, LocalDate from, LocalDate to, UUID accountId, TransactionType type) {

        TransactionType effectiveType = type != null ? type : TransactionType.EXPENSE;

        List<Object[]> rows = effectiveType == TransactionType.EXPENSE
                ? netExpenseBreakdown(userId, from, to, accountId)
                : transactionRepository.findCategoryBreakdown(userId, effectiveType, from, to, accountId);
        Map<UUID, String> categoryNames = categoryNamesOf(rows);
        BigDecimal uncategorized = safe(transactionRepository.sumUncategorized(userId, effectiveType, from, to, accountId));
        if (effectiveType == TransactionType.EXPENSE) {
            uncategorized = uncategorized.subtract(
                    safe(transactionRepository.sumUncategorized(userId, TransactionType.REFUND, from, to, accountId)))
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
    public MonthlyBarChartResponse getMonthlyBarChart(UUID userId, int months, UUID accountId) {
        int effectiveMonths = Math.min(months, 24);
        LocalDate today = LocalDate.now();
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate from = today.minusMonths(effectiveMonths - 1L).withDayOfMonth(1);

        List<Object[]> rows = transactionRepository.findMonthlyIncomeExpense(
                userId, from, to, accountId);

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
            UUID userId, LocalDate from, LocalDate to, ChartGranularity granularity) {

        ChartGranularity effectiveGranularity = granularity != null ? granularity : ChartGranularity.MONTHLY;

        List<NetWorthEvolutionResponse.Snapshot> snapshots = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            BigDecimal netWorth = safe(transactionRepository.sumNetWorthUpTo(userId, cursor));
            snapshots.add(new NetWorthEvolutionResponse.Snapshot(cursor, netWorth));
            cursor = advance(cursor, effectiveGranularity);
        }

        if (snapshots.isEmpty() || !snapshots.getLast().date().equals(to)) {
            BigDecimal netWorth = safe(transactionRepository.sumNetWorthUpTo(userId, to));
            snapshots.add(new NetWorthEvolutionResponse.Snapshot(to, netWorth));
        }

        return new NetWorthEvolutionResponse(snapshots);
    }

    @Override
    public MonthlyComparisonResponse getMonthlyComparison(UUID userId, String month1, String month2) {
        MonthlyComparisonResponse.MonthMetrics m1 = buildMonthMetrics(userId, month1);
        MonthlyComparisonResponse.MonthMetrics m2 = buildMonthMetrics(userId, month2);

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
    public List<UpcomingBillResponse> getUpcomingBills(UUID userId, int daysAhead) {
        int effectiveDays = daysAhead > 0 ? daysAhead : appProperties.getDashboard().getUpcomingBillsDays();
        int maxResults = appProperties.getDashboard().getUpcomingBillsMaxResults();
        LocalDate deadline = LocalDate.now().plusDays(effectiveDays);

        List<Transaction> transactions = transactionRepository.findUpcomingBills(
                userId, UPCOMING_BILL_STATUSES, deadline, PageRequest.of(0, maxResults));

        return transactions.stream()
                .map(t -> new UpcomingBillResponse(
                        t.getId(),
                        t.getAmount(),
                        t.getDescription(),
                        t.getAccount().getName(),
                        t.getCategory() != null ? t.getCategory().getName() : null,
                        t.getPaymentDate(),
                        t.getStatus()))
                .toList();
    }

    @Override
    public List<UpcomingInvoiceResponse> getUpcomingInvoices(UUID userId, int daysAhead) {
        int effectiveDays = daysAhead > 0 ? daysAhead : appProperties.getDashboard().getUpcomingBillsDays();
        LocalDate deadline = LocalDate.now().plusDays(effectiveDays);

        List<Invoice> invoices = invoiceRepository
                .findAllByUserIdAndDueDateLessThanEqualAndStatusIn(userId, deadline, UPCOMING_INVOICE_STATUSES);

        return invoices.stream()
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
    public List<LargestExpenseResponse> getLargestExpenses(UUID userId, LocalDate from, LocalDate to, int limit) {
        int effectiveLimit = limit > 0 ? limit : 5;
        LocalDate effectiveTo = to != null ? to : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);

        List<Transaction> transactions = transactionRepository.findLargestExpenses(
                userId, effectiveFrom, effectiveTo, PageRequest.of(0, effectiveLimit));

        return transactions.stream()
                .map(t -> new LargestExpenseResponse(
                        t.getId(),
                        t.getAmount(),
                        t.getDescription(),
                        t.getCategory() != null ? t.getCategory().getName() : null,
                        t.getAccount().getName(),
                        t.getPaymentDate()))
                .toList();
    }

    @Override
    public List<RecentTransactionResponse> getRecentTransactions(UUID userId, int limit) {
        int effectiveLimit = limit > 0 ? limit : 10;

        List<Transaction> transactions = transactionRepository.findRecentTransactions(
                userId, PageRequest.of(0, effectiveLimit));

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
    private BigDecimal netExpenses(UUID userId, LocalDate from, LocalDate to) {
        return safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                        userId, TransactionType.EXPENSE, from, to, null))
                .subtract(safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                        userId, TransactionType.REFUND, from, to, null)));
    }

    /**
     * Despesa por categoria menos os estornos da mesma categoria, no formato de
     * {@code findCategoryBreakdown}: [categoryId, valor], do maior para o menor. Categoria que
     * o estorno zerou (ou que só teve estorno) sai da lista — o gráfico é de gasto.
     */
    private List<Object[]> netExpenseBreakdown(UUID userId, LocalDate from, LocalDate to, UUID accountId) {
        Map<UUID, BigDecimal> net = new HashMap<>();
        for (Object[] row : transactionRepository.findCategoryBreakdown(
                userId, TransactionType.EXPENSE, from, to, accountId)) {
            net.merge((UUID) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        for (Object[] row : transactionRepository.findCategoryBreakdown(
                userId, TransactionType.REFUND, from, to, accountId)) {
            net.computeIfPresent((UUID) row[0], (id, amount) -> amount.subtract((BigDecimal) row[1]));
        }
        return net.entrySet().stream()
                .filter(entry -> entry.getValue().signum() > 0)
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                .map(entry -> new Object[]{entry.getKey(), entry.getValue()})
                .toList();
    }

    private MonthlyComparisonResponse.MonthMetrics buildMonthMetrics(UUID userId, String month) {
        YearMonth ym = YearMonth.parse(month, MONTH_FORMATTER);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        BigDecimal income = safe(transactionRepository.sumPaidByTypeAndPaymentDateRange(
                userId, TransactionType.INCOME, from, to, null));
        BigDecimal expenses = netExpenses(userId, from, to);
        BigDecimal savings = income.subtract(expenses);

        List<Object[]> rows = netExpenseBreakdown(userId, from, to, null);
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
}
