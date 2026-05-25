package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.CreatePermissionRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.PageResponse;
import com.cashcontrol.api.dto.response.PermissionResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.PermissionService;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Permissions", description = "Permission catalog management. Follows the `resource:action` naming convention.")
@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PermissionController {

    private final PermissionService permissionService;

    @Operation(summary = "Create a permission", description = "Registers a new named permission in the system catalog. Requires `permission:grant` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Permission created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `permission:grant` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Permission name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('permission:grant')")
    public PermissionResponse createPermission(
            @Valid @RequestBody CreatePermissionRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return permissionService.createPermission(principal.getUser().getId(), request.name(), request.description(), request.categoryId());
    }

    @Operation(summary = "List permissions", description = "Returns a paginated list of all registered permissions. Requires `permission:grant` or `audit:view` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated permission list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing required authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAnyAuthority('permission:grant','audit:view')")
    public PageResponse<PermissionResponse> listPermissions(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(permissionService.listPermissions(pageable));
    }

    @Operation(summary = "Delete a permission", description = "Removes a permission from the catalog. Permissions currently assigned to roles or users cannot be deleted. Requires `permission:revoke` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Permission deleted"),
            @ApiResponse(responseCode = "400", description = "Permission is still in use",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `permission:revoke` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Permission not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('permission:revoke')")
    public void deletePermission(
            @Parameter(description = "Permission UUID", required = true) @PathVariable UUID permissionId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        permissionService.deletePermission(principal.getUser().getId(), permissionId);
    }
}
