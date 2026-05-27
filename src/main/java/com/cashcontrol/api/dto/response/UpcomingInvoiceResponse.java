package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpcomingInvoiceResponse(
        UUID invoiceId,
        String cardName,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal remainingAmount,
        LocalDate dueDate,
        InvoiceStatus status
) {}
