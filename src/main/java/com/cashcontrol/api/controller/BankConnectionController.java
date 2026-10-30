package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.LinkBankAccountRequest;
import com.cashcontrol.api.dto.request.RegisterBankConnectionRequest;
import com.cashcontrol.api.dto.response.BankConnectionResponse;
import com.cashcontrol.api.dto.response.BankConnectionsResponse;
import com.cashcontrol.api.dto.response.ConnectTokenResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.openfinance.BankConnectionService;
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

import java.util.UUID;

@Tag(name = "Bank connections", description = "Open Finance connections through Pluggy and the link of each bank account to an app account or card")
@RestController
@RequestMapping("/api/v1/bank-connections")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Connection, account or card not found for this user",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Integration not configured or business rule violated",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "Pluggy failed",
                content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public class BankConnectionController {

    private final BankConnectionService bankConnectionService;

    @Operation(summary = "List connections", description = "Connections with their accounts, and whether the integration is configured on this server.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public BankConnectionsResponse list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return bankConnectionService.list(principal.getUser().getId());
    }

    @Operation(summary = "Connect token", description = "Token that opens the Pluggy Connect widget to connect a new bank.")
    @PostMapping("/connect-token")
    @PreAuthorize("isAuthenticated()")
    public ConnectTokenResponse createConnectToken(@AuthenticationPrincipal AuthenticatedUser principal) {
        return new ConnectTokenResponse(bankConnectionService.createConnectToken(principal.getUser().getId(), null));
    }

    @Operation(summary = "Reconnect token", description = "Token that opens the widget on an existing connection, to renew an expired consent.")
    @PostMapping("/{id}/connect-token")
    @PreAuthorize("isAuthenticated()")
    public ConnectTokenResponse createReconnectToken(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Connection UUID", required = true) @PathVariable UUID id) {
        return new ConnectTokenResponse(bankConnectionService.createConnectToken(principal.getUser().getId(), id));
    }

    @Operation(summary = "Register connection", description = "Saves the item the widget returned, after checking with Pluggy that it belongs to this user.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public BankConnectionResponse register(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody RegisterBankConnectionRequest request) {
        return bankConnectionService.register(principal.getUser().getId(), request.itemId());
    }

    @Operation(summary = "Refresh connection", description = "Reads status and accounts again from Pluggy.")
    @PostMapping("/{id}/refresh")
    @PreAuthorize("isAuthenticated()")
    public BankConnectionResponse refresh(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Connection UUID", required = true) @PathVariable UUID id) {
        return bankConnectionService.refresh(principal.getUser().getId(), id);
    }

    @Operation(summary = "Link bank account", description = "Links a bank account to an app account (BANK) or card (CREDIT); both null unlinks.")
    @PutMapping("/{id}/accounts/{accountId}/link")
    @PreAuthorize("isAuthenticated()")
    public BankConnectionResponse link(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Connection UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Bank account UUID (from the connection's accounts)", required = true) @PathVariable UUID accountId,
            @Valid @RequestBody LinkBankAccountRequest request) {
        return bankConnectionService.link(principal.getUser().getId(), id, accountId, request);
    }

    @Operation(summary = "Delete connection", description = "Revokes the consent at Pluggy and deletes the connection. Imported transactions stay.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(description = "Connection UUID", required = true) @PathVariable UUID id) {
        bankConnectionService.delete(principal.getUser().getId(), id);
    }
}
