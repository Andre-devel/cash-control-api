package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCategoryRuleRequest(
        @NotBlank @Size(max = 255) String pattern,
        @NotNull UUID categoryId,
        UUID subcategoryId,
        UUID accountId,
        int priority
) {}
