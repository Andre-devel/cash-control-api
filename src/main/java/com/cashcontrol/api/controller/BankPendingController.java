package com.cashcontrol.api.controller;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.dto.request.BankPendingTokenRequest;
import com.cashcontrol.api.dto.request.ImportCommitRow;
import com.cashcontrol.api.dto.response.BankPendingConfirmAllResponse;
import com.cashcontrol.api.dto.response.BankPendingConfirmResponse;
import com.cashcontrol.api.dto.response.BankPendingCountResponse;
import com.cashcontrol.api.dto.response.BankPendingItem;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.openfinance.BankPendingService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Bank pending", description = "Queue of bank transactions announced by Pluggy, waiting for the user to confirm")
@RestController
@RequestMapping("/api/v1/bank-pending")
@RequiredArgsConstructor
@ApiResponses({
        @ApiResponse(responseCode = "404", description = "Pending transaction not found, or action token invalid or expired",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "The transaction could not be saved as given",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class BankPendingController {

    private final BankPendingService bankPendingService;

    @Operation(summary = "List the queue", description = "Pending transactions, oldest first, classified like the import preview.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    public List<BankPendingItem> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bankPendingService.list(principal.getUser().getId());
    }

    @Operation(summary = "Queue size", description = "For the badge in the menu.")
    @GetMapping("/count")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    public BankPendingCountResponse count(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new BankPendingCountResponse(bankPendingService.count(principal.getUser().getId()));
    }

    @Operation(summary = "Confirm one", description = "Saves the transaction as suggested, or with the user's edits (type, description, category, other side). Date, amount and key always come from the bank.")
    @PostMapping("/{id}/confirm")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    public BankPendingConfirmResponse confirm(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Pending transaction UUID", required = true) @PathVariable UUID id,
            @RequestBody(required = false) @Valid ImportCommitRow edits) {
        return bankPendingService.confirm(principal.getUser().getId(), id, edits);
    }

    @Operation(summary = "Confirm all as suggested", description = "Possible duplicates and rows that fail stay in the queue.")
    @PostMapping("/confirm-all")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    public BankPendingConfirmAllResponse confirmAll(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bankPendingService.confirmAll(principal.getUser().getId());
    }

    @Operation(summary = "Discard", description = "Leaves the queue for good; Pluggy announcing it again does not bring it back.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    public void discard(@AuthenticationPrincipal AuthenticatedUser principal,
                        @Parameter(description = "Pending transaction UUID", required = true) @PathVariable UUID id) {
        bankPendingService.discard(principal.getUser().getId(), id);
    }

    @Operation(summary = "Confirm from the notification",
            description = "Public: authorized only by the one-time token the push notification carried for this transaction (7 days).")
    @PostMapping("/{id}/confirm-by-token")
    public BankPendingConfirmResponse confirmByToken(
            @Parameter(description = "Pending transaction UUID", required = true) @PathVariable UUID id,
            @RequestBody @Valid BankPendingTokenRequest request) {
        return bankPendingService.confirmByToken(id, request.token());
    }
}
