package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Aggregated financial health metrics for the authenticated user")
public record OverviewMetricsResponse(
        @Schema(description = "Sum of PAID transactions across all non-archived, non-investment accounts. Serialized as decimal string.", example = "8500.00") BigDecimal totalBalance,
        @Schema(description = "Total balance plus investment account balances. Serialized as decimal string.", example = "25000.00") BigDecimal netWorth,
        @Schema(description = "Total PAID INCOME transactions with paymentDate in the current calendar month. Serialized as decimal string.", example = "6000.00") BigDecimal monthlyIncome,
        @Schema(description = "Total PAID EXPENSE transactions with paymentDate in the current calendar month. Serialized as decimal string.", example = "3200.00") BigDecimal monthlyExpenses,
        @Schema(description = "monthlyIncome minus monthlyExpenses. Serialized as decimal string.", example = "2800.00") BigDecimal monthlySavings,
        @Schema(description = "Net income minus expense movement for the current month. Serialized as decimal string.", example = "2800.00") BigDecimal cashFlow,
        @Schema(description = "Current calendar month in YYYY-MM format", example = "2026-05") String currentMonth
) {}
