package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.MarketIndexesResponse;
import com.cashcontrol.api.service.marketdata.MarketDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Investments", description = "Fixed-income investments and the market data they are valued with")
@RestController
@RequestMapping("/api/v1/investments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class InvestmentController {

    private final MarketDataService marketDataService;

    @Operation(summary = "Latest market indexes", description = "Returns the latest synchronized CDI, SELIC and IPCA values and the base date of the Tesouro Direto prices, each flagged when older than its normal publication delay.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Indexes returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/indexes/latest")
    @PreAuthorize("isAuthenticated()")
    public MarketIndexesResponse getLatestIndexes() {
        return marketDataService.getLatest(LocalDate.now(MarketDataService.MARKET_ZONE));
    }
}
