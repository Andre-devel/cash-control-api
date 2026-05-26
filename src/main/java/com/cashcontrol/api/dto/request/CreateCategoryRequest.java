package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 100) String name,
        UUID parentId,
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex code (e.g. #FF5733)")
        @Size(max = 7) String color,
        @Size(max = 50) String icon,
        int sortOrder
) {}
