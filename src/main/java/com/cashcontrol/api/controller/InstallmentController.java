package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.AdvanceInstallmentRequest;
import com.cashcontrol.api.dto.request.CreateInstallmentRequest;
import com.cashcontrol.api.dto.request.EarlySettlementRequest;
import com.cashcontrol.api.dto.request.EditInstallmentRequest;
import com.cashcontrol.api.dto.request.EditSeriesRequest;
import com.cashcontrol.api.dto.response.EarlySettlementResponse;
import com.cashcontrol.api.dto.response.EditSeriesResult;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.InstallmentSeriesDetailResponse;
import com.cashcontrol.api.dto.response.InstallmentSeriesResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.InstallmentService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Installments", description = "Installment series creation, editing, early settlement, and advance payment")
@RestController
@RequestMapping("/api/v1/installments")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class InstallmentController {

    private final InstallmentService installmentService;

    @Operation(summary = "List installment series",
            description = "Returns all installment series belonging to the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Series list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/series")
    @PreAuthorize("isAuthenticated()")
    public List<InstallmentSeriesResponse> listInstallmentSeries(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return installmentService.listInstallmentSeries(principal.getUser().getId());
    }

    @Operation(summary = "Get installment series detail",
            description = "Returns the series metadata together with all its installment transactions.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Series detail returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Installment series not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/series/{seriesId}")
    @PreAuthorize("isAuthenticated()")
    public InstallmentSeriesDetailResponse getInstallmentSeriesDetail(
            @Parameter(description = "Installment series UUID", required = true) @PathVariable UUID seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return installmentService.getInstallmentSeriesDetail(seriesId, principal.getUser().getId());
    }

    @Operation(summary = "Create installment series",
            description = "Creates an installment series with individual transaction records for each installment. The per-installment amount is split evenly; the remainder goes to the last installment.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Installment series created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation (archived account, etc.)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public InstallmentSeriesDetailResponse createInstallmentSeries(
            @Valid @RequestBody CreateInstallmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return installmentService.createInstallmentSeries(request, principal.getUser().getId());
    }

    @Operation(summary = "Edit installment series",
            description = "Updates description, notes, category, or account on all non-detached PENDING and OVERDUE installments in the series.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Series updated; affected installment count returned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Installment series not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Series is already settled",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/series/{seriesId}")
    @PreAuthorize("isAuthenticated()")
    public EditSeriesResult editSeries(
            @Parameter(description = "Installment series UUID", required = true) @PathVariable UUID seriesId,
            @Valid @RequestBody EditSeriesRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return installmentService.editSeries(seriesId, request, principal.getUser().getId());
    }

    @Operation(summary = "Delete installment series",
            description = "Permanently removes the series and all of its installments. Intended for series created by mistake: it is rejected once an installment has been paid or has reached a closed invoice — use early settlement in those cases.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Installment series deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Installment series not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Series is settled, has paid installments, reached a closed invoice, or has attachments",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/series/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteInstallmentSeries(
            @Parameter(description = "Installment series UUID", required = true) @PathVariable UUID seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        installmentService.deleteInstallmentSeries(seriesId, principal.getUser().getId());
    }

    @Operation(summary = "Edit individual installment",
            description = "Updates a single installment and detaches it from series-wide operations.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Installment updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Transaction is not an installment or is cancelled",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{transactionId}")
    @PreAuthorize("isAuthenticated()")
    public TransactionDetailResponse editInstallment(
            @Parameter(description = "Transaction UUID of the installment", required = true) @PathVariable UUID transactionId,
            @Valid @RequestBody EditInstallmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return installmentService.editInstallment(transactionId, request, principal.getUser().getId());
    }

    @Operation(summary = "Early settlement",
            description = "Cancels all remaining PENDING/OVERDUE installments and creates a single PAID settlement transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settlement recorded; cancelled installment count returned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Installment series not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Series is already settled",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/series/{seriesId}/settle")
    @PreAuthorize("isAuthenticated()")
    public EarlySettlementResponse earlySettlement(
            @Parameter(description = "Installment series UUID", required = true) @PathVariable UUID seriesId,
            @Valid @RequestBody EarlySettlementRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return installmentService.earlySettlement(seriesId, request, principal.getUser().getId());
    }

    @Operation(summary = "Advance installments",
            description = "Moves selected PENDING installments to an earlier payment date, optionally adjusting the amount. Transitions to PAID if the new date is today or past.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Installments advanced"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Installment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Installment is not PENDING",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/advance")
    @PreAuthorize("isAuthenticated()")
    public List<TransactionDetailResponse> advanceInstallments(
            @Valid @RequestBody AdvanceInstallmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return installmentService.advanceInstallments(request, principal.getUser().getId());
    }
}
