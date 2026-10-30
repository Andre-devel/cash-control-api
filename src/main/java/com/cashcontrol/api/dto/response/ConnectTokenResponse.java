package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Short-lived token (30 min) that opens the Pluggy Connect widget")
public record ConnectTokenResponse(String accessToken) {}
