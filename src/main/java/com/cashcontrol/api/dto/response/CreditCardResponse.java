package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.CardBrand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditCardResponse(
        UUID id,
        String name,
        CardBrand brand,
        String issuer,
        BigDecimal creditLimit,
        int closingDay,
        int dueDay,
        UUID sharedLimitGroupId,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt
) {}
