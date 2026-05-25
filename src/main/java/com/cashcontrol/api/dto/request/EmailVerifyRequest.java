package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record EmailVerifyRequest(
        @NotBlank String token
) {}