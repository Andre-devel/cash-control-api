package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CategoryPieChartResponse(
        List<Entry> entries,
        BigDecimal totalAmount
) {
    public record Entry(
            UUID categoryId,
            String categoryName,
            BigDecimal totalAmount,
            BigDecimal percentage
    ) {}
}
