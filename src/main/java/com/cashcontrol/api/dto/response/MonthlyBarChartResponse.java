package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record MonthlyBarChartResponse(
        List<Entry> months
) {
    public record Entry(
            String month,
            BigDecimal income,
            BigDecimal expenses,
            BigDecimal net
    ) {}
}
