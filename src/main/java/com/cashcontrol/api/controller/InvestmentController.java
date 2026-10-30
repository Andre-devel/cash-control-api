package com.cashcontrol.api.controller;

import com.cashcontrol.api.domain.entity.FixedIncomeStatus;
import com.cashcontrol.api.dto.request.FixedIncomeHoldingRequest;
import com.cashcontrol.api.dto.request.ManualValueRequest;
import com.cashcontrol.api.dto.request.RedeemFixedIncomeRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.FixedIncomeHoldingResponse;
import com.cashcontrol.api.dto.response.HoldingEvolutionResponse;
import com.cashcontrol.api.dto.response.InvestmentSummaryResponse;
import com.cashcontrol.api.dto.response.MarketIndexesResponse;
import com.cashcontrol.api.dto.response.PendingInvestmentTransferResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.investment.InvestmentService;
import com.cashcontrol.api.service.investment.Valuation;
import com.cashcontrol.api.service.marketdata.MarketDataService;
import com.cashcontrol.api.service.marketdata.TreasuryBondPrice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Investments", description = "Fixed-income investments and the market data they are valued with")
@RestController
@RequestMapping("/api/v1/investments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Holding or account not found for this user",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Business rule violated",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class InvestmentController {

    private final MarketDataService marketDataService;
    private final InvestmentService investmentService;

    @Operation(summary = "Latest market indexes", description = "Returns the latest synchronized CDI, SELIC and IPCA values and the base date of the Tesouro Direto prices, each flagged when older than its normal publication delay.")
    @GetMapping("/indexes/latest")
    @PreAuthorize("isAuthenticated()")
    public MarketIndexesResponse getLatestIndexes() {
        return marketDataService.getLatest(today());
    }

    @Operation(summary = "Tesouro Direto bonds", description = "Bonds quoted on the latest base date, to pick type and maturity when registering a holding.")
    @GetMapping("/treasury-bonds")
    @PreAuthorize("isAuthenticated()")
    public List<TreasuryBondPrice> listTreasuryBonds() {
        return investmentService.listTreasuryBonds();
    }

    @Operation(summary = "Portfolio summary", description = "Totals, allocation and upcoming maturities of the active holdings, valued today, and the unallocated balance of each investment account.")
    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    public InvestmentSummaryResponse getSummary(@AuthenticationPrincipal AuthenticatedUser principal) {
        return investmentService.getSummary(principal.getUser().getId(), today());
    }

    @Operation(summary = "Pending investment transfers", description = "Transfers of the last 90 days into or out of an investment account that no holding or redemption references yet, to register the holding or reconcile the redemption.")
    @GetMapping("/pending-transfers")
    @PreAuthorize("isAuthenticated()")
    public List<PendingInvestmentTransferResponse> listPendingTransfers(@AuthenticationPrincipal AuthenticatedUser principal) {
        return investmentService.listPendingTransfers(principal.getUser().getId(), today());
    }

    @Operation(summary = "List holdings", description = "Holdings valued today. Without status, active and redeemed.")
    @GetMapping("/holdings")
    @PreAuthorize("isAuthenticated()")
    public List<FixedIncomeHoldingResponse> listHoldings(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) FixedIncomeStatus status) {
        return investmentService.listHoldings(principal.getUser().getId(), status, today());
    }

    @Operation(summary = "Get holding")
    @GetMapping("/holdings/{id}")
    @PreAuthorize("isAuthenticated()")
    public FixedIncomeHoldingResponse getHolding(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Holding UUID", required = true) @PathVariable UUID id) {
        return investmentService.getHolding(id, principal.getUser().getId(), today());
    }

    @Operation(summary = "Holding evolution", description = "Gross value at the application date, at each month end and today, against the same principal at 100% CDI.")
    @GetMapping("/holdings/{id}/evolution")
    @PreAuthorize("isAuthenticated()")
    public HoldingEvolutionResponse getEvolution(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Holding UUID", required = true) @PathVariable UUID id) {
        return investmentService.getEvolution(id, principal.getUser().getId(), today());
    }

    @Operation(summary = "Create holding", description = "Registers a holding in an INVESTMENT account. With fromAccountId, also creates the transfer that funds it; with fundingTransferGroupId, links an existing one.")
    @PostMapping("/holdings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public FixedIncomeHoldingResponse createHolding(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody FixedIncomeHoldingRequest request) {
        return investmentService.createHolding(request, principal.getUser().getId(), today());
    }

    @Operation(summary = "Update holding", description = "Principal and application date cannot change after a redemption or when the holding came from a transfer.")
    @PutMapping("/holdings/{id}")
    @PreAuthorize("isAuthenticated()")
    public FixedIncomeHoldingResponse updateHolding(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Holding UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody FixedIncomeHoldingRequest request) {
        return investmentService.updateHolding(id, request, principal.getUser().getId(), today());
    }

    @Operation(summary = "Delete holding", description = "Deletes the registration only; the funding transfer stays. Refused after a redemption.")
    @DeleteMapping("/holdings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteHolding(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Holding UUID", required = true) @PathVariable UUID id) {
        investmentService.deleteHolding(id, principal.getUser().getId());
    }

    @Operation(summary = "Set bank-informed value", description = "Gross value shown by the bank on a date; the curve continues from it. Both fields null clears it.")
    @PutMapping("/holdings/{id}/manual-value")
    @PreAuthorize("isAuthenticated()")
    public FixedIncomeHoldingResponse setManualValue(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Holding UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody ManualValueRequest request) {
        return investmentService.setManualValue(id, request, principal.getUser().getId(), today());
    }

    @Operation(summary = "Redemption preview", description = "Gross, income tax, IOF and net of a redemption on the date, without recording it.")
    @GetMapping("/holdings/{id}/redemption-preview")
    @PreAuthorize("isAuthenticated()")
    public Valuation previewRedemption(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Holding UUID", required = true) @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean total,
            @RequestParam(required = false) BigDecimal grossAmount) {
        return investmentService.previewRedemption(id, date, total, grossAmount, principal.getUser().getId(), today());
    }

    @Operation(summary = "Redeem", description = "Records a total or partial redemption: the yield (or loss) as a transaction in Investimentos › Rendimentos and the transfer of the net amount.")
    @PostMapping("/holdings/{id}/redemptions")
    @PreAuthorize("isAuthenticated()")
    public FixedIncomeHoldingResponse redeem(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Holding UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody RedeemFixedIncomeRequest request) {
        return investmentService.redeem(id, request, principal.getUser().getId(), today());
    }

    private static LocalDate today() {
        return LocalDate.now(MarketDataService.MARKET_ZONE);
    }
}
