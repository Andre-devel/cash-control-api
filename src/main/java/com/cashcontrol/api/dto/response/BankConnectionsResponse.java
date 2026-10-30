package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record BankConnectionsResponse(
        @Schema(description = "False when the server has no Pluggy credentials; the app then hides the feature") boolean enabled,
        List<BankConnectionResponse> connections
) {}
