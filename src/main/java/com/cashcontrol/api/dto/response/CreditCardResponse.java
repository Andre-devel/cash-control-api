package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.CardBrand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Credit card details")
public record CreditCardResponse(
        @Schema(description = "Credit card UUID") UUID id,
        @Schema(description = "User-defined card name", example = "Nubank Platinum") String name,
        @Schema(description = "Card network brand") CardBrand brand,
        @Schema(description = "Issuing bank or institution name", example = "Nubank") String issuer,
        @Schema(description = "Last four digits printed on the card. Null when not informed.", example = "7866") String last4Digits,
        @Schema(description = "Total credit limit. Serialized as decimal string.", example = "10000.00") BigDecimal creditLimit,
        @Schema(description = "Day of month (1–28) when the billing cycle closes", example = "20") int closingDay,
        @Schema(description = "Day of month (1–28) when the invoice payment is due", example = "27") int dueDay,
        @Schema(description = "Shared limit group UUID. Null if this card has an independent limit.") UUID sharedLimitGroupId,
        @Schema(description = "Timestamp when the card was archived. Null if active.") Instant archivedAt,
        @Schema(description = "Record creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt
) {}
