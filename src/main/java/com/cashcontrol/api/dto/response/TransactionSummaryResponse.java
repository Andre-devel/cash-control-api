package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionSummaryResponse(
        UUID id,
        UUID accountId,
        String accountName,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String description,
        LocalDate competenceDate,
        LocalDate paymentDate,
        UUID categoryId,
        String categoryName,
        Instant createdAt
) {}
