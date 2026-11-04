package com.cashcontrol.api.controller;

import com.cashcontrol.api.domain.entity.BankSyncOutcome;
import com.cashcontrol.api.dto.response.BankOrphanItemResponse;
import com.cashcontrol.api.dto.response.BankSyncOverviewResponse;
import com.cashcontrol.api.dto.response.BankSyncRunResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.PageResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.openfinance.BankOrphanItemService;
import com.cashcontrol.api.service.openfinance.BankSyncRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Tag(name = "Bank sync log", description = "History of the reads of the current user's Pluggy items")
@RestController
@RequestMapping("/api/v1/admin/bank-sync")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Missing `bank-sync:view` authority",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class BankSyncRunController {

    private final BankSyncRunService bankSyncRunService;
    private final BankOrphanItemService bankOrphanItemService;

    @Operation(summary = "Sync health", description = "Last results of each of the user's connections and the next hourly poll.")
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('bank-sync:view')")
    public BankSyncOverviewResponse overview(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bankSyncRunService.overview(principal.getUser().getId(), Instant.now());
    }

    @Operation(summary = "List sync runs", description = "Runs of the user's connections, newest first.")
    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('bank-sync:view')")
    public PageResponse<BankSyncRunResponse> runs(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Only this connection") @RequestParam(required = false) UUID connectionId,
            @Parameter(description = "Only these outcomes") @RequestParam(required = false) List<BankSyncOutcome> outcome,
            @PageableDefault(size = 25, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(bankSyncRunService.list(principal.getUser().getId(), connectionId, outcome, pageable));
    }

    @Operation(summary = "List orphan Pluggy items",
            description = "Items the Pluggy webhook announced for the user that never became a connection in the app.")
    @GetMapping("/orphans")
    @PreAuthorize("hasAuthority('bank-sync:view')")
    public List<BankOrphanItemResponse> orphans(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bankOrphanItemService.list(principal.getUser().getId());
    }

    @Operation(summary = "Delete an orphan Pluggy item",
            description = "Deletes the item at Pluggy after checking it belongs to the user, then forgets it. "
                    + "An item Pluggy no longer knows is only forgotten.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "No orphan item with this id for the user",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Pluggy unavailable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/orphans/{itemId}")
    @PreAuthorize("hasAuthority('bank-sync:view')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrphan(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable String itemId) {
        bankOrphanItemService.delete(principal.getUser().getId(), itemId);
    }
}
