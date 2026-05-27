package com.cashcontrol.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Request body for creating a new installment series")
public record CreateInstallmentRequest(
        @Schema(description = "UUID of the account where installment transactions will be recorded", required = true)
        @NotNull UUID accountId,

        @Schema(description = "Total committed amount for the entire series. Split evenly across installments; remainder goes to the last.", required = true, example = "1200.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal totalAmount,

        @Schema(description = "Number of installments to generate. Range: 2–360.", required = true, example = "12")
        @NotNull @Min(2) @Max(360) Integer totalInstallments,

        @Schema(description = "Payment date for the first installment. Subsequent installments are scheduled monthly from this date.", required = true, example = "2026-06-01")
        @NotNull LocalDate firstPaymentDate,

        @Schema(description = "Description applied to all generated installment transactions", required = true, example = "New laptop purchase")
        @NotBlank @Size(max = 255) String description,

        @Schema(description = "Optional notes applied to all installment transactions")
        @Size(max = 5000) String notes,

        @Schema(description = "Primary category UUID for all installment transactions")
        UUID categoryId,

        @Schema(description = "Subcategory UUID. Must be a child of categoryId when provided.")
        UUID subcategoryId
) {}
