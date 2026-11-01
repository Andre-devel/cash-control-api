package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a test notification: devices of the user, and how many the push service accepted")
public record PushTestResponse(int devices, int delivered) {}
