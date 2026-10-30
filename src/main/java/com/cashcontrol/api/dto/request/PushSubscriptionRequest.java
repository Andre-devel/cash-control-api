package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "This device's push subscription, as the browser's PushSubscription.toJSON() gives it")
public record PushSubscriptionRequest(
        @Schema(description = "Push service URL of this device", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 1024) String endpoint,
        @Schema(description = "keys.p256dh (base64url)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 255) String p256dh,
        @Schema(description = "keys.auth (base64url)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) String auth,
        @Schema(description = "Name shown for the device", example = "Android · Chrome")
        @Size(max = 100) String deviceLabel,
        @Schema(description = "true to leave amounts out of the notification (lock screen)")
        boolean hideAmounts
) {}
