package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.YearMonth;

@Schema(description = "Monthly spending cap of a category. Without month it is the default for every month.")
public record CategoryBudgetRequest(
        @Schema(example = "1200.00") @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Schema(description = "Only this month (YYYY-MM); null sets the default", example = "2026-12") YearMonth month
) {}
