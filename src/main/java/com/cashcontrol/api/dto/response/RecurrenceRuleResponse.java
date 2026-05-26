package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.RecurrenceStatus;
import com.cashcontrol.api.domain.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RecurrenceRuleResponse(
        UUID id,
        UUID accountId,
        String accountName,
        TransactionType type,
        RecurrenceFrequency frequency,
        RecurrenceStatus status,
        BigDecimal amount,
        String description,
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate nextOccurrenceDate,
        Instant pausedAt,
        Instant resumeAt,
        Instant createdAt,
        Instant updatedAt
) {}
