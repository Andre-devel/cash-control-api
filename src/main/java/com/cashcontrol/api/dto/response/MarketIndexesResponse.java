package com.cashcontrol.api.dto.response;

import com.cashcontrol.api.domain.entity.MarketIndex;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MarketIndexesResponse(
        @Schema(description = "Latest synchronized value of every index (CDI, SELIC, IPCA), always all three.")
        List<IndexValue> indexes,
        @Schema(description = "Most recent base date of the Tesouro Direto prices; null if never synchronized.", example = "2026-10-29")
        LocalDate treasuryBaseDate,
        @Schema(description = "True when the Tesouro prices are older than their normal publication delay, or missing.")
        boolean treasuryStale) {

    public record IndexValue(
            @Schema(example = "CDI") MarketIndex index,
            @Schema(description = "Null if never synchronized.", example = "2026-10-29") LocalDate date,
            @Schema(description = "CDI/SELIC: percent per business day. IPCA: percent in the month. Serialized as decimal string. Null if never synchronized.", example = "0.050788")
            BigDecimal value,
            @Schema(description = "True when the value is older than the index's normal publication delay, or missing.")
            boolean stale) {
    }
}
