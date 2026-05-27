package com.cashcontrol.api.controller;

import com.cashcontrol.api.domain.entity.ChartGranularity;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.response.CategoryPieChartResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.LargestExpenseResponse;
import com.cashcontrol.api.dto.response.MonthlyBarChartResponse;
import com.cashcontrol.api.dto.response.MonthlyComparisonResponse;
import com.cashcontrol.api.dto.response.NetWorthEvolutionResponse;
import com.cashcontrol.api.dto.response.OverviewMetricsResponse;
import com.cashcontrol.api.dto.response.RecentTransactionResponse;
import com.cashcontrol.api.dto.response.UpcomingBillResponse;
import com.cashcontrol.api.dto.response.UpcomingInvoiceResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Dashboard", description = "Aggregated financial overview metrics, chart data, and configurable widgets")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "Overview metrics", description = "Returns total balance, net worth, monthly income, expenses, savings, and cash flow for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Metrics returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/overview")
    @PreAuthorize("isAuthenticated()")
    public OverviewMetricsResponse getOverviewMetrics(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getOverviewMetrics(principal.getUser().getId());
    }

    @Operation(summary = "Category pie chart data", description = "Returns expense (or income) distribution by category for a given date range, suitable for pie chart rendering.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chart data returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/charts/categories")
    @PreAuthorize("isAuthenticated()")
    public CategoryPieChartResponse getCategoryPieChart(
            @Parameter(description = "Start date (inclusive), ISO 8601") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive), ISO 8601") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Filter to a specific account") @RequestParam(required = false) UUID accountId,
            @Parameter(description = "Transaction type to analyse (default: EXPENSE)") @RequestParam(required = false) TransactionType type,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getCategoryPieChart(principal.getUser().getId(), from, to, accountId, type);
    }

    @Operation(summary = "Monthly bar chart data", description = "Returns monthly income and expense totals for the last N months. Months with no transactions are included with zero values.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chart data returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/charts/monthly")
    @PreAuthorize("isAuthenticated()")
    public MonthlyBarChartResponse getMonthlyBarChart(
            @Parameter(description = "Number of months to include (max 24, default 6)") @RequestParam(defaultValue = "6") int months,
            @Parameter(description = "Filter to a specific account") @RequestParam(required = false) UUID accountId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getMonthlyBarChart(principal.getUser().getId(), months, accountId);
    }

    @Operation(summary = "Net worth evolution chart data", description = "Replays transaction history to compute net worth snapshots over the requested period.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Chart data returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/charts/net-worth")
    @PreAuthorize("isAuthenticated()")
    public NetWorthEvolutionResponse getNetWorthEvolution(
            @Parameter(description = "Start date (inclusive), ISO 8601") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive), ISO 8601") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Snapshot granularity: DAILY, WEEKLY, MONTHLY (default: MONTHLY)") @RequestParam(defaultValue = "MONTHLY") ChartGranularity granularity,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getNetWorthEvolution(principal.getUser().getId(), from, to, granularity);
    }

    @Operation(summary = "Monthly comparison data", description = "Compares income, expenses, and category breakdowns between two calendar months, including delta values.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Comparison data returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/charts/comparison")
    @PreAuthorize("isAuthenticated()")
    public MonthlyComparisonResponse getMonthlyComparison(
            @Parameter(description = "First month in YYYY-MM format") @RequestParam String month1,
            @Parameter(description = "Second month in YYYY-MM format") @RequestParam String month2,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getMonthlyComparison(principal.getUser().getId(), month1, month2);
    }

    @Operation(summary = "Upcoming bills widget", description = "Returns PENDING and OVERDUE transactions due within the specified number of days, sorted by urgency.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upcoming bills returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/widgets/upcoming-bills")
    @PreAuthorize("isAuthenticated()")
    public List<UpcomingBillResponse> getUpcomingBills(
            @Parameter(description = "Number of days ahead to look (default: configured value)") @RequestParam(defaultValue = "0") int daysAhead,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getUpcomingBills(principal.getUser().getId(), daysAhead);
    }

    @Operation(summary = "Upcoming invoices widget", description = "Returns credit card invoices due within the specified number of days that are not yet fully paid.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upcoming invoices returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/widgets/upcoming-invoices")
    @PreAuthorize("isAuthenticated()")
    public List<UpcomingInvoiceResponse> getUpcomingInvoices(
            @Parameter(description = "Number of days ahead to look (default: configured value)") @RequestParam(defaultValue = "0") int daysAhead,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getUpcomingInvoices(principal.getUser().getId(), daysAhead);
    }

    @Operation(summary = "Largest expenses widget", description = "Returns the top N largest PAID EXPENSE transactions for the given period.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Largest expenses returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/widgets/largest-expenses")
    @PreAuthorize("isAuthenticated()")
    public List<LargestExpenseResponse> getLargestExpenses(
            @Parameter(description = "Start date (inclusive), ISO 8601. Defaults to start of current month.") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "End date (inclusive), ISO 8601. Defaults to end of current month.") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Maximum number of results (default 5)") @RequestParam(defaultValue = "5") int limit,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getLargestExpenses(principal.getUser().getId(), from, to, limit);
    }

    @Operation(summary = "Recent transactions widget", description = "Returns the N most recent transactions across all active accounts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recent transactions returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/widgets/recent-transactions")
    @PreAuthorize("isAuthenticated()")
    public List<RecentTransactionResponse> getRecentTransactions(
            @Parameter(description = "Number of transactions to return (default 10)") @RequestParam(defaultValue = "10") int limit,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return dashboardService.getRecentTransactions(principal.getUser().getId(), limit);
    }
}
