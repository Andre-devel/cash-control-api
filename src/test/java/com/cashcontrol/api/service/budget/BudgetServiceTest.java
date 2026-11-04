package com.cashcontrol.api.service.budget;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryBudget;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.response.BudgetHistoryResponse;
import com.cashcontrol.api.dto.response.BudgetProgressResponse;
import com.cashcontrol.api.repository.CategoryBudgetRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Mock private CategoryBudgetRepository budgetRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private TransactionRepository transactionRepository;
    @InjectMocks private BudgetService budgetService;

    private final UUID userId = UUID.randomUUID();
    private Category category;

    @BeforeEach
    void setUp() {
        category = new Category();
        ReflectionTestUtils.setField(category, "id", UUID.randomUUID());
        category.setUserId(userId);
        category.setName("Lazer");
    }

    @Test
    void elapsedDays_followTheCalendar_forPastCurrentAndFutureMonths() {
        stubEmpty();
        LocalDate today = LocalDate.of(2026, 10, 12);

        assertThat(budgetService.progress(userId, OCTOBER, today).dayOfMonth()).isEqualTo(12);
        assertThat(budgetService.progress(userId, YearMonth.of(2026, 9), today).dayOfMonth()).isEqualTo(30);
        assertThat(budgetService.progress(userId, YearMonth.of(2026, 11), today).dayOfMonth()).isZero();
    }

    @Test
    void archivedCategory_dropsOutOfTheProgress() {
        category.setArchived(true);
        CategoryBudget budget = new CategoryBudget();
        budget.setCategoryId(category.getId());
        budget.setAmount(new BigDecimal("300.00"));
        when(budgetRepository.findApplicable(userId, OCTOBER.atDay(1))).thenReturn(List.of(budget));
        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(category));
        when(transactionRepository.sumBudgetSpending(userId, OCTOBER.atDay(1), OCTOBER.atEndOfMonth(), null)).thenReturn(List.of());

        BudgetProgressResponse progress = budgetService.progress(userId, OCTOBER, LocalDate.of(2026, 10, 12));

        assertThat(progress.items()).isEmpty();
        assertThat(progress.totalBudget()).isEqualByComparingTo("0");
    }

    @Test
    void save_rejectsArchivedCategory() {
        category.setArchived(true);
        when(categoryRepository.findByIdVisibleToUser(category.getId(), userId)).thenReturn(Optional.of(category));

        assertThatThrownBy(() -> budgetService.save(userId, category.getId(), BigDecimal.TEN, null))
                .isInstanceOf(BusinessRuleException.class);
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void history_followsTheCapOfEachMonth_andTheSpendingByPurchaseMonth() {
        YearMonth august = YearMonth.of(2026, 8);
        YearMonth september = YearMonth.of(2026, 9);
        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(category));
        // Teto padrão de 400; ajustes de 300 em agosto e de 200 em setembro. Uma consulta de tetos e
        // uma de gasto para os três meses.
        when(budgetRepository.findApplicableBetween(userId, august.atDay(1), OCTOBER.atDay(1)))
                .thenReturn(List.of(budget(null, "400.00"), budget(august, "300.00"), budget(september, "200.00")));
        when(transactionRepository.sumBudgetSpendingByMonth(userId, august.atDay(1), OCTOBER.atEndOfMonth(), null))
                .thenReturn(rows(
                        monthRow(august, row(TransactionType.EXPENSE, TransactionStatus.PAID, false, "150.00")),
                        monthRow(september, row(TransactionType.EXPENSE, TransactionStatus.PAID, false, "180.00")),
                        monthRow(september, row(TransactionType.EXPENSE, TransactionStatus.PENDING, true, "70.00")),
                        monthRow(september, row(TransactionType.REFUND, TransactionStatus.PAID, false, "10.00")),
                        // Conta a vencer é previsto, não gasto.
                        monthRow(september, row(TransactionType.EXPENSE, TransactionStatus.PENDING, false, "999.00")),
                        monthRow(OCTOBER, row(TransactionType.EXPENSE, TransactionStatus.PAID, true, "100.00"))));

        BudgetHistoryResponse history = budgetService.history(userId, OCTOBER, 3);

        assertThat(history.months()).containsExactly(august, september, OCTOBER);
        assertThat(history.items()).hasSize(1);
        BudgetHistoryResponse.Item item = history.items().getFirst();
        assertThat(item.categoryId()).isEqualTo(category.getId());
        assertThat(item.name()).isEqualTo("Lazer");
        assertThat(item.cells()).extracting(BudgetHistoryResponse.Cell::month).containsExactly(august, september, OCTOBER);
        assertCell(item.cells().get(0), "300.00", "150.00", "50.00");
        assertCell(item.cells().get(1), "200.00", "240.00", "120.00");
        assertCell(item.cells().get(2), "400.00", "100.00", "25.00");
    }

    @Test
    void history_monthWithoutCap_hasNullBudgetAndPercent_butStillShowsTheSpending() {
        YearMonth september = YearMonth.of(2026, 9);
        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of(category));
        when(budgetRepository.findApplicableBetween(userId, september.atDay(1), OCTOBER.atDay(1)))
                .thenReturn(List.of(budget(OCTOBER, "100.00")));
        when(transactionRepository.sumBudgetSpendingByMonth(userId, september.atDay(1), OCTOBER.atEndOfMonth(), null))
                .thenReturn(rows(monthRow(september, row(TransactionType.EXPENSE, TransactionStatus.PAID, false, "80.00"))));

        BudgetHistoryResponse history = budgetService.history(userId, OCTOBER, 2);

        BudgetHistoryResponse.Cell withoutCap = history.items().getFirst().cells().getFirst();
        assertThat(withoutCap.budget()).isNull();
        assertThat(withoutCap.percent()).isNull();
        assertThat(withoutCap.spent()).isEqualByComparingTo("80.00");
        assertCell(history.items().getFirst().cells().get(1), "100.00", "0", "0");
    }

    @Test
    void history_rejectsMonthsOutsideOneToTwelve() {
        assertThatThrownBy(() -> budgetService.history(userId, OCTOBER, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budgetService.history(userId, OCTOBER, 13)).isInstanceOf(IllegalArgumentException.class);
    }

    private CategoryBudget budget(YearMonth month, String amount) {
        CategoryBudget budget = new CategoryBudget();
        budget.setUserId(userId);
        budget.setCategoryId(category.getId());
        budget.setMonth(month != null ? month.atDay(1) : null);
        budget.setAmount(new BigDecimal(amount));
        return budget;
    }

    private Object[] row(TransactionType type, TransactionStatus status, boolean onCard, String amount) {
        return new Object[]{category.getId(), null, type, status, onCard, new BigDecimal(amount)};
    }

    /** A linha de {@code sumBudgetSpendingByMonth}: ano e mês da compra antes das colunas de {@link #row}. */
    private static Object[] monthRow(YearMonth month, Object[] row) {
        Object[] withMonth = new Object[row.length + 2];
        withMonth[0] = month.getYear();
        withMonth[1] = month.getMonthValue();
        System.arraycopy(row, 0, withMonth, 2, row.length);
        return withMonth;
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    private static void assertCell(BudgetHistoryResponse.Cell cell, String budget, String spent, String percent) {
        assertThat(cell.budget()).isEqualByComparingTo(budget);
        assertThat(cell.spent()).isEqualByComparingTo(spent);
        assertThat(cell.percent()).isEqualByComparingTo(percent);
    }

    private void stubEmpty() {
        when(budgetRepository.findApplicable(any(), any())).thenReturn(List.of());
        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of());
        when(transactionRepository.sumBudgetSpending(any(), any(), any(), any())).thenReturn(List.of());
    }
}
