package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.CardBrand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Lightweight credit card reference embedded in transaction details")
public record CreditCardRefResponse(
        @Schema(description = "Credit card UUID") UUID id,
        @Schema(description = "Card display name") String name,
        @Schema(description = "Card brand") CardBrand brand
) {}
