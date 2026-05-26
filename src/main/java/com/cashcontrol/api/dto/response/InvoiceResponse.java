package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID creditCardId,
        String referenceMonth,
        InvoiceStatus status,
        LocalDate closingDate,
        LocalDate dueDate,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        int page,
        int size,
        long totalItems,
        List<InvoiceItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {}
