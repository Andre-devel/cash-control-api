package com.cashcontrol.api.dto.request;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Savings goal. Linked to at most one account or one fixed-income holding, which provides the current value.")
public record SavingsGoalRequest(
        @Schema(example = "Reserva de emergência") @NotBlank @Size(max = 100) String name,
        @Schema(example = "20000.00") @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal targetAmount,
        @Schema(description = "Optional deadline; without it monthlyNeeded is null", example = "2027-12-31") LocalDate targetDate,
        @Schema(description = "Account whose balance is the current value. Mutually exclusive with holdingId.") UUID accountId,
        @Schema(description = "Holding whose net value is the current value. Mutually exclusive with accountId.") UUID holdingId,
        @Size(max = 50) String icon,
        @Size(max = 20) String color
) {}
