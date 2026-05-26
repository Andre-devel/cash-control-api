package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record SpendingByCategoryResponse(
        UUID categoryId,
        String categoryName,
        BigDecimal totalAmount,
        BigDecimal percentage
) {}
