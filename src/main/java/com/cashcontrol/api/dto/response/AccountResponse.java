package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Financial account details including computed balance")
public record AccountResponse(
        @Schema(description = "Account UUID") UUID id,
        @Schema(description = "User-defined account name", example = "Nubank Checking") String name,
        @Schema(description = "Account type") AccountType type,
        @Schema(description = "ISO 4217 currency code", example = "BRL") String currencyCode,
        @Schema(description = "Optional account description") String description,
        @Schema(description = "User-defined display order", example = "0") int sortOrder,
        @Schema(description = "Computed balance from PAID transactions. Serialized as decimal string.", example = "1500.75") BigDecimal balance,
        @Schema(description = "Timestamp when the account was archived, or null if active") Instant archivedAt,
        @Schema(description = "Account creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt
) {}
