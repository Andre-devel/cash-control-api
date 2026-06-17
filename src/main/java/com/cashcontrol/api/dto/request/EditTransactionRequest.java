package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EditTransactionRequest(
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description,
        @Size(max = 5000) String notes,
        LocalDate competenceDate,
        LocalDate paymentDate,
        TransactionStatus status,
        UUID categoryId,
        UUID subcategoryId,
        List<UUID> tagIds,
        @Size(max = 255) String location,
        PaymentMethodSlug paymentMethod,
        UUID creditCardId
) {}
