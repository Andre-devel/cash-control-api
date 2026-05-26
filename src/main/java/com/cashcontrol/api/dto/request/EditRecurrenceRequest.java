package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record EditRecurrenceRequest(
        @Positive BigDecimal amount,
        @Size(max = 255) String description,
        UUID categoryId,
        UUID subcategoryId,
        UUID accountId
) {}
