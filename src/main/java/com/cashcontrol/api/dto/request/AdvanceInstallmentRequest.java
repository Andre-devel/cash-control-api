package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AdvanceInstallmentRequest(
        @NotNull @Size(min = 1) List<UUID> installmentIds,
        @NotNull LocalDate newPaymentDate,
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal adjustedAmount
) {}
