package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Item the Pluggy Connect widget returned in onSuccess")
public record RegisterBankConnectionRequest(
        @Schema(description = "Pluggy item id") @NotNull UUID itemId
) {}
