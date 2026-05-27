package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Request body for recording a new financial transaction")
public record CreateTransactionRequest(
        @Schema(description = "UUID of the account this transaction belongs to", required = true)
        @NotNull UUID accountId,

        @Schema(description = "Transaction type. Use INCOME, EXPENSE, or REFUND. TRANSFER is created via the dedicated transfers endpoint.", required = true)
        @NotNull TransactionType type,

        @Schema(description = "Positive transaction amount. Direction is encoded by the type field.", required = true, example = "250.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,

        @Schema(description = "Short human-readable label for this transaction", required = true, example = "Supermarket purchase")
        @NotBlank @Size(max = 255) String description,

        @Schema(description = "Date the financial event occurred (accrual date). Used in cash flow reports.", required = true, example = "2026-05-15")
        @NotNull LocalDate competenceDate,

        @Schema(description = "Date the payment was actually settled. Defaults to competenceDate when status is PAID and no explicit date is provided.", example = "2026-05-15")
        LocalDate paymentDate,

        @Schema(description = "Free-form long-form notes or observations", example = "Weekly grocery run")
        @Size(max = 5000) String notes,

        @Schema(description = "Primary category UUID. Must belong to the authenticated user or be a system default.")
        UUID categoryId,

        @Schema(description = "Subcategory UUID. Must be a child of categoryId when provided.")
        UUID subcategoryId,

        @Schema(description = "List of tag UUIDs to associate with this transaction")
        List<UUID> tagIds,

        @Schema(description = "Optional geolocation or address associated with this transaction", example = "Carrefour Paulista, São Paulo")
        @Size(max = 255) String location,

        @Schema(description = "Transaction status. Defaults to PAID. Use PENDING for future obligations.", example = "PAID")
        TransactionStatus status
) {}
