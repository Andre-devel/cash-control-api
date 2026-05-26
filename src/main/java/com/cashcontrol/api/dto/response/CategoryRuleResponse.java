package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CategoryRuleResponse(
        UUID id,
        UUID userId,
        String pattern,
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        UUID accountId,
        String accountName,
        int priority,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {}
