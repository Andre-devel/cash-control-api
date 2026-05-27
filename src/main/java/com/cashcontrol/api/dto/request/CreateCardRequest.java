package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.CardBrand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Request body for registering a new credit card")
public record CreateCardRequest(
        @Schema(description = "User-defined card name, unique per user", required = true, example = "Nubank Platinum")
        @NotBlank @Size(max = 100) String name,

        @Schema(description = "Card network brand", required = true)
        @NotNull CardBrand brand,

        @Schema(description = "Issuing bank or institution name", example = "Nubank")
        @Size(max = 100) String issuer,

        @Schema(description = "Total credit limit for this card. Serialized as decimal string.", required = true, example = "10000.00")
        @NotNull @Digits(integer = 17, fraction = 2) @DecimalMin("0.01") BigDecimal creditLimit,

        @Schema(description = "Day of the month (1–28) when the billing cycle closes. Charges after this day go to the next invoice.", required = true, example = "20")
        @NotNull @Min(1) @Max(28) Integer closingDay,

        @Schema(description = "Day of the month (1–28) when the invoice payment is due.", required = true, example = "27")
        @NotNull @Min(1) @Max(28) Integer dueDay,

        @Schema(description = "Optional shared limit group UUID. When set, this card's limit is pooled with other cards in the group.")
        UUID sharedLimitGroupId
) {}
