package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.CreateAccountRequest;
import com.cashcontrol.api.dto.request.EditAccountRequest;
import com.cashcontrol.api.dto.request.ManualAdjustmentRequest;
import com.cashcontrol.api.dto.request.TransferRequest;
import com.cashcontrol.api.dto.response.AccountResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AccountService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Accounts", description = "Financial account management including wallets, balance computation, and transfers")
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "Create account", description = "Creates a new financial account for the authenticated user. An optional initial balance seeds a MANUAL_ADJUSTMENT transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Account name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public AccountResponse createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.createAccount(request, principal.getUser().getId());
    }

    @Operation(summary = "List accounts", description = "Returns all financial accounts for the authenticated user, sorted by display order then creation date.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<AccountResponse> listAccounts(
            @Parameter(description = "Include archived accounts in the response") @RequestParam(defaultValue = "false") boolean includeArchived,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.listAccounts(principal.getUser().getId(), includeArchived);
    }

    @Operation(summary = "Get account", description = "Returns details and computed balance for a single account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public AccountResponse getAccount(
            @Parameter(description = "Account UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.getAccount(id, principal.getUser().getId());
    }

    @Operation(summary = "Edit account", description = "Updates the account name, type, currency, description, or display order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Account name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public AccountResponse editAccount(
            @Parameter(description = "Account UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody EditAccountRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.editAccount(id, request, principal.getUser().getId());
    }

    @Operation(summary = "Archive account", description = "Marks the account as archived. Archived accounts are excluded from portfolio balance calculations.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account archived"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Account is already archived",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/archive")
    @PreAuthorize("isAuthenticated()")
    public AccountResponse archiveAccount(
            @Parameter(description = "Account UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.archiveAccount(id, principal.getUser().getId());
    }

    @Operation(summary = "Unarchive account", description = "Restores an archived account to active status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account unarchived"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Account is not archived",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/unarchive")
    @PreAuthorize("isAuthenticated()")
    public AccountResponse unarchiveAccount(
            @Parameter(description = "Account UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.unarchiveAccount(id, principal.getUser().getId());
    }

    @Operation(summary = "Delete account", description = "Soft-deletes an account. Only allowed if the account has no transactions beyond the initial seed record.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Account has existing transactions",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteAccount(
            @Parameter(description = "Account UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accountService.deleteAccount(id, principal.getUser().getId());
    }

    @Operation(summary = "Manual balance adjustment", description = "Creates a MANUAL_ADJUSTMENT transaction that changes the account balance by the specified delta amount.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Adjustment applied; updated account returned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/adjust")
    @PreAuthorize("isAuthenticated()")
    public AccountResponse manualAdjustment(
            @Parameter(description = "Account UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody ManualAdjustmentRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return accountService.manualAdjustment(id, request, principal.getUser().getId());
    }

    @Operation(summary = "Create transfer", description = "Atomically creates two linked TRANSFER transactions between two accounts owned by the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transfer created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Source or destination account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation (same account, archived, etc.)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void createTransfer(
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accountService.createTransfer(request, principal.getUser().getId());
    }

    @Operation(summary = "Delete transfer", description = "Atomically deletes both legs of a transfer identified by its group ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transfer deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transfer not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/transfers/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteTransfer(
            @Parameter(description = "Transfer group UUID", required = true) @PathVariable UUID groupId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        accountService.deleteTransfer(groupId, principal.getUser().getId());
    }
}
