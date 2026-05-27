package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.RecurrenceFrequency;
import com.cashcontrol.api.domain.entity.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Request body for creating a recurring transaction rule")
public record CreateRecurrenceRequest(
        @Schema(description = "UUID of the account where recurring transactions will be recorded", required = true)
        @NotNull UUID accountId,

        @Schema(description = "Transaction type for generated instances", required = true)
        @NotNull TransactionType type,

        @Schema(description = "Amount for each generated transaction instance. Serialized as decimal string.", required = true, example = "500.00")
        @NotNull @Positive BigDecimal amount,

        @Schema(description = "Description applied to all generated transaction instances", required = true, example = "Monthly rent")
        @NotBlank @Size(max = 255) String description,

        @Schema(description = "Primary category UUID for generated transactions")
        UUID categoryId,

        @Schema(description = "Subcategory UUID. Must be a child of categoryId when provided.")
        UUID subcategoryId,

        @Schema(description = "Date of the first occurrence. The first transaction instance is created immediately for this date.", required = true, example = "2026-06-01")
        @NotNull LocalDate startDate,

        @Schema(description = "Optional end date. Null for open-ended recurrences that generate indefinitely until paused or deleted.", example = "2027-06-01")
        LocalDate endDate,

        @Schema(description = "Recurrence frequency determining the interval between generated instances", required = true)
        @NotNull RecurrenceFrequency frequency
) {}
