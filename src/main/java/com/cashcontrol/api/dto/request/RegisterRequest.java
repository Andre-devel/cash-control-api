package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.security.validation.ValidPassword;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @ValidPassword @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        // @AssertTrue alone accepts null, so a client that omits the field would skip consent.
        @NotNull(message = "Consent to data processing is required")
        @AssertTrue(message = "Consent to data processing is required") Boolean consentAccepted
) {}