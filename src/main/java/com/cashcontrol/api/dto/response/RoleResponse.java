package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.UUID;

public record RoleResponse(
        UUID id,
        String name,
        String description,
        boolean systemRole,
        int permissionCount,
        Instant createdAt
) {}