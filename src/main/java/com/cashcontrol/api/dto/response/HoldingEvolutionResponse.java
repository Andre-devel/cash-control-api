package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record HoldingEvolutionResponse(List<Point> points) {

    public record Point(
            LocalDate date,
            @Schema(description = "Gross value of the holding on the date") BigDecimal gross,
            @Schema(description = "The same principal at 100% CDI since the application") BigDecimal cdiBenchmark) {
    }
}
