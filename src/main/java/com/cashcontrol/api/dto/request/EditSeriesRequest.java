package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record EditSeriesRequest(
        @Size(max = 255) String description,
        @Size(max = 5000) String notes,
        UUID categoryId,
        UUID subcategoryId,
        UUID accountId
) {}
