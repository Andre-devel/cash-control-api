package com.cashcontrol.api.dto.request;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Recurrence suggestion to dismiss")
public record DismissRecurrenceSuggestionRequest(
        @Schema(description = "Pattern of the suggestion, as returned by GET /recurrences/suggestions", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "hostinger")
        @NotBlank @Size(max = 255) String pattern
) {}
