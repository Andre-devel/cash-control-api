package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceItemResponse(
        UUID id,
        String description,
        BigDecimal amount,
        LocalDate competenceDate,
        UUID categoryId,
        String categoryName,
        UUID subcategoryId,
        String subcategoryName,
        String notes,
        boolean isRevolving,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {}
