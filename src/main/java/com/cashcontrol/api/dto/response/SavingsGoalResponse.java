package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SavingsGoalResponse(
        UUID id,
        String name,
        BigDecimal targetAmount,
        LocalDate targetDate,
        UUID accountId,
        UUID holdingId,
        String icon,
        String color,
        @Schema(description = "Balance of the linked account or net value of the linked holding; 0 without a link.", example = "5000.00")
        BigDecimal current,
        @Schema(description = "current / targetAmount, in percent (not capped at 100, never below 0).", example = "25.00")
        BigDecimal percent,
        @Schema(description = "(targetAmount − current) / months until targetDate; null without targetDate, 0 once reached.", example = "1250.00")
        BigDecimal monthlyNeeded,
        Instant createdAt,
        Instant archivedAt
) {}
