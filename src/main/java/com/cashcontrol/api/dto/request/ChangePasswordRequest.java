package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.security.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @ValidPassword String newPassword
) {}