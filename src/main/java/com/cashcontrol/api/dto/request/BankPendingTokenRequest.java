package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "One-time action token that came in the push notification")
public record BankPendingTokenRequest(@NotBlank @Size(max = 64) String token) {}
