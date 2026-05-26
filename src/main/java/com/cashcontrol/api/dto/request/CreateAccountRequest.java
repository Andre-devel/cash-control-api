package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.AccountType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccountType type,
        @Size(min = 3, max = 3) String currencyCode,
        @Size(max = 255) String description,
        Integer sortOrder,
        @Digits(integer = 17, fraction = 2) BigDecimal initialBalance
) {}
