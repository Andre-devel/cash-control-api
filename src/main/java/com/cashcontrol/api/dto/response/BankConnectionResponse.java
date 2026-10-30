package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Consent given to a bank through Pluggy")
public record BankConnectionResponse(
        UUID id,
        @Schema(description = "Pluggy item id; the widget takes it as updateItem to renew the consent") String itemId,
        @Schema(example = "Inter") String connectorName,
        @Schema(description = "Pluggy item status", example = "UPDATED") String status,
        @Schema(description = "Error of the last update, if any") String statusDetail,
        @Schema(description = "When Pluggy last finished updating from the bank") Instant lastSyncedAt,
        List<BankConnectionAccountResponse> accounts
) {}
