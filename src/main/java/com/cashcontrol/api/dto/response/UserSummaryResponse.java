package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record UserSummaryResponse(
        UUID id,
        String maskedEmail,
        String accountStatus,
        String authOrigin,
        Instant lastLoginAt
) {}