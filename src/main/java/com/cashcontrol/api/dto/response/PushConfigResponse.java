package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether this server sends push notifications, and the VAPID key the browser subscribes with")
public record PushConfigResponse(boolean enabled, String publicKey) {}
