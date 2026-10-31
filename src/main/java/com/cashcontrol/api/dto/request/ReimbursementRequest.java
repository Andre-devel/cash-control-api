package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Reimbursement state of an EXPENSE. Replaces the current state.")
public record ReimbursementRequest(
        @Schema(description = "True when the expense will be (or was) paid back") boolean reimbursable,
        @Schema(description = "INCOME that paid the expense back. Null while nothing came back; must be null when reimbursable is false.")
        UUID reimbursedById
) {}
