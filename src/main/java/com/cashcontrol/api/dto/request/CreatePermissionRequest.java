package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CreatePermissionRequest(
        @NotBlank @Pattern(regexp = "[a-z]+:[a-z]+", message = "Permission name must follow resource:action convention (e.g. user:create)")
        String name,
        String description,
        UUID categoryId
) {}