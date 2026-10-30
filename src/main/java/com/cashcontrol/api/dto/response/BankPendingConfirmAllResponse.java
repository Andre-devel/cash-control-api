package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of confirming the whole queue as suggested")
public record BankPendingConfirmAllResponse(
        @Schema(description = "Saved as suggested") int confirmed,
        @Schema(description = "Left in the queue: possible duplicates and rows that failed") int skipped
) {}
