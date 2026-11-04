package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.AccountScope;
import com.cashcontrol.api.domain.entity.CardBrand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record EditCardRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull CardBrand brand,
        @Size(max = 100) String issuer,
        @Pattern(regexp = "\\d{4}", message = "Informe exatamente 4 dígitos.") String last4Digits,
        @NotNull @Digits(integer = 17, fraction = 2) @DecimalMin("0.01") BigDecimal creditLimit,
        @NotNull @Min(1) @Max(31) Integer closingDay,
        @NotNull @Min(1) @Max(31) Integer dueDay,
        AccountScope scope
) {

    /** Sem escopo: a edição mantém o escopo atual do cartão. */
    public EditCardRequest(String name, CardBrand brand, String issuer, String last4Digits,
                           BigDecimal creditLimit, Integer closingDay, Integer dueDay) {
        this(name, brand, issuer, last4Digits, creditLimit, closingDay, dueDay, null);
    }
}
