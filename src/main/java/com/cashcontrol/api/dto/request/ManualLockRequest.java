package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ManualLockRequest(
        @NotNull UUID targetUserId,
        @NotBlank String reason
) {}