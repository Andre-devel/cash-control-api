package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateInstallmentRequest(
        @NotNull UUID accountId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal totalAmount,
        @NotNull @Min(2) @Max(360) Integer totalInstallments,
        @NotNull LocalDate firstPaymentDate,
        @NotBlank @Size(max = 255) String description,
        @Size(max = 5000) String notes,
        UUID categoryId,
        UUID subcategoryId
) {}
