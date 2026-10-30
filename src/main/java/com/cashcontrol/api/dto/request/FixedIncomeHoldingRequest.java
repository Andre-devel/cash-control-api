package com.cashcontrol.api.dto.request;

import com.cashcontrol.api.domain.entity.FixedIncomeIndexer;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.domain.entity.FixedIncomeLiquidity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Criação e edição de uma aplicação. Na edição, {@code accountId}, {@code kind} e a origem do
 * dinheiro ({@code fromAccountId}, {@code fundingTransferGroupId}) são ignorados.
 */
@Schema(description = "Fixed-income holding. On update, accountId, kind and the funding fields are ignored.")
public record FixedIncomeHoldingRequest(
        @Schema(description = "INVESTMENT account that holds the investment", required = true)
        @NotNull UUID accountId,

        @Schema(required = true) @NotNull FixedIncomeKind kind,

        @Schema(description = "Required for CDB/LCI/LCA; ignored for TESOURO_DIRETO; optional for OTHER (without it, the value is the manually informed one)")
        FixedIncomeIndexer indexer,

        @Schema(description = "CDI_PERCENT/SELIC_PERCENT: percent of the index (110 = 110%). Others: percent per year.", example = "110")
        @DecimalMin("0.0001") @Digits(integer = 5, fraction = 4) BigDecimal rate,

        @Schema(required = true, example = "CDB Banco Inter 110% CDI") @NotBlank @Size(max = 100) String name,

        @Size(max = 100) String issuer,

        @Schema(description = "Only for OTHER; for the other kinds it comes from the kind (LCI/LCA exempt).")
        Boolean taxExempt,

        @Schema(description = "Defaults to DAILY without maturityDate, AT_MATURITY with it.")
        FixedIncomeLiquidity liquidity,

        @Schema(required = true, example = "2026-03-10") @NotNull LocalDate appliedAt,

        @Schema(description = "Required for AT_MATURITY and TESOURO_DIRETO.", example = "2028-03-10")
        LocalDate maturityDate,

        @Schema(description = "Amount invested. Serialized as decimal string.", required = true, example = "1000.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal principal,

        @Schema(description = "TESOURO_DIRETO: bond type as listed in /investments/treasury-bonds.", example = "Tesouro Selic")
        @Size(max = 60) String treasuryBondType,

        @Schema(description = "TESOURO_DIRETO: bonds held. Derived from principal / buy price on appliedAt when absent.", example = "0.05")
        @DecimalMin("0.00000001") @Digits(integer = 11, fraction = 8) BigDecimal treasuryQuantity,

        @Schema(description = "Create a transfer from this account to the investment account. Mutually exclusive with fundingTransferGroupId.")
        UUID fromAccountId,

        @Schema(description = "Link an existing transfer into the investment account (e.g. from a statement import).")
        UUID fundingTransferGroupId,

        @Size(max = 5000) String notes
) {}
