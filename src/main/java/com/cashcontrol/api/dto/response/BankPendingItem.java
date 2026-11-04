package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Bank transaction waiting for confirmation, classified like a statement preview row. "
        + "Either accountId (checking account) or creditCardId (current bill of the linked card) is set.")
public record BankPendingItem(
        UUID id,
        @Schema(description = "Checking account; null for a card charge") UUID accountId,
        @Schema(description = "Null for a card charge", example = "Inter") String accountName,
        @Schema(description = "Credit card; null for a checking account transaction") UUID creditCardId,
        @Schema(description = "Null for a checking account transaction", example = "Inter Gold") String creditCardName,
        @Schema(description = "Installment of a card charge, when parceled", example = "1") Integer installmentNumber,
        @Schema(description = "Installments of a card charge, when parceled; confirming installment 1 "
                + "creates the series with the next ones in the following bills", example = "10") Integer totalInstallments,
        @Schema(description = "Classification and suggestions, same shape as the import preview. On a card "
                + "charge, type is EXPENSE or REFUND and only description and category can be edited") ImportPreviewRow row
) {

    /** Lançamento da conta corrente. */
    public BankPendingItem(UUID id, UUID accountId, String accountName, ImportPreviewRow row) {
        this(id, accountId, accountName, null, null, null, null, row);
    }
}
