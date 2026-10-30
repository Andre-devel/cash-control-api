package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Gross value shown by the bank on a date. Both null clears it.")
public record ManualValueRequest(
        @Schema(example = "1052.37") @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal value,
        @Schema(example = "2026-10-29") LocalDate date
) {}
