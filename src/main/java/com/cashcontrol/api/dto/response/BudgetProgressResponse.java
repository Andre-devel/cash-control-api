package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Schema(description = "Spending against each category cap in a month, by purchase date (competence)")
public record BudgetProgressResponse(
        YearMonth month,
        @Schema(description = "Sum of the caps, counting a subcategory only when its parent has no cap")
        BigDecimal totalBudget,
        @Schema(description = "Spent in the categories behind totalBudget")
        BigDecimal totalSpent,
        @Schema(description = "Bills still to pay this month in those categories (not on a card)")
        BigDecimal totalForecast,
        @Schema(description = "Spent this month without a category, so outside any cap")
        BigDecimal uncategorizedSpent,
        @Schema(description = "Days of the month already elapsed: 0 for a future month, all for a past one")
        int dayOfMonth,
        int daysInMonth,
        @Schema(description = "Only categories with a cap, highest percent first")
        List<Item> items
) {

    public record Item(
            UUID categoryId,
            String name,
            String color,
            String icon,
            UUID parentId,
            BigDecimal budget,
            BigDecimal spent,
            BigDecimal forecast,
            BigDecimal remaining,
            BigDecimal percent,
            @Schema(description = "DEFAULT: cap for every month; MONTH: adjusted for this month only")
            Source source
    ) {}

    public enum Source { DEFAULT, MONTH }
}
