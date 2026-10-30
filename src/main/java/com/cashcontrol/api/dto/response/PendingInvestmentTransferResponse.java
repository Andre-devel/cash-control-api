package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Transfer into or out of an investment account that no holding or redemption references yet — usually one that came from a statement import")
public record PendingInvestmentTransferResponse(
        UUID transferGroupId,
        @Schema(description = "Investment account side of the transfer") UUID accountId,
        String accountName,
        @Schema(description = "The other account of the transfer") String counterpartAccountName,
        LocalDate date,
        @Schema(description = "Always positive", example = "1000.00") BigDecimal amount,
        String description,
        @Schema(description = "APPLICATION: money went into the investment account; REDEMPTION: it left") Direction direction
) {

    public enum Direction { APPLICATION, REDEMPTION }
}
