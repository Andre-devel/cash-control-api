package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What was saved, for the notification to say so")
public record BankPendingConfirmResponse(
        @Schema(example = "Pix Marketplace") String description,
        @Schema(example = "Alimentação") String categoryName
) {}
