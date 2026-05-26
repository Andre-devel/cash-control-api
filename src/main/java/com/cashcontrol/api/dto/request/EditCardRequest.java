package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.CardBrand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record EditCardRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull CardBrand brand,
        @Size(max = 100) String issuer,
        @NotNull @Digits(integer = 17, fraction = 2) @DecimalMin("0.01") BigDecimal creditLimit,
        @NotNull @Min(1) @Max(28) Integer closingDay,
        @NotNull @Min(1) @Max(28) Integer dueDay
) {}
