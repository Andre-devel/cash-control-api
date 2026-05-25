package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.AssignPermissionRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.RbacAssignmentService;
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
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "User Permission Assignment", description = "Grant and revoke direct permissions on individual user accounts, bypassing role-level assignment. Requires `permission:grant` or `permission:revoke` authority.")
@RestController
@RequestMapping("/api/v1/users/{userId}/permissions")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class UserPermissionController {

    private final RbacAssignmentService rbacAssignmentService;

    @Operation(summary = "Assign permission to user", description = "Grants a direct permission to the specified user, independent of role assignments. Idempotent. Requires `permission:grant` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Permission assigned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `permission:grant` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User or permission not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('permission:grant')")
    public void assignPermissionToUser(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @Valid @RequestBody AssignPermissionRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        rbacAssignmentService.assignPermissionToUser(principal.getUser().getId(), userId, request.permissionId());
    }

    @Operation(summary = "Revoke permission from user", description = "Removes a direct permission from the specified user. Idempotent. Requires `permission:revoke` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Permission revoked"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `permission:revoke` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User or permission not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('permission:revoke')")
    public void revokePermissionFromUser(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @Parameter(description = "Permission UUID to revoke", required = true) @PathVariable UUID permissionId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        rbacAssignmentService.revokePermissionFromUser(principal.getUser().getId(), userId, permissionId);
    }
}
