package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.ForceReAuthRequest;
import com.cashcontrol.api.dto.request.ManualLockRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AdminSecurityService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin Security", description = "Administrative operations for credential invalidation and account lockout management. All endpoints require `auth:manage` authority.")
@RestController
@RequestMapping("/api/v1/admin/security")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AdminSecurityController {

    private final AdminSecurityService adminSecurityService;

    @Operation(
            summary = "Force re-authentication",
            description = "Updates `credentials_updated_at` for the target user, immediately invalidating all previously issued JWTs. "
                    + "The user must re-authenticate to obtain a new token. "
                    + "Requires `auth:manage` authority."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Re-authentication forced; all prior JWTs invalidated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `auth:manage` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Target user not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/force-reauth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('auth:manage')")
    public void forceReAuthentication(
            @Valid @RequestBody ForceReAuthRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        adminSecurityService.forceReAuthentication(principal.getUser().getId(), request.targetUserId());
    }

    @Operation(
            summary = "Manually lock account",
            description = "Applies an administrative lock to the target user account, preventing authentication regardless of brute-force counters. "
                    + "Requires `auth:manage` authority."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account locked"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `auth:manage` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Target user not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('auth:manage')")
    public void manualLockAccount(
            @Valid @RequestBody ManualLockRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        adminSecurityService.manualLockAccount(principal.getUser().getId(), request.targetUserId(), request.reason());
    }

    @Operation(
            summary = "Unlock account",
            description = "Clears any active administrative or brute-force lock on the target user account. "
                    + "Requires `auth:manage` authority."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account unlocked"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `auth:manage` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Target user not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('auth:manage')")
    public void unlockAccount(
            @Valid @RequestBody ForceReAuthRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        adminSecurityService.unlockAccount(principal.getUser().getId(), request.targetUserId());
    }
}
