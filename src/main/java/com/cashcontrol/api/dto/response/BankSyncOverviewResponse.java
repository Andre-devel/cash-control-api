package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.BankSyncOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Bank synchronization health of the current user's connections")
public record BankSyncOverviewResponse(
        @Schema(description = "Next hourly poll; null when scheduling is off on this server") Instant nextScheduledAt,
        @Schema(description = "Retention of the log, in days") int retentionDays,
        List<ConnectionSummary> connections
) {

    @Schema(description = "Last results of one connection")
    public record ConnectionSummary(
            UUID connectionId,
            @Schema(example = "Inter") String connectorName,
            String itemId,
            @Schema(description = "Pluggy item status", example = "UPDATED") String status,
            String statusDetail,
            @Schema(description = "When Pluggy last finished updating from the bank") Instant lastSyncedAt,
            @Schema(description = "Accounts the bank shares through this connection, to tell connections of the same connector apart")
            List<BankConnectionAccountResponse> accounts,
            Instant lastRunAt,
            BankSyncOutcome lastRunOutcome,
            @Schema(description = "Last run that ended SUCCESS or NO_CHANGE") Instant lastSuccessAt,
            @Schema(description = "Last run that ended ERROR or PARTIAL") Instant lastProblemAt,
            String lastProblemMessage,
            long runsLast24h,
            long problemsLast24h,
            long transactionsQueuedLast24h
    ) {}
}
