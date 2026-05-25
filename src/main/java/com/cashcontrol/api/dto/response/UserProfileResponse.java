package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String maskedEmail,
        String displayName,
        String accountStatus,
        String authOrigin,
        Instant lastLoginAt,
        List<String> roles,
        List<String> directPermissions,
        Instant createdAt
) {}