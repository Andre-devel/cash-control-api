package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Monthly subscriptions: active monthly expense recurrences plus non-dismissed recurrence suggestions")
public record SubscriptionsResponse(
        @Schema(description = "Sum of the item amounts. Serialized as decimal string.", example = "137.70")
        BigDecimal monthlyTotal,
        List<Item> items
) {

    public enum Source { RECURRENCE, SUGGESTION }

    @Schema(description = "One subscription")
    public record Item(
            String description,
            @Schema(description = "Monthly amount. Serialized as decimal string.", example = "39.90")
            BigDecimal amount,
            @Schema(description = "RECURRENCE for a registered recurrence, SUGGESTION for a detected one")
            Source source
    ) {}
}
