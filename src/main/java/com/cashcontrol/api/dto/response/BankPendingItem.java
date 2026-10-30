package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Bank transaction waiting for confirmation, classified like a statement preview row")
public record BankPendingItem(
        UUID id,
        UUID accountId,
        @Schema(example = "Inter") String accountName,
        @Schema(description = "Classification and suggestions, same shape as the import preview") ImportPreviewRow row
) {}
