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
        @Schema(description = "When the Open Finance consent expires; renew it before this date") Instant consentExpiresAt,
        List<BankConnectionAccountResponse> accounts,
        @Schema(description = "Older connection of the same user to the same bank account (same BANK account number or "
                + "same set of Pluggy accounts); this one is the duplicate and can be removed") UUID duplicateOf
) {}
