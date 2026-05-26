package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InstallmentSeriesResponse(
        UUID id,
        UUID accountId,
        String accountName,
        TransactionType type,
        String description,
        BigDecimal totalAmount,
        int totalInstallments,
        LocalDate firstPaymentDate,
        UUID categoryId,
        String categoryName,
        boolean settled,
        Instant settledAt,
        Instant createdAt,
        Instant updatedAt
) {}
