package com.cashcontrol.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NetWorthEvolutionResponse(
        List<Snapshot> snapshots
) {
    public record Snapshot(
            LocalDate date,
            BigDecimal netWorth
    ) {}
}
