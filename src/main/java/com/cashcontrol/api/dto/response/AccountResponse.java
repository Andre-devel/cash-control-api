package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.AccountType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        AccountType type,
        String currencyCode,
        String description,
        int sortOrder,
        BigDecimal balance,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt
) {}
