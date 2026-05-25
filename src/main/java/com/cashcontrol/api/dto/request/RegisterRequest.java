package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.security.validation.ValidPassword;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @ValidPassword @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        @AssertTrue(message = "Consent to data processing is required") Boolean consentAccepted
) {}