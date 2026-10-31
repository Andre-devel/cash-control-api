package com.cashcontrol.api.service.budget;

/**
 * @author Andre
 * 31 de Outubro. de 2026
 */

import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryBudget;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
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
        when(transactionRepository.sumBudgetSpending(userId, OCTOBER.atDay(1), OCTOBER.atEndOfMonth())).thenReturn(List.of());

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

    private void stubEmpty() {
        when(budgetRepository.findApplicable(any(), any())).thenReturn(List.of());
        when(categoryRepository.findAllSystemCategories()).thenReturn(List.of());
        when(categoryRepository.findAllByUserId(userId)).thenReturn(List.of());
        when(transactionRepository.sumBudgetSpending(any(), any(), any())).thenReturn(List.of());
    }
}
