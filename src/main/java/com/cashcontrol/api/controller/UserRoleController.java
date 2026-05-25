package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.AssignRoleRequest;
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

@Tag(name = "User Role Assignment", description = "Assign and revoke roles on individual user accounts. Requires `role:update` authority.")
@RestController
@RequestMapping("/api/v1/users/{userId}/roles")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class UserRoleController {

    private final RbacAssignmentService rbacAssignmentService;

    @Operation(summary = "Assign role to user", description = "Grants a role to the specified user. Idempotent — assigning a role the user already holds is a no-op. Requires `role:update` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role assigned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `role:update` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User or role not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('role:update')")
    public void assignRoleToUser(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @Valid @RequestBody AssignRoleRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        rbacAssignmentService.assignRoleToUser(principal.getUser().getId(), userId, request.roleId());
    }

    @Operation(summary = "Revoke role from user", description = "Removes a role from the specified user. Idempotent — revoking a role the user does not hold is a no-op. Requires `role:update` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role revoked"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `role:update` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User or role not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('role:update')")
    public void revokeRoleFromUser(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @Parameter(description = "Role UUID to revoke", required = true) @PathVariable UUID roleId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        rbacAssignmentService.revokeRoleFromUser(principal.getUser().getId(), userId, roleId);
    }
}
