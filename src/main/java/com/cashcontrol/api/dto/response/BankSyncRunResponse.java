package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.BankSyncOutcome;
import com.cashcontrol.api.domain.entity.BankSyncRun;
import com.cashcontrol.api.domain.entity.BankSyncTrigger;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One read of a Pluggy item: scheduled poll, webhook, manual refresh or registration")
public record BankSyncRunResponse(
        UUID id,
        @Schema(description = "Null when the run was not tied to a connection still known") UUID connectionId,
        String itemId,
        @Schema(example = "Inter") String connectorName,
        BankSyncTrigger trigger,
        @Schema(example = "item/updated") String webhookEvent,
        BankSyncOutcome outcome,
        Instant startedAt,
        Instant finishedAt,
        @Schema(description = "Pluggy item status read in this run", example = "UPDATED") String itemStatus,
        @Schema(description = "When Pluggy last finished updating from the bank, as read in this run") Instant itemLastUpdatedAt,
        Integer accountsRead,
        Integer transactionsQueued,
        Integer investmentsCreated,
        Integer investmentsRedeemed,
        Integer investmentsFailed,
        @Schema(description = "transactions/created: minutes between the transaction reaching Pluggy and the webhook")
        Long webhookDelayMinutes,
        String errorMessage
) {
    public static BankSyncRunResponse from(BankSyncRun run) {
        return new BankSyncRunResponse(run.getId(), run.getConnectionId(), run.getItemId(), run.getConnectorName(),
                run.getTrigger(), run.getWebhookEvent(), run.getOutcome(), run.getStartedAt(), run.getFinishedAt(),
                run.getItemStatus(), run.getItemLastUpdatedAt(), run.getAccountsRead(), run.getTransactionsQueued(),
                run.getInvestmentsCreated(), run.getInvestmentsRedeemed(), run.getInvestmentsFailed(),
                run.getWebhookDelayMinutes(), run.getErrorMessage());
    }
}
