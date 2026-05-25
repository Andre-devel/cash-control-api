package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.*;
import com.cashcontrol.api.dto.response.AuthResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.MessageResponse;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.EmailVerificationService;
import com.cashcontrol.api.service.OAuthProviderService;
import com.cashcontrol.api.service.PasswordResetService;
import com.cashcontrol.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Authentication", description = "Registration, login, logout, password management, and email verification flows")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final OAuthProviderService oAuthProviderService;

    @Operation(
            summary = "Register a new account",
            description = "Creates a new user account in PENDING_VERIFICATION status. "
                    + "Dispatches a verification email. "
                    + "Anti-enumeration: response is identical whether or not the email already exists.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created; verification email dispatched"),
            @ApiResponse(responseCode = "400", description = "Validation error (invalid email, weak password, missing consent)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(
            summary = "Login with email and password",
            description = "Authenticates with email and password. "
                    + "Returns a short-lived JWT access token on success. "
                    + "Any failure (wrong credentials, locked account, unverified account) produces a generic HTTP 401. "
                    + "Response includes `Cache-Control: no-store`.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authentication successful; JWT access token returned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials, locked or unverified account",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        String userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT);
        AuthResponse response = authService.login(request, ip, userAgent);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    @Operation(
            summary = "Logout",
            description = "Records the AUTH_LOGOUT audit event. "
                    + "The client is responsible for discarding the JWT locally. "
                    + "No server-side session state is modified — the system is fully stateless.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logout recorded"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void logout(@AuthenticationPrincipal AuthenticatedUser principal) {
        authService.logout(principal.getUser().getId());
    }

    @Operation(
            summary = "Get own profile",
            description = "Returns the authenticated user's non-sensitive profile data. "
                    + "Never returns password hash, raw tokens, or full IP history.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
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

    @Operation(
            summary = "Change password",
            description = "Changes the authenticated user's password. "
                    + "Requires the current password for verification. "
                    + "Updates `credentials_updated_at`, invalidating all previously issued JWTs.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed; previously issued JWTs invalidated"),
            @ApiResponse(responseCode = "400", description = "Validation error or wrong current password",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        authService.changePassword(principal.getUser().getId(), request);
    }

    @Operation(
            summary = "Request password reset",
            description = "Initiates a password reset flow for the given email. "
                    + "Anti-enumeration: always returns HTTP 200 regardless of whether the email exists.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset email dispatched if the address exists and is active"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/password-reset/request")
    public MessageResponse initiatePasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.initiateReset(request.email());
        return new MessageResponse("If the email is registered, a reset link has been sent.");
    }

    @Operation(
            summary = "Complete password reset",
            description = "Validates the reset token and sets a new password. "
                    + "Updates `credentials_updated_at`, invalidating all previously issued JWTs. "
                    + "Token is single-use and expires after the configured TTL.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset successful"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired, or already-consumed token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/password-reset/confirm")
    public MessageResponse completePasswordReset(@Valid @RequestBody PasswordResetCompleteRequest request) {
        passwordResetService.completeReset(request.token(), request.newPassword());
        return new MessageResponse("Password reset successful.");
    }

    @Operation(
            summary = "Verify email address",
            description = "Activates the account by validating the email verification token. "
                    + "Transitions account from PENDING_VERIFICATION to ACTIVE.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email verified; account activated"),
            @ApiResponse(responseCode = "400", description = "Invalid or expired token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/email/verify")
    public MessageResponse verifyEmail(
            @Parameter(description = "Email verification token from the verification email", required = true)
            @RequestParam @NotBlank String token) {
        emailVerificationService.verifyEmail(token);
        return new MessageResponse("Email verified successfully.");
    }

    @Operation(
            summary = "Resend email verification",
            description = "Resends the email verification link for accounts in PENDING_VERIFICATION status. "
                    + "Anti-enumeration: always returns HTTP 200.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verification email dispatched if applicable"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/email/verify/resend")
    public MessageResponse resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resendVerification(request.email());
        return new MessageResponse("If the email is pending verification, a new verification email has been sent.");
    }

    @Operation(
            summary = "Initiate email change",
            description = "Dispatches a verification email to the new address. "
                    + "The current email remains active until the new one is verified.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verification email dispatched to new address"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/email/change")
    @PreAuthorize("isAuthenticated()")
    public MessageResponse initiateEmailChange(
            @Valid @RequestBody EmailChangeRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        emailVerificationService.initiateEmailChange(principal.getUser().getId(), request.newEmail());
        return new MessageResponse("A verification email has been sent to the new address.");
    }

    @Operation(
            summary = "Unlink OAuth2 provider",
            description = "Removes the linked OAuth2 provider from the account. "
                    + "Blocked if the account has no local password set (prevents account lockout).",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Provider unlinked"),
            @ApiResponse(responseCode = "400", description = "Cannot unlink — no local password set",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Provider not linked to this account",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/provider/{providerSlug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void unlinkProvider(
            @Parameter(description = "Provider identifier, e.g. `google`", required = true)
            @PathVariable String providerSlug,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        oAuthProviderService.unlinkProvider(principal.getUser().getId(), providerSlug);
    }
}
