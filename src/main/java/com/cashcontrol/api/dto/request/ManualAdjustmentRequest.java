package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ManualAdjustmentRequest(
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description,
        LocalDate date
) {}
