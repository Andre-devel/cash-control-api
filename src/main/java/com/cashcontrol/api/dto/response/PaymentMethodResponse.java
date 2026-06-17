package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Payment method summary for use in transaction responses and lookup lists")
public record PaymentMethodResponse(
        @Schema(description = "Payment method UUID") UUID id,
        @Schema(description = "Canonical slug identifier") PaymentMethodSlug slug,
        @Schema(description = "Human-readable display name") String name
) {}
