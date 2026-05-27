package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Transaction summary entry returned in paginated list results")
public record TransactionSummaryResponse(
        @Schema(description = "Transaction UUID") UUID id,
        @Schema(description = "Account UUID") UUID accountId,
        @Schema(description = "Account name") String accountName,
        @Schema(description = "Transaction type") TransactionType type,
        @Schema(description = "Current payment status") TransactionStatus status,
        @Schema(description = "Transaction amount. Serialized as decimal string.", example = "150.00") BigDecimal amount,
        @Schema(description = "Short transaction label", example = "Supermarket purchase") String description,
        @Schema(description = "Accrual date — when the financial event occurred", example = "2026-05-15") LocalDate competenceDate,
        @Schema(description = "Settlement date — when payment was actually made. Null for PENDING transactions.", example = "2026-05-15") LocalDate paymentDate,
        @Schema(description = "Primary category UUID") UUID categoryId,
        @Schema(description = "Primary category name") String categoryName,
        @Schema(description = "Record creation timestamp") Instant createdAt
) {}
