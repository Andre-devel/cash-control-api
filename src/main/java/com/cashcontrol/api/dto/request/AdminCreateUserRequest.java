package com.cashcontrol.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public record AdminCreateUserRequest(
        @NotBlank @Email String email,
        List<UUID> roleIds
) {}