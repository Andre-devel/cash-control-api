package com.cashcontrol.api.dto.response;

import java.util.UUID;

public record PermissionResponse(
        UUID id,
        String name,
        String description,
        String category,
        boolean systemPerm
) {}