package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Request body for creating a new financial account")
public record CreateAccountRequest(
        @Schema(description = "Unique account name for this user", example = "Nubank Checking")
        @NotBlank @Size(max = 100) String name,

        @Schema(description = "Account type determining how the balance is included in net worth calculations")
        @NotNull AccountType type,

        @Schema(description = "ISO 4217 currency code. Immutable after creation.", example = "BRL")
        @Size(min = 3, max = 3) String currencyCode,

        @Schema(description = "Optional account description", example = "Main everyday checking account")
        @Size(max = 255) String description,

        @Schema(description = "User-defined display order within the account list. Lower values appear first.", example = "0")
        Integer sortOrder,

        @Schema(description = "Initial balance seeded as a MANUAL_ADJUSTMENT transaction. Defaults to zero.", example = "1500.00")
        @Digits(integer = 17, fraction = 2) BigDecimal initialBalance
) {}
