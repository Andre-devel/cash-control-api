package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Schema(description = "Cap against spending per category and month, by purchase date (competence)")
public record BudgetHistoryResponse(
        @Schema(description = "Months covered, oldest first")
        List<YearMonth> months,
        @Schema(description = "Categories with a cap in at least one of the months, by name")
        List<Item> items
) {

    public record Item(
            UUID categoryId,
            String name,
            String icon,
            String color,
            @Schema(description = "One cell per month, in the order of months")
            List<Cell> cells
    ) {}

    public record Cell(
            YearMonth month,
            @Schema(description = "Cap in force that month; null when the category had no cap then")
            BigDecimal budget,
            BigDecimal spent,
            @Schema(description = "spent / budget in percent; null without a cap")
            BigDecimal percent
    ) {}
}
