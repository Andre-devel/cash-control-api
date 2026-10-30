package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Total or partial redemption of a holding")
public record RedeemFixedIncomeRequest(
        @Schema(required = true, example = "2026-10-29") @NotNull LocalDate date,

        @Schema(description = "Redeem everything. When false or absent, grossAmount is required.")
        Boolean total,

        @Schema(description = "Partial redemption: gross amount to redeem.", example = "500.00")
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal grossAmount,

        @Schema(description = "Net amount actually received, when it differs from the estimate. Ignored with transferGroupId.", example = "487.12")
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal netAmount,

        @Schema(description = "Account that receives the net amount. Absent: the money stays in the investment account.")
        UUID toAccountId,

        @Schema(description = "Existing transfer out of the investment account (e.g. from a statement import) that already moved the net amount.")
        UUID transferGroupId
) {

    public boolean isTotal() {
        return Boolean.TRUE.equals(total);
    }
}
