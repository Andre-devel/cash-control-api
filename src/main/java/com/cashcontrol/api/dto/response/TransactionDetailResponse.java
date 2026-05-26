package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record TransactionDetailResponse(
        UUID id,
        UUID accountId,
        String accountName,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String description,
        String notes,
        LocalDate competenceDate,
        LocalDate paymentDate,
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        Set<TagResponse> tags,
        String location,
        UUID transferGroupId,
        UUID installmentSeriesId,
        Integer installmentNumber,
        Integer totalInstallments,
        boolean detached,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {}
