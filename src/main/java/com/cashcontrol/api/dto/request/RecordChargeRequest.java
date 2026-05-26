package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecordChargeRequest(
        @NotBlank @Size(max = 255) String description,
        @NotNull @Digits(integer = 17, fraction = 2) @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate competenceDate,
        UUID categoryId,
        UUID subcategoryId,
        @Size(max = 2000) String notes
) {}
