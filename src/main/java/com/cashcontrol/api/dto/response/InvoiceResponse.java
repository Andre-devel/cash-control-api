package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Credit card invoice for a billing cycle, with paginated charge items")
public record InvoiceResponse(
        @Schema(description = "Invoice UUID") UUID id,
        @Schema(description = "Credit card UUID this invoice belongs to") UUID creditCardId,
        @Schema(description = "Billing cycle identifier in YYYY-MM format", example = "2026-05") String referenceMonth,
        @Schema(description = "Invoice payment status") InvoiceStatus status,
        @Schema(description = "Date the billing cycle closed. No new charges added after this date.", example = "2026-05-20") LocalDate closingDate,
        @Schema(description = "Payment due date", example = "2026-05-27") LocalDate dueDate,
        @Schema(description = "Sum of all non-cancelled charges in this cycle. Serialized as decimal string.", example = "1500.00") BigDecimal totalAmount,
        @Schema(description = "Amount paid. Zero for OPEN/CLOSED invoices; non-zero for PARTIAL/PAID.", example = "1000.00") BigDecimal paidAmount,
        @Schema(description = "True when PAID came from the simple settle action instead of a real payment transaction — the only case it is safe to reopen.") boolean paidWithoutTransaction,
        @Schema(description = "Current page number (0-based)", example = "0") int page,
        @Schema(description = "Page size", example = "20") int size,
        @Schema(description = "Total number of charge items in this invoice", example = "15") long totalItems,
        @Schema(description = "Paginated list of charge items for the current page") List<InvoiceItemResponse> items,
        @Schema(description = "Record creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt
) {}
