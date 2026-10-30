package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.FixedIncomeIndexer;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.domain.entity.FixedIncomeLiquidity;
import com.cashcontrol.api.domain.entity.FixedIncomeStatus;
import com.cashcontrol.api.service.investment.Valuation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FixedIncomeHoldingResponse(
        UUID id,
        UUID accountId,
        String accountName,
        FixedIncomeKind kind,
        FixedIncomeIndexer indexer,
        BigDecimal rate,
        String name,
        String issuer,
        boolean taxExempt,
        FixedIncomeLiquidity liquidity,
        LocalDate appliedAt,
        LocalDate maturityDate,
        BigDecimal principal,
        BigDecimal remainingPrincipal,
        String treasuryBondType,
        BigDecimal treasuryQuantity,
        BigDecimal manualValue,
        LocalDate manualValueDate,
        FixedIncomeStatus status,
        String notes,
        @Schema(description = "Value today, with the taxes of a redemption today. Null once fully redeemed.")
        Valuation valuation,
        @Schema(description = "Gross return over the remaining principal, in percent.", example = "6.42")
        BigDecimal yieldPercent,
        @Schema(description = "Return as a percent of 100% CDI over the same period; null when CDI accrued nothing.", example = "109.8")
        BigDecimal cdiPercent,
        @Schema(description = "Calendar days until maturity; null without maturity date.")
        Long daysToMaturity,
        List<FixedIncomeRedemptionResponse> redemptions
) {}
