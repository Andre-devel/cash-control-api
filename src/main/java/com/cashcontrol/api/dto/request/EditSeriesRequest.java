package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record EditSeriesRequest(
        @Size(max = 255) String description,
        @Size(max = 5000) String notes,
        UUID categoryId,
        UUID subcategoryId,
        UUID accountId,
        PaymentMethodSlug paymentMethod,
        UUID creditCardId
) {}
