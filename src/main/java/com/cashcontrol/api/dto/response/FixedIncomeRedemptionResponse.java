package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FixedIncomeRedemptionResponse(
        UUID id,
        LocalDate redeemedAt,
        BigDecimal principalPortion,
        BigDecimal treasuryQuantity,
        BigDecimal gross,
        BigDecimal incomeTax,
        BigDecimal iof,
        BigDecimal net,
        UUID yieldTransactionId,
        UUID transferGroupId
) {}
