package com.cashcontrol.api.service.budget;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.domain.entity.AccountScope;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryBudget;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.BudgetHistoryResponse;
import com.cashcontrol.api.dto.response.BudgetProgressResponse;
import com.cashcontrol.api.dto.response.CategoryBudgetResponse;
import com.cashcontrol.api.repository.CategoryBudgetRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orçamento por categoria: o teto vem de {@code category_budgets}, o gasto é calculado a cada
 * leitura pelas transações do mês da compra.
 *
 * <p>Entra como gasto a despesa paga e a do cartão ainda pendente (a compra já foi feita); a
 * despesa pendente fora do cartão é uma conta a vencer e entra como previsto. O estorno
 * desconta do gasto. O gasto de uma subcategoria conta também para a categoria pai.
 */
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final CategoryBudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public BudgetProgressResponse progress(UUID userId, YearMonth month) {
        return progress(userId, month, (AccountScope) null);
    }

    /** Progresso só com o gasto do escopo ({@code null} soma todos); os tetos são os mesmos. */
    @Transactional(readOnly = true)
    public BudgetProgressResponse progress(UUID userId, YearMonth month, AccountScope scope) {
        return progress(userId, month, LocalDate.now(), scope);
    }

    BudgetProgressResponse progress(UUID userId, YearMonth month, LocalDate today) {
        return progress(userId, month, today, null);
    }

    private BudgetProgressResponse progress(UUID userId, YearMonth month, LocalDate today, AccountScope scope) {
        Map<UUID, CategoryBudget> budgets = applicableBudgets(userId, month);
        Map<UUID, Category> categories = visibleCategories(userId);
        Spending spending = spending(userId, month, categories, scope);
        Map<UUID, BigDecimal> spent = spending.spent();
        Map<UUID, BigDecimal> forecast = spending.forecast();
        BigDecimal uncategorized = spending.uncategorized();

        List<BudgetProgressResponse.Item> items = new ArrayList<>();
        BigDecimal totalBudget = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;
        BigDecimal totalForecast = BigDecimal.ZERO;
        for (CategoryBudget budget : budgets.values()) {
            Category category = categories.get(budget.getCategoryId());
            if (category == null || category.isArchived()) {
                continue;
            }
            BigDecimal itemSpent = spent.getOrDefault(category.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            BigDecimal itemForecast = forecast.getOrDefault(category.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
            UUID parentId = category.getParent() != null ? category.getParent().getId() : null;
            items.add(new BudgetProgressResponse.Item(
                    category.getId(), category.getName(), category.getColor(), category.getIcon(), parentId,
                    budget.getAmount(), itemSpent, itemForecast, budget.getAmount().subtract(itemSpent),
                    percent(itemSpent, budget.getAmount()),
                    budget.getMonth() == null ? BudgetProgressResponse.Source.DEFAULT : BudgetProgressResponse.Source.MONTH));
            // A subcategoria com teto já está dentro do gasto da pai; somar as duas contaria em dobro.
            if (parentId == null || !budgets.containsKey(parentId)) {
                totalBudget = totalBudget.add(budget.getAmount());
                totalSpent = totalSpent.add(itemSpent);
                totalForecast = totalForecast.add(itemForecast);
            }
        }
        items.sort(Comparator.comparing(BudgetProgressResponse.Item::percent).reversed()
                .thenComparing(BudgetProgressResponse.Item::name));

        return new BudgetProgressResponse(month, totalBudget, totalSpent, totalForecast,
                uncategorized.max(BigDecimal.ZERO), elapsedDays(month, today), month.lengthOfMonth(), items);
    }

    /**
     * Orçamento × realizado dos {@code months} meses que terminam em {@code until}, pelas mesmas
     * regras de {@link #progress}: o teto de cada mês é o ajuste daquele mês ou o padrão, e o gasto
     * é o realizado pela data da compra, sem reembolsos. Entra toda categoria que teve teto em
     * algum dos meses; no mês sem teto a célula vem com {@code budget} e {@code percent} nulos.
     */
    @Transactional(readOnly = true)
    public BudgetHistoryResponse history(UUID userId, YearMonth until, int months) {
        return history(userId, until, months, null);
    }

    /** {@link #history(UUID, YearMonth, int)} só com o gasto do escopo; {@code null} soma todos. */
    @Transactional(readOnly = true)
    public BudgetHistoryResponse history(UUID userId, YearMonth until, int months, AccountScope scope) {
        if (months < 1 || months > 12) {
            throw new IllegalArgumentException("months must be between 1 and 12: " + months);
        }
        Map<UUID, Category> categories = visibleCategories(userId);
        List<YearMonth> range = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            range.add(until.minusMonths(i));
        }

        // Uma consulta de tetos e uma de gasto para o intervalo inteiro, separadas por mês aqui.
        YearMonth first = range.getFirst();
        List<CategoryBudget> allBudgets = budgetRepository.findApplicableBetween(userId, first.atDay(1), until.atDay(1));
        Map<YearMonth, List<Object[]>> rowsByMonth = new HashMap<>();
        for (Object[] row : transactionRepository.sumBudgetSpendingByMonth(userId, first.atDay(1), until.atEndOfMonth(), scope)) {
            YearMonth month = YearMonth.of(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            rowsByMonth.computeIfAbsent(month, k -> new ArrayList<>()).add(Arrays.copyOfRange(row, 2, row.length));
        }

        Map<YearMonth, Map<UUID, CategoryBudget>> budgetsByMonth = new HashMap<>();
        Map<YearMonth, Map<UUID, BigDecimal>> spentByMonth = new HashMap<>();
        Set<UUID> budgeted = new LinkedHashSet<>();
        for (YearMonth month : range) {
            LocalDate monthStart = month.atDay(1);
            Map<UUID, CategoryBudget> budgets = applicableBudgets(allBudgets.stream()
                    .filter(budget -> budget.getMonth() == null || budget.getMonth().equals(monthStart))
                    .toList());
            budgetsByMonth.put(month, budgets);
            spentByMonth.put(month, spending(rowsByMonth.getOrDefault(month, List.of()), categories).spent());
            budgeted.addAll(budgets.keySet());
        }

        List<BudgetHistoryResponse.Item> items = new ArrayList<>();
        for (UUID categoryId : budgeted) {
            Category category = categories.get(categoryId);
            if (category == null || category.isArchived()) {
                continue;
            }
            List<BudgetHistoryResponse.Cell> cells = new ArrayList<>();
            for (YearMonth month : range) {
                CategoryBudget budget = budgetsByMonth.get(month).get(categoryId);
                BigDecimal spent = spentByMonth.get(month).getOrDefault(categoryId, BigDecimal.ZERO).max(BigDecimal.ZERO);
                BigDecimal amount = budget != null ? budget.getAmount() : null;
                cells.add(new BudgetHistoryResponse.Cell(month, amount, spent, amount != null ? percent(spent, amount) : null));
            }
            items.add(new BudgetHistoryResponse.Item(
                    category.getId(), category.getName(), category.getIcon(), category.getColor(), cells));
        }
        items.sort(Comparator.comparing(BudgetHistoryResponse.Item::name));
        return new BudgetHistoryResponse(range, items);
    }

    /** Cria ou troca o teto padrão ({@code month} nulo) ou o ajuste de um mês. */
    @Transactional
    public CategoryBudgetResponse save(UUID userId, UUID categoryId, BigDecimal amount, YearMonth month) {
        Category category = categoryRepository.findByIdVisibleToUser(categoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
        if (category.isArchived()) {
            throw new BusinessRuleException("Categoria arquivada não pode ter orçamento.");
        }
        LocalDate monthStart = month != null ? month.atDay(1) : null;
        CategoryBudget budget = budgetRepository.findOne(userId, categoryId, monthStart).orElseGet(() -> {
            CategoryBudget created = new CategoryBudget();
            created.setUserId(userId);
            created.setCategoryId(categoryId);
            created.setMonth(monthStart);
            return created;
        });
        budget.setAmount(amount);
        budgetRepository.save(budget);
        return new CategoryBudgetResponse(categoryId, amount, month);
    }

    @Transactional
    public void delete(UUID userId, UUID categoryId, YearMonth month) {
        CategoryBudget budget = budgetRepository.findOne(userId, categoryId, month != null ? month.atDay(1) : null)
                .orElseThrow(() -> new ResourceNotFoundException("Budget not found for category: " + categoryId));
        budgetRepository.delete(budget);
    }

    /** Gasto realizado e previsto por categoria no mês, e o realizado sem categoria. */
    private record Spending(Map<UUID, BigDecimal> spent, Map<UUID, BigDecimal> forecast, BigDecimal uncategorized) {}

    private Spending spending(UUID userId, YearMonth month, Map<UUID, Category> categories, AccountScope scope) {
        return spending(transactionRepository.sumBudgetSpending(userId, month.atDay(1), month.atEndOfMonth(), scope),
                categories);
    }

    /** Agrega as linhas de {@link TransactionRepository#sumBudgetSpending}. */
    private static Spending spending(List<Object[]> rows, Map<UUID, Category> categories) {
        Map<UUID, BigDecimal> spent = new HashMap<>();
        Map<UUID, BigDecimal> forecast = new HashMap<>();
        BigDecimal uncategorized = BigDecimal.ZERO;
        for (Object[] row : rows) {
            UUID categoryId = (UUID) row[0];
            UUID subcategoryId = (UUID) row[1];
            TransactionType type = (TransactionType) row[2];
            TransactionStatus status = (TransactionStatus) row[3];
            boolean onCard = (Boolean) row[4];
            BigDecimal amount = (BigDecimal) row[5];

            boolean realized = status == TransactionStatus.PAID || onCard;
            BigDecimal signed = type == TransactionType.REFUND ? amount.negate() : amount;
            if (!realized && type == TransactionType.REFUND) {
                continue;
            }
            if (categoryId == null) {
                if (realized) {
                    uncategorized = uncategorized.add(signed);
                }
                continue;
            }
            Map<UUID, BigDecimal> bucket = realized ? spent : forecast;
            for (UUID target : targets(categoryId, subcategoryId, categories)) {
                bucket.merge(target, signed, BigDecimal::add);
            }
        }
        return new Spending(spent, forecast, uncategorized);
    }

    private static BigDecimal percent(BigDecimal spent, BigDecimal budget) {
        return spent.multiply(BigDecimal.valueOf(100)).divide(budget, 2, RoundingMode.HALF_UP);
    }

    /** Um teto por categoria: o ajuste do mês, se houver, senão o padrão. */
    private Map<UUID, CategoryBudget> applicableBudgets(UUID userId, YearMonth month) {
        return applicableBudgets(budgetRepository.findApplicable(userId, month.atDay(1)));
    }

    /** Um teto por categoria entre os padrões e os ajustes de um único mês. */
    private static Map<UUID, CategoryBudget> applicableBudgets(List<CategoryBudget> budgets) {
        Map<UUID, CategoryBudget> byCategory = new HashMap<>();
        for (CategoryBudget budget : budgets) {
            byCategory.merge(budget.getCategoryId(), budget, (a, b) -> a.getMonth() != null ? a : b);
        }
        return byCategory;
    }

    private Map<UUID, Category> visibleCategories(UUID userId) {
        Map<UUID, Category> categories = new HashMap<>();
        categoryRepository.findAllSystemCategories().forEach(c -> categories.put(c.getId(), c));
        categoryRepository.findAllByUserId(userId).forEach(c -> categories.put(c.getId(), c));
        return categories;
    }

    /**
     * Categorias que recebem o valor da transação: a categoria, a subcategoria e a pai da
     * categoria quando a própria transação já foi lançada direto numa subcategoria.
     */
    private static Set<UUID> targets(UUID categoryId, UUID subcategoryId, Map<UUID, Category> categories) {
        Set<UUID> targets = new LinkedHashSet<>();
        targets.add(categoryId);
        if (subcategoryId != null) {
            targets.add(subcategoryId);
        }
        Category category = categories.get(categoryId);
        if (category != null && category.getParent() != null) {
            targets.add(category.getParent().getId());
        }
        return targets;
    }

    private static int elapsedDays(YearMonth month, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (month.isBefore(current)) {
            return month.lengthOfMonth();
        }
        return month.equals(current) ? today.getDayOfMonth() : 0;
    }
}
