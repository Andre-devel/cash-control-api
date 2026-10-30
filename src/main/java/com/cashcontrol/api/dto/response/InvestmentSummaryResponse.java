package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Active fixed-income holdings, valued today")
public record InvestmentSummaryResponse(
        BigDecimal totalPrincipal,
        BigDecimal totalGross,
        BigDecimal totalIncomeTax,
        BigDecimal totalIof,
        @Schema(description = "What a redemption of everything today would pay out") BigDecimal totalNet,
        BigDecimal totalYield,
        @Schema(description = "Some value is estimated (index not yet published, missing price)") boolean estimated,
        @Schema(description = "Gross value by FixedIncomeKind") List<AllocationSlice> byKind,
        @Schema(description = "Gross value by FixedIncomeIndexer; key MARKET for Tesouro, NONE for holdings without an indexer") List<AllocationSlice> byIndexer,
        @Schema(description = "Gross value by maturity bucket: DAILY, UP_TO_1Y, UP_TO_3Y, UP_TO_5Y, OVER_5Y") List<AllocationSlice> byMaturity,
        @Schema(description = "Next maturities in the coming 90 days") List<UpcomingMaturity> upcomingMaturities,
        @Schema(description = "Each active INVESTMENT account: cash balance at cost against the principal registered in holdings") List<AccountAllocation> accounts
) {

    public record AllocationSlice(String key, BigDecimal gross, BigDecimal percent) {
    }

    public record UpcomingMaturity(UUID holdingId, String name, LocalDate maturityDate, BigDecimal net) {
    }

    public record AccountAllocation(
            UUID accountId,
            String accountName,
            @Schema(description = "Account balance (cost basis)") BigDecimal balance,
            @Schema(description = "Remaining principal of the active holdings in the account") BigDecimal allocatedPrincipal,
            @Schema(description = "balance − allocatedPrincipal. Positive: money without a registered holding. Negative: a redemption not reconciled with a holding.")
            BigDecimal unallocated) {
    }
}
