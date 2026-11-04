package com.cashcontrol.api.dto.response;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Pluggy item announced by the webhook that never became a connection in the app")
public record BankOrphanItemResponse(
        @Schema(description = "Pluggy item id") String itemId,
        @Schema(example = "MeuPluggy") String connectorName,
        @Schema(description = "When the webhook first announced it") Instant firstSeenAt
) {}
