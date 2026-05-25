package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.AssignPermissionRequest;
import com.cashcontrol.api.dto.request.CreateRoleRequest;
import com.cashcontrol.api.dto.request.UpdateRoleRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.PageResponse;
import com.cashcontrol.api.dto.response.PermissionResponse;
import com.cashcontrol.api.dto.response.RoleResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.RbacAssignmentService;
import com.cashcontrol.api.service.RoleService;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Roles", description = "Role management and role-permission assignment. Requires `role:create`, `role:update`, or `role:delete` authority.")
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class RoleController {

    private final RoleService roleService;
    private final RbacAssignmentService rbacAssignmentService;

    @Operation(summary = "Create a role", description = "Creates a new named role. Requires `role:create` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Role created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `role:create` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Role name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('role:create')")
    public RoleResponse createRole(
            @Valid @RequestBody CreateRoleRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return roleService.createRole(principal.getUser().getId(), request.name(), request.description());
    }

    @Operation(summary = "List roles", description = "Returns a paginated list of all roles. Requires `role:create` or `role:update` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated role list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing required authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAnyAuthority('role:create','role:update')")
    public PageResponse<RoleResponse> listRoles(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(roleService.listRoles(pageable));
    }

    @Operation(summary = "Get role by ID", description = "Returns a single role by its UUID. Requires `role:create` or `role:update` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing required authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Role not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{roleId}")
    @PreAuthorize("hasAnyAuthority('role:create','role:update')")
    public RoleResponse getRoleById(
            @Parameter(description = "Role UUID", required = true) @PathVariable UUID roleId) {
        return roleService.getRoleById(roleId);
    }

    @Operation(summary = "Update a role", description = "Updates the description of an existing role. System roles cannot be renamed. Requires `role:update` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `role:update` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Role not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{roleId}")
    @PreAuthorize("hasAuthority('role:update')")
    public RoleResponse updateRole(
            @Parameter(description = "Role UUID", required = true) @PathVariable UUID roleId,
            @Valid @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return roleService.updateRole(principal.getUser().getId(), roleId, request.description());
    }

    @Operation(summary = "Delete a role", description = "Removes a role. System roles and roles currently assigned to users cannot be deleted. Requires `role:delete` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role deleted"),
            @ApiResponse(responseCode = "400", description = "Role is in use or is a system role",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `role:delete` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Role not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('role:delete')")
    public void deleteRole(
            @Parameter(description = "Role UUID", required = true) @PathVariable UUID roleId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        roleService.deleteRole(principal.getUser().getId(), roleId);
    }

    @Operation(summary = "List permissions for role", description = "Returns all permissions assigned to the given role. Requires `role:create` or `role:update` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permission list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing required authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Role not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{roleId}/permissions")
    @PreAuthorize("hasAnyAuthority('role:create','role:update')")
    public List<PermissionResponse> listRolePermissions(
            @Parameter(description = "Role UUID", required = true) @PathVariable UUID roleId) {
        return roleService.listRolePermissions(roleId);
    }

    @Operation(summary = "Assign permission to role", description = "Grants a permission to a role. Idempotent — assigning an already-granted permission is a no-op. Requires `permission:grant` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Permission assigned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `permission:grant` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Role or permission not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{roleId}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('permission:grant')")
    public void assignPermissionToRole(
            @Parameter(description = "Role UUID", required = true) @PathVariable UUID roleId,
            @Valid @RequestBody AssignPermissionRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        rbacAssignmentService.assignPermissionToRole(principal.getUser().getId(), roleId, request.permissionId());
    }

    @Operation(summary = "Revoke permission from role", description = "Removes a permission from a role. Idempotent — revoking a permission not held is a no-op. Requires `permission:revoke` authority.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Permission revoked"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `permission:revoke` authority",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Role or permission not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{roleId}/permissions/{permissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('permission:revoke')")
    public void revokePermissionFromRole(
            @Parameter(description = "Role UUID", required = true) @PathVariable UUID roleId,
            @Parameter(description = "Permission UUID", required = true) @PathVariable UUID permissionId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        rbacAssignmentService.revokePermissionFromRole(principal.getUser().getId(), roleId, permissionId);
    }
}
