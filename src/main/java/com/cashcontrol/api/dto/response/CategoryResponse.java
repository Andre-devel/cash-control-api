package com.cashcontrol.api.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        UUID userId,
        UUID parentId,
        String parentName,
        String name,
        String color,
        String icon,
        int sortOrder,
        boolean isDefault,
        boolean isHidden,
        boolean isArchived,
        Instant archivedAt,
        List<CategoryResponse> subcategories,
        Instant createdAt,
        Instant updatedAt
) {}
