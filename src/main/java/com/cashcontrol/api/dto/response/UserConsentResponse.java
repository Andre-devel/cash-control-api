package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record UserConsentResponse(
        UUID id,
        String consentVersion,
        Instant acceptedAt,
        Instant revokedAt,
        boolean active
) {}
