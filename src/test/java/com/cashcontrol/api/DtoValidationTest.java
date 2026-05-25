package com.cashcontrol.api;

import com.cashcontrol.api.dto.request.AdminCreateUserRequest;
import com.cashcontrol.api.dto.request.AssignPermissionRequest;
import com.cashcontrol.api.dto.request.AssignRoleRequest;
import com.cashcontrol.api.dto.request.AuditLogFilterRequest;
import com.cashcontrol.api.dto.request.ChangePasswordRequest;
import com.cashcontrol.api.dto.request.CreatePermissionRequest;
import com.cashcontrol.api.dto.request.CreateRoleRequest;
import com.cashcontrol.api.dto.request.EmailChangeRequest;
import com.cashcontrol.api.dto.request.EmailVerifyRequest;
import com.cashcontrol.api.dto.request.ForceReAuthRequest;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.ManualLockRequest;
import com.cashcontrol.api.dto.request.PasswordResetCompleteRequest;
import com.cashcontrol.api.dto.request.PasswordResetRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.dto.request.ResendVerificationRequest;
import com.cashcontrol.api.dto.request.UpdateProfileRequest;
import com.cashcontrol.api.dto.request.UpdateRoleRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DtoValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String VALID_EMAIL = "user@example.com";
    private static final String VALID_PASSWORD = "Str0ng!Pass99";

    private <T> Set<String> violationFields(T object) {
        return VALIDATOR.validate(object).stream()
                .map(cv -> cv.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private <T> Set<ConstraintViolation<T>> violations(T object) {
        return VALIDATOR.validate(object);
    }

    // =========================================================
    // RegisterRequest
    // =========================================================

    @Test
    void registerRequest_validData_noViolations() {
        var req = new RegisterRequest(VALID_EMAIL, VALID_PASSWORD, true);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void registerRequest_blankEmail_violation() {
        var req = new RegisterRequest("", VALID_PASSWORD, true);
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void registerRequest_nullEmail_violation() {
        var req = new RegisterRequest(null, VALID_PASSWORD, true);
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void registerRequest_invalidEmailFormat_violation() {
        var req = new RegisterRequest("not-an-email", VALID_PASSWORD, true);
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void registerRequest_blankPassword_violation() {
        var req = new RegisterRequest(VALID_EMAIL, "", true);
        assertThat(violationFields(req)).contains("password");
    }

    @Test
    void registerRequest_weakPassword_violation() {
        var req = new RegisterRequest(VALID_EMAIL, "weak", true);
        assertThat(violationFields(req)).contains("password");
    }

    @Test
    void registerRequest_consentFalse_violation() {
        var req = new RegisterRequest(VALID_EMAIL, VALID_PASSWORD, false);
        assertThat(violationFields(req)).contains("consentAccepted");
    }

    // =========================================================
    // LoginRequest
    // =========================================================

    @Test
    void loginRequest_validData_noViolations() {
        var req = new LoginRequest(VALID_EMAIL, "any-password");
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void loginRequest_blankEmail_violation() {
        var req = new LoginRequest("", "pass");
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void loginRequest_nullEmail_violation() {
        var req = new LoginRequest(null, "pass");
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void loginRequest_blankPassword_violation() {
        var req = new LoginRequest(VALID_EMAIL, "");
        assertThat(violationFields(req)).contains("password");
    }

    @Test
    void loginRequest_nullPassword_violation() {
        var req = new LoginRequest(VALID_EMAIL, null);
        assertThat(violationFields(req)).contains("password");
    }

    // =========================================================
    // PasswordResetRequest
    // =========================================================

    @Test
    void passwordResetRequest_validEmail_noViolations() {
        var req = new PasswordResetRequest(VALID_EMAIL);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void passwordResetRequest_blankEmail_violation() {
        var req = new PasswordResetRequest("");
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void passwordResetRequest_invalidEmail_violation() {
        var req = new PasswordResetRequest("bad-format");
        assertThat(violationFields(req)).contains("email");
    }

    // =========================================================
    // PasswordResetCompleteRequest
    // =========================================================

    @Test
    void passwordResetCompleteRequest_validData_noViolations() {
        var req = new PasswordResetCompleteRequest("valid-token", VALID_PASSWORD);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void passwordResetCompleteRequest_blankToken_violation() {
        var req = new PasswordResetCompleteRequest("", VALID_PASSWORD);
        assertThat(violationFields(req)).contains("token");
    }

    @Test
    void passwordResetCompleteRequest_weakPassword_violation() {
        var req = new PasswordResetCompleteRequest("valid-token", "weak");
        assertThat(violationFields(req)).contains("newPassword");
    }

    // =========================================================
    // EmailVerifyRequest
    // =========================================================

    @Test
    void emailVerifyRequest_validToken_noViolations() {
        var req = new EmailVerifyRequest("some-verification-token");
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void emailVerifyRequest_blankToken_violation() {
        var req = new EmailVerifyRequest("");
        assertThat(violationFields(req)).contains("token");
    }

    @Test
    void emailVerifyRequest_nullToken_violation() {
        var req = new EmailVerifyRequest(null);
        assertThat(violationFields(req)).contains("token");
    }

    // =========================================================
    // ResendVerificationRequest
    // =========================================================

    @Test
    void resendVerificationRequest_validEmail_noViolations() {
        var req = new ResendVerificationRequest(VALID_EMAIL);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void resendVerificationRequest_blankEmail_violation() {
        var req = new ResendVerificationRequest("");
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void resendVerificationRequest_invalidEmail_violation() {
        var req = new ResendVerificationRequest("not-valid");
        assertThat(violationFields(req)).contains("email");
    }

    // =========================================================
    // ChangePasswordRequest
    // =========================================================

    @Test
    void changePasswordRequest_validData_noViolations() {
        var req = new ChangePasswordRequest("Current1!", VALID_PASSWORD);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void changePasswordRequest_blankCurrentPassword_violation() {
        var req = new ChangePasswordRequest("", VALID_PASSWORD);
        assertThat(violationFields(req)).contains("currentPassword");
    }

    @Test
    void changePasswordRequest_weakNewPassword_violation() {
        var req = new ChangePasswordRequest("Current1!", "weak");
        assertThat(violationFields(req)).contains("newPassword");
    }

    // =========================================================
    // EmailChangeRequest
    // =========================================================

    @Test
    void emailChangeRequest_validEmail_noViolations() {
        var req = new EmailChangeRequest("newemail@example.com");
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void emailChangeRequest_blankEmail_violation() {
        var req = new EmailChangeRequest("");
        assertThat(violationFields(req)).contains("newEmail");
    }

    @Test
    void emailChangeRequest_invalidEmail_violation() {
        var req = new EmailChangeRequest("not-an-email");
        assertThat(violationFields(req)).contains("newEmail");
    }

    // =========================================================
    // UpdateProfileRequest
    // =========================================================

    @Test
    void updateProfileRequest_validDisplayName_noViolations() {
        var req = new UpdateProfileRequest("My Display Name");
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void updateProfileRequest_nullDisplayName_noViolations() {
        var req = new UpdateProfileRequest(null);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void updateProfileRequest_displayNameTooLong_violation() {
        var req = new UpdateProfileRequest("a".repeat(101));
        assertThat(violationFields(req)).contains("displayName");
    }

    @Test
    void updateProfileRequest_maxDisplayName_noViolations() {
        var req = new UpdateProfileRequest("a".repeat(100));
        assertThat(violations(req)).isEmpty();
    }

    // =========================================================
    // CreateRoleRequest
    // =========================================================

    @Test
    void createRoleRequest_validData_noViolations() {
        var req = new CreateRoleRequest("MANAGER", "Manages things");
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void createRoleRequest_blankName_violation() {
        var req = new CreateRoleRequest("", "desc");
        assertThat(violationFields(req)).contains("name");
    }

    @Test
    void createRoleRequest_nullName_violation() {
        var req = new CreateRoleRequest(null, "desc");
        assertThat(violationFields(req)).contains("name");
    }

    @Test
    void createRoleRequest_nameTooLong_violation() {
        var req = new CreateRoleRequest("a".repeat(101), "desc");
        assertThat(violationFields(req)).contains("name");
    }

    @Test
    void createRoleRequest_nullDescription_noViolations() {
        var req = new CreateRoleRequest("MANAGER", null);
        assertThat(violations(req)).isEmpty();
    }

    // =========================================================
    // UpdateRoleRequest
    // =========================================================

    @Test
    void updateRoleRequest_anyDescription_noViolations() {
        var req = new UpdateRoleRequest("Updated description");
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void updateRoleRequest_nullDescription_noViolations() {
        var req = new UpdateRoleRequest(null);
        assertThat(violations(req)).isEmpty();
    }

    // =========================================================
    // CreatePermissionRequest
    // =========================================================

    @Test
    void createPermissionRequest_validData_noViolations() {
        var req = new CreatePermissionRequest("user:create", "Create users", null);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void createPermissionRequest_blankName_violation() {
        var req = new CreatePermissionRequest("", "desc", null);
        assertThat(violationFields(req)).contains("name");
    }

    @Test
    void createPermissionRequest_invalidPatternUppercase_violation() {
        var req = new CreatePermissionRequest("User:Create", "desc", null);
        assertThat(violationFields(req)).contains("name");
    }

    @Test
    void createPermissionRequest_invalidPatternNoColon_violation() {
        var req = new CreatePermissionRequest("usercreate", "desc", null);
        assertThat(violationFields(req)).contains("name");
    }

    @Test
    void createPermissionRequest_invalidPatternUnderscoreInAction_violation() {
        var req = new CreatePermissionRequest("user:read_all", "desc", null);
        assertThat(violationFields(req)).contains("name");
    }

    @Test
    void createPermissionRequest_withCategoryId_noViolations() {
        var req = new CreatePermissionRequest("role:update", "Update roles", UUID.randomUUID());
        assertThat(violations(req)).isEmpty();
    }

    // =========================================================
    // AssignPermissionRequest
    // =========================================================

    @Test
    void assignPermissionRequest_validId_noViolations() {
        var req = new AssignPermissionRequest(UUID.randomUUID());
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void assignPermissionRequest_nullId_violation() {
        var req = new AssignPermissionRequest(null);
        assertThat(violationFields(req)).contains("permissionId");
    }

    // =========================================================
    // AssignRoleRequest
    // =========================================================

    @Test
    void assignRoleRequest_validId_noViolations() {
        var req = new AssignRoleRequest(UUID.randomUUID());
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void assignRoleRequest_nullId_violation() {
        var req = new AssignRoleRequest(null);
        assertThat(violationFields(req)).contains("roleId");
    }

    // =========================================================
    // AdminCreateUserRequest
    // =========================================================

    @Test
    void adminCreateUserRequest_validData_noViolations() {
        var req = new AdminCreateUserRequest(VALID_EMAIL, List.of(UUID.randomUUID()));
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void adminCreateUserRequest_blankEmail_violation() {
        var req = new AdminCreateUserRequest("", List.of());
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void adminCreateUserRequest_invalidEmail_violation() {
        var req = new AdminCreateUserRequest("bad-format", null);
        assertThat(violationFields(req)).contains("email");
    }

    @Test
    void adminCreateUserRequest_nullRoleIds_noViolations() {
        var req = new AdminCreateUserRequest(VALID_EMAIL, null);
        assertThat(violations(req)).isEmpty();
    }

    // =========================================================
    // ForceReAuthRequest
    // =========================================================

    @Test
    void forceReAuthRequest_validId_noViolations() {
        var req = new ForceReAuthRequest(UUID.randomUUID());
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void forceReAuthRequest_nullId_violation() {
        var req = new ForceReAuthRequest(null);
        assertThat(violationFields(req)).contains("targetUserId");
    }

    // =========================================================
    // ManualLockRequest
    // =========================================================

    @Test
    void manualLockRequest_validData_noViolations() {
        var req = new ManualLockRequest(UUID.randomUUID(), "Suspicious activity");
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void manualLockRequest_nullUserId_violation() {
        var req = new ManualLockRequest(null, "reason");
        assertThat(violationFields(req)).contains("targetUserId");
    }

    @Test
    void manualLockRequest_blankReason_violation() {
        var req = new ManualLockRequest(UUID.randomUUID(), "");
        assertThat(violationFields(req)).contains("reason");
    }

    @Test
    void manualLockRequest_nullReason_violation() {
        var req = new ManualLockRequest(UUID.randomUUID(), null);
        assertThat(violationFields(req)).contains("reason");
    }

    // =========================================================
    // AuditLogFilterRequest — no constraints; all fields optional
    // =========================================================

    @Test
    void auditLogFilterRequest_allNulls_noViolations() {
        var req = new AuditLogFilterRequest(null, null, null, null, null, null);
        assertThat(violations(req)).isEmpty();
    }

    @Test
    void auditLogFilterRequest_withValues_noViolations() {
        var req = new AuditLogFilterRequest(
                "auth_success",
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().minusSeconds(3600),
                Instant.now(),
                "success"
        );
        assertThat(violations(req)).isEmpty();
    }
}
