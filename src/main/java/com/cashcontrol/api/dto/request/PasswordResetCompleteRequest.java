package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.security.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetCompleteRequest(
        @NotBlank String token,
        @NotBlank @ValidPassword String newPassword
) {}