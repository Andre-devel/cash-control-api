package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Schema(description = "Full transaction details including installment, recurrence, and category context")
public record TransactionDetailResponse(
        @Schema(description = "Transaction UUID") UUID id,
        @Schema(description = "Account UUID") UUID accountId,
        @Schema(description = "Account name at time of transaction") String accountName,
        @Schema(description = "Transaction type") TransactionType type,
        @Schema(description = "Current payment status") TransactionStatus status,
        @Schema(description = "Transaction amount (always positive). Direction encoded by type.", example = "150.00") BigDecimal amount,
        @Schema(description = "Short transaction label", example = "Supermarket purchase") String description,
        @Schema(description = "Free-form notes") String notes,
        @Schema(description = "Accrual date — when the financial event occurred", example = "2026-05-15") LocalDate competenceDate,
        @Schema(description = "Settlement date — when payment was actually made. Null for PENDING transactions.", example = "2026-05-15") LocalDate paymentDate,
        @Schema(description = "Primary category UUID") UUID categoryId,
        @Schema(description = "Primary category name") String categoryName,
        @Schema(description = "Subcategory UUID") UUID subcategoryId,
        @Schema(description = "Subcategory name") String subcategoryName,
        @Schema(description = "Tags associated with this transaction") Set<TagResponse> tags,
        @Schema(description = "Optional geolocation or address") String location,
        @Schema(description = "Shared UUID linking both legs of a transfer. Null for non-transfer transactions.") UUID transferGroupId,
        @Schema(description = "Parent installment series UUID. Null for non-installment transactions.") UUID installmentSeriesId,
        @Schema(description = "1-based installment position within the series. Null for non-installment transactions.", example = "2") Integer installmentNumber,
        @Schema(description = "Total installments in the series. Null for non-installment transactions.", example = "12") Integer totalInstallments,
        @Schema(description = "True when this installment was individually edited and is no longer managed by series-wide operations.") boolean detached,
        @Schema(description = "Timestamp when the transaction was cancelled. Null if not cancelled.") Instant cancelledAt,
        @Schema(description = "Record creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt,
        @Schema(description = "Payment method used for this transaction") PaymentMethodResponse paymentMethod,
        @Schema(description = "Credit card linked to this transaction. Non-null only when paymentMethod is CREDIT_CARD.") CreditCardRefResponse creditCard
) {}
