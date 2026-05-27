package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.ChartGranularity;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DashboardService {

    OverviewMetricsResponse getOverviewMetrics(UUID userId);

    CategoryPieChartResponse getCategoryPieChart(
            UUID userId, LocalDate from, LocalDate to, UUID accountId, TransactionType type);

    MonthlyBarChartResponse getMonthlyBarChart(UUID userId, int months, UUID accountId);

    NetWorthEvolutionResponse getNetWorthEvolution(
            UUID userId, LocalDate from, LocalDate to, ChartGranularity granularity);

    MonthlyComparisonResponse getMonthlyComparison(UUID userId, String month1, String month2);

    List<UpcomingBillResponse> getUpcomingBills(UUID userId, int daysAhead);

    List<UpcomingInvoiceResponse> getUpcomingInvoices(UUID userId, int daysAhead);

    List<LargestExpenseResponse> getLargestExpenses(UUID userId, LocalDate from, LocalDate to, int limit);

    List<RecentTransactionResponse> getRecentTransactions(UUID userId, int limit);
}
