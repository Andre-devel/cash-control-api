package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.CategoryBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryBudgetRepository extends JpaRepository<CategoryBudget, UUID> {

    /** Tetos padrão do usuário e os ajustes do mês pedido (dia 1). */
    @Query("SELECT b FROM CategoryBudget b WHERE b.userId = :userId AND (b.month IS NULL OR b.month = :month)")
    List<CategoryBudget> findApplicable(@Param("userId") UUID userId, @Param("month") LocalDate month);

    /** O teto padrão quando {@code month} é nulo, senão o ajuste daquele mês (dia 1). */
    default Optional<CategoryBudget> findOne(UUID userId, UUID categoryId, LocalDate month) {
        // Separado em dois porque o Postgres não infere o tipo de um parâmetro nulo em "? IS NULL".
        return month == null
                ? findByUserIdAndCategoryIdAndMonthIsNull(userId, categoryId)
                : findByUserIdAndCategoryIdAndMonth(userId, categoryId, month);
    }

    Optional<CategoryBudget> findByUserIdAndCategoryIdAndMonthIsNull(UUID userId, UUID categoryId);

    Optional<CategoryBudget> findByUserIdAndCategoryIdAndMonth(UUID userId, UUID categoryId, LocalDate month);
}
