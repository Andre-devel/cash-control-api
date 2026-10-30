package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "A device of the user that receives notifications")
public record PushSubscriptionResponse(String endpoint, String deviceLabel, boolean hideAmounts, Instant createdAt) {}
