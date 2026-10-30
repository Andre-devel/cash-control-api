package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Aggregated financial health metrics for the authenticated user")
public record OverviewMetricsResponse(
        @Schema(description = "Sum of PAID transactions across all non-archived, non-investment accounts. Serialized as decimal string.", example = "8500.00") BigDecimal totalBalance,
        @Schema(description = "Total balance plus investment account balances, with fixed-income holdings at their value net of income tax and IOF. Serialized as decimal string.", example = "25000.00") BigDecimal netWorth,
        @Schema(description = "Total PAID INCOME transactions with paymentDate in the current calendar month. Serialized as decimal string.", example = "6000.00") BigDecimal monthlyIncome,
        @Schema(description = "Total PAID EXPENSE transactions with paymentDate in the current calendar month. Serialized as decimal string.", example = "3200.00") BigDecimal monthlyExpenses,
        @Schema(description = "monthlyIncome minus monthlyExpenses. Serialized as decimal string.", example = "2800.00") BigDecimal monthlySavings,
        @Schema(description = "Net income minus expense movement for the current month. Serialized as decimal string.", example = "2800.00") BigDecimal cashFlow,
        @Schema(description = "Current calendar month in YYYY-MM format", example = "2026-05") String currentMonth,
        @Schema(description = "netWorth at the last day of the previous month. Serialized as decimal string.", example = "24000.00") BigDecimal previousNetWorth,
        @Schema(description = "monthlyIncome of the previous calendar month. Serialized as decimal string.", example = "5800.00") BigDecimal previousMonthIncome,
        @Schema(description = "monthlyExpenses of the previous calendar month. Serialized as decimal string.", example = "3500.00") BigDecimal previousMonthExpenses,
        @Schema(description = "Gross value of the active fixed-income holdings today. Serialized as decimal string.", example = "15400.00") BigDecimal investmentsGross,
        @Schema(description = "What redeeming every active holding today would pay out, after income tax and IOF. Serialized as decimal string.", example = "15200.00") BigDecimal investmentsNet,
        @Schema(description = "Gross yield of the active holdings since the end of the previous month. Serialized as decimal string.", example = "120.00") BigDecimal investmentsYieldMonth,
        @Schema(description = "Remaining amount of every unpaid credit-card invoice, future ones included. Serialized as decimal string.", example = "2300.00") BigDecimal openCardDebt
) {}
