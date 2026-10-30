package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many bank transactions are waiting for review")
public record BankPendingCountResponse(long count) {}
