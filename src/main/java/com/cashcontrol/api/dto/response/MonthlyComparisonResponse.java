package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MonthlyComparisonResponse(
        MonthMetrics month1,
        MonthMetrics month2,
        Delta delta
) {
    public record MonthMetrics(
            String month,
            BigDecimal income,
            BigDecimal expenses,
            BigDecimal savings,
            List<CategoryEntry> categoryBreakdown
    ) {}

    public record CategoryEntry(
            UUID categoryId,
            String categoryName,
            BigDecimal totalAmount,
            BigDecimal percentage
    ) {}

    public record Delta(
            BigDecimal incomeDelta,
            BigDecimal expensesDelta,
            BigDecimal savingsDelta,
            BigDecimal incomeChangePercent,
            BigDecimal expensesChangePercent,
            BigDecimal savingsChangePercent
    ) {}
}
