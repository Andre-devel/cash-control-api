package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record EditInstallmentRequest(
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description,
        @Size(max = 5000) String notes,
        LocalDate paymentDate,
        UUID categoryId,
        UUID subcategoryId
) {}
