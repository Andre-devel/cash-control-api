package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayInvoiceRequest(
        @NotNull @Digits(integer = 17, fraction = 2) @DecimalMin("0.01") BigDecimal amount,
        @NotNull UUID sourceAccountId,
        LocalDate paymentDate
) {}
