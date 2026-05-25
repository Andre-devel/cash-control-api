package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.AdminCreateUserRequest;
import com.cashcontrol.api.dto.request.UpdateProfileRequest;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.MessageResponse;
import com.cashcontrol.api.dto.response.PageResponse;
import com.cashcontrol.api.dto.response.UserAdminResponse;
import com.cashcontrol.api.dto.response.UserConsentResponse;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.dto.response.UserSummaryResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.UserService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Users", description = "User profile management, admin user operations, and consent history")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Get own profile", description = "Returns the authenticated user's non-sensitive profile data.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileResponse getOwnProfile(@AuthenticationPrincipal AuthenticatedUser principal) {
        return userService.getOwnProfile(principal.getUser().getId());
    }

    @Operation(summary = "Get own consent history", description = "Returns the authenticated user's data-processing consent records.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consent history returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me/consents")
    @PreAuthorize("isAuthenticated()")
    public List<UserConsentResponse> getConsentHistory(@AuthenticationPrincipal AuthenticatedUser principal) {
        return userService.getConsentHistory(principal.getUser().getId());
    }

    @Operation(summary = "Update own profile", description = "Updates permitted profile fields for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileResponse updateOwnProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return userService.updateOwnProfile(principal.getUser().getId(), request.displayName());
    }

    @Operation(summary = "Get user by ID (admin)", description = "Returns full user details for an admin lookup. Requires `user:read` permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User details returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `user:read` permission",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('user:read')")
    public UserAdminResponse getUserById(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId) {
        return userService.getUserById(userId);
    }

    @Operation(summary = "List users (admin)", description = "Returns a paginated list of users. Requires `user:read` permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated user list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `user:read` permission",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public PageResponse<UserSummaryResponse> listUsers(
            @Parameter(description = "Filter by account status UUID") @RequestParam(required = false) UUID statusId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<UserSummaryResponse> page = userService.listUsers(statusId, pageable);
        return PageResponse.from(page);
    }

    @Operation(summary = "Admin create user", description = "Creates a new user account on behalf of an admin. Requires `user:create` permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created; activation email dispatched"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `user:create` permission",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Email already registered",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('user:create')")
    public MessageResponse adminCreateUser(
            @Valid @RequestBody AdminCreateUserRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        userService.adminCreateUser(principal.getUser().getId(), request.email(), request.roleIds());
        return new MessageResponse("User created successfully.");
    }

    @Operation(summary = "Disable user account (admin)", description = "Transitions the user to INACTIVE and invalidates all previously issued JWTs. Requires `user:update` permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account disabled; previously issued JWTs invalidated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `user:update` permission",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{userId}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('user:update')")
    public void disableUser(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @Parameter(description = "Optional reason for disabling the account") @RequestParam(required = false, defaultValue = "") String reason,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        userService.disableUser(principal.getUser().getId(), userId, reason);
    }

    @Operation(summary = "Activate user account (admin)", description = "Transitions the user from INACTIVE to ACTIVE. Requires `user:update` permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account activated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `user:update` permission",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{userId}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('user:update')")
    public void activateUser(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        userService.activateUser(principal.getUser().getId(), userId);
    }

    @Operation(summary = "Soft-delete user account (admin)", description = "Sets `deleted_at` on the user record. "
            + "No physical row removal. All previously issued JWTs are invalidated. Requires `user:delete` permission.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account soft-deleted; previously issued JWTs invalidated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing `user:delete` permission",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('user:delete')")
    public void softDeleteUser(
            @Parameter(description = "Target user UUID", required = true) @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        userService.softDeleteUser(principal.getUser().getId(), userId);
    }
}
