package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Quantas correções a reconciliação aplicou")
public record ApplyReconciliationResult(
        int invoicePayments,
        int investments,
        int seriesMerged,
        @Schema(description = "Itens aprovados que já não valiam (aplicados antes ou dados mudaram)")
        int skipped
) {}
