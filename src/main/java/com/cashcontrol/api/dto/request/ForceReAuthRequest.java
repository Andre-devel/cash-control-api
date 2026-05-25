package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ForceReAuthRequest(
        @NotNull UUID targetUserId
) {}